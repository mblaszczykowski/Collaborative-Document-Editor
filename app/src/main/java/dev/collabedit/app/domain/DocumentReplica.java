package dev.collabedit.app.domain;

import dev.collabedit.app.persist.PendingOperation;
import dev.collabedit.crdt.Id;
import dev.collabedit.crdt.Op;
import dev.collabedit.crdt.RgaDocument;
import dev.collabedit.crdt.TreapBlockList;
import dev.collabedit.crdt.VersionVector;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A document replica living in this backend instance's memory.
 *
 * <p>The name is literal and follows from ADR-0004: <b>a backend instance is an ordinary CRDT
 * replica</b>, a peer of the other instances and of the browsers. There is no document owner, no
 * leader election and no distributed lock — because the algorithm from ADR-0001 needs none of them,
 * and every one of those mechanisms would introduce a whole class of bugs that simply does not
 * exist this way.
 *
 * <p>The data structure is {@link TreapBlockList}, the O(log n) variant. The naive version was used
 * to build and test the algorithm (and still passes the same property test suite), but on the
 * production path its complexity would show up as a delay on every keystroke — see the JMH
 * benchmark in the README.
 *
 * <p><b>Synchronisation:</b> {@link RgaDocument} is not thread-safe and deliberately stays that way
 * — the core of the algorithm is not meant to deal with concurrency. Serialising access is this
 * class's job, with one lock per document. The "one lock per document" granularity is the right
 * one, because operations on different documents are entirely independent, while operations on the
 * same one have to be ordered anyway.
 */
public class DocumentReplica {

    private final UUID documentId;
    private final RgaDocument document;
    private final ReentrantLock lock = new ReentrantLock();

    /** The highest {@code server_seq} applied to this replica — the cursor for anti-entropy. */
    private volatile long appliedServerSeq;

    /** Operations accepted but not yet written. The batch is drained every {@code opBatchWindowMs}. */
    private final List<PendingOperation> unwritten = new ArrayList<>();

    /**
     * Operations merged but not yet broadcast. Drained every {@code broadcastWindowMs}.
     *
     * <p>A separate list from {@link #unwritten}, because <b>the two batches have different rhythms
     * and different purposes</b>: the broadcast is responsible for what the user sees (fast, 10 ms),
     * and the write for durability (less often, 50 ms, because it touches the disk and a row lock).
     * One shared list would force a shared cadence, that is, either slower broadcasting or more
     * frequent transactions.
     */
    private final List<PendingOperation> pendingBroadcast = new ArrayList<>();

    /** How many operations have arrived since the last snapshot — the threshold from ADR-0003. */
    private int operationsSinceSnapshot;

    private volatile long lastActivityMillis;

    /**
     * The boundary below which tombstones have already been physically removed.
     *
     * <p>Without remembering this value there is no way to distinguish two situations that look
     * identical from outside: an operation that arrived before its cause (it will wait in the
     * buffer, normal) from one whose cause was removed by GC (it will never arrive — ADR-0005). The
     * first calls for patience, the second for an explicit refusal.
     */
    private volatile VersionVector gcWatermark = VersionVector.empty();

    /**
     * The version vectors of connected clients — the basis for computing causal stability for GC
     * (ADR-0005). The key is the client replica's identifier.
     */
    private final Map<Integer, ReplicaProgress> replicaProgress = new ConcurrentHashMap<>();

    public record ReplicaProgress(VersionVector version, long reportedAtMillis) {
    }

    public DocumentReplica(UUID documentId, RgaDocument document, long appliedServerSeq, long nowMillis) {
        this.documentId = documentId;
        this.document = document;
        this.appliedServerSeq = appliedServerSeq;
        this.lastActivityMillis = nowMillis;
    }

    public UUID documentId() {
        return documentId;
    }

    public long appliedServerSeq() {
        return appliedServerSeq;
    }

    public long lastActivityMillis() {
        return lastActivityMillis;
    }

    /**
     * Runs an action under the document's lock.
     *
     * <p>Every path that touches {@link RgaDocument} goes through here — including reading the
     * text, because a read while the structure is being modified would give a result from an
     * inconsistent state.
     */
    public <T> T withLock(java.util.function.Function<RgaDocument, T> action) {
        lock.lock();
        try {
            return action.apply(document);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Merges an operation and records it for writing.
     *
     * @return {@code true} when the operation contributed something new; {@code false} when it was
     *         a duplicate and there is nothing to write or broadcast
     */
    public boolean merge(Op op, String userId, String traceparent, long nowMillis) {
        lock.lock();
        try {
            RgaDocument.ApplyResult result = document.apply(op);
            lastActivityMillis = nowMillis;
            if (result.isNews()) {
                // Buffered operations (BUFFERED) are written too. They are complete and correct —
                // all they lack is a cause that will arrive later. Deferring their write until
                // integration would mean an instance dying loses an operation we have already
                // accepted from a client.
                PendingOperation pending = new PendingOperation(op, userId, traceparent);
                unwritten.add(pending);
                pendingBroadcast.add(pending);
                operationsSinceSnapshot++;
            }
            return result.isNews();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Adds to the batch an operation that has already been merged (version restore, CP5). It skips
     * merging, because the operation was created on this very replica.
     */
    public void enqueueForWrite(Op op, String userId, long nowMillis) {
        lock.lock();
        try {
            PendingOperation pending = new PendingOperation(op, userId, null);
            unwritten.add(pending);
            pendingBroadcast.add(pending);
            operationsSinceSnapshot++;
            lastActivityMillis = nowMillis;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Adds to the broadcast an operation we do NOT write — because it is already in the log.
     *
     * <p>The path for operations received from the cluster and pulled during gap repair (ADR-0004).
     * They reached the database on another instance, so writing them again would be a duplicate,
     * but the clients connected here have to see them.
     */
    public void enqueueForBroadcast(Op op, long nowMillis) {
        lock.lock();
        try {
            pendingBroadcast.add(new PendingOperation(op, null, null));
            lastActivityMillis = nowMillis;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Takes the write batch, at most {@code limit} operations.
     *
     * <p>The upper bound is not decorative. Without it one transaction can cover arbitrarily many
     * operations — and {@link dev.collabedit.app.persist.OperationStore#append} holds the document's
     * row lock while it does, that is, it blocks <b>every writer to that document, including ones
     * on other instances</b>. A bounded batch means a bounded time holding the lock.
     *
     * <p>The rest stays in the buffer and goes out in the next window, that is, in
     * {@code op-batch-window-ms}. The delay is the right behaviour here: under a sudden flood of
     * operations we prefer a steady, predictable write rhythm to one huge transaction that stops
     * the document for as long as it lasts.
     */
    public List<PendingOperation> drainUnwritten(int limit) {
        lock.lock();
        try {
            if (unwritten.isEmpty()) {
                return List.of();
            }
            if (unwritten.size() <= limit) {
                List<PendingOperation> batch = List.copyOf(unwritten);
                unwritten.clear();
                return batch;
            }
            List<PendingOperation> batch = List.copyOf(unwritten.subList(0, limit));
            unwritten.subList(0, limit).clear();
            return batch;
        } finally {
            lock.unlock();
        }
    }

    /** Takes everything waiting to be broadcast. No limit — see {@link OutboundBatch}. */
    public List<PendingOperation> drainBroadcast() {
        lock.lock();
        try {
            if (pendingBroadcast.isEmpty()) {
                return List.of();
            }
            List<PendingOperation> batch = List.copyOf(pendingBroadcast);
            pendingBroadcast.clear();
            return batch;
        } finally {
            lock.unlock();
        }
    }

    /**
     * The outbound batch: what to broadcast and what to write, taken <b>under a single acquisition
     * of the lock</b>.
     *
     * <p>The atomicity is not decorative but a correctness requirement. The client detects lost
     * broadcasts by receiving an ACKNOWLEDGEMENT for an operation it does not have (see
     * {@code connection.ts}). If the two buffers were drained separately, an operation could slip
     * in between: it would land in the write batch and get acknowledged — while its broadcast
     * waited another window. The client would treat that as a gap and ask for a full
     * resynchronisation. The recovery mechanism would then fire regularly on a perfectly healthy
     * system.
     *
     * <p>The broadcast has no size limit and the write does. That is deliberate: the write limit
     * protects how long a database row lock is held, and a broadcast holds no lock at all. Limiting
     * it would only delay what the user is meant to see.
     *
     * @param writeLimit the upper bound on the number of operations in the write batch
     */
    public OutboundBatch drainForFlush(int writeLimit) {
        lock.lock();
        try {
            // The lock is reentrant, and holding it from outside guarantees nobody adds an
            // operation between these two drains.
            return new OutboundBatch(drainBroadcast(), drainUnwritten(writeLimit));
        } finally {
            lock.unlock();
        }
    }

    /**
     * @param broadcast operations to send to this instance's clients
     * @param writes    operations to append to the operation log
     */
    public record OutboundBatch(List<PendingOperation> broadcast, List<PendingOperation> writes) {
    }

    /**
     * Whether the replica has anything to send or write — checked <b>without the lock</b>.
     *
     * <p>The {@code flushAll} loop runs every 50 ms over every loaded document. With one document
     * taking the lock was unnoticeable; with five hundred it is ten thousand lock acquisitions per
     * second, and in normal traffic almost all of them on empty buffers.
     *
     * <p>Reading without the lock is <b>safe despite the race</b>, not because there is no race.
     * There are two possible mistakes and both are harmless:
     * <ul>
     *   <li>a FALSE POSITIVE — we take the lock and find nothing; it costs one unnecessary
     *       acquisition,</li>
     *   <li>a FALSE NEGATIVE — an operation added exactly during the check waits for the next
     *       window, that is, 50 ms. It is not lost: the buffers are drained whole rather than
     *       incrementally.</li>
     * </ul>
     * The sizes are read from an {@code ArrayList} without synchronisation — at worst we see a
     * stale value rather than a corrupted one, because {@code size} is a plain {@code int}.
     */
    public boolean hasPendingWork() {
        return !unwritten.isEmpty() || !pendingBroadcast.isEmpty();
    }

    /**
     * Whether there is anything to broadcast — a lock-free check, as above.
     *
     * <p>Separate from {@link #hasPendingWork()}, because the broadcast loop runs <b>five times
     * more often</b> than the write loop (10 ms against 50 ms) and cares about only one of the two
     * buffers. Using the wider condition would take the lock of a document that has something to
     * write but nothing to send — which is most passes.
     */
    public boolean hasPendingBroadcast() {
        return !pendingBroadcast.isEmpty();
    }

    public int unwrittenCount() {
        lock.lock();
        try {
            return unwritten.size();
        } finally {
            lock.unlock();
        }
    }

    public int pendingBroadcastCount() {
        lock.lock();
        try {
            return pendingBroadcast.size();
        } finally {
            lock.unlock();
        }
    }

    public void advanceServerSeq(long serverSeq) {
        if (serverSeq > appliedServerSeq) {
            appliedServerSeq = serverSeq;
        }
    }

    public boolean snapshotDue(int threshold) {
        return operationsSinceSnapshot >= threshold;
    }

    public void snapshotTaken() {
        operationsSinceSnapshot = 0;
    }

    public void recordGc(VersionVector stable) {
        this.gcWatermark = stable;
    }

    public VersionVector gcWatermark() {
        return gcWatermark;
    }

    /**
     * Whether an operation is permanently unanchorable because its anchor point was removed by GC.
     *
     * <p>This is the situation from ADR-0005: a client was offline longer than the GC window and
     * comes back with an operation pointing at a tombstone that no longer exists. The information
     * about where those characters belong has been irretrievably lost — <b>there is no honest way
     * to merge it</b>.
     *
     * <p>The condition is deliberately narrow: the origin must be BOTH absent and below the GC
     * boundary. Absence alone means nothing (the operation may have outrun its cause), and being
     * below the boundary alone means nothing either (the character may still be alive, because GC
     * removes only tombstones). Too wide a condition would reject valid operations — that is, it
     * would lose the user's work in the name of protecting it from being lost.
     */
    public boolean isPermanentlyUnanchored(Op op) {
        if (!(op instanceof Op.Insert insert) || insert.originLeft() == null) {
            return false;
        }
        Id origin = insert.originLeft();
        return withLock(document -> !document.contains(origin))
            && origin.lamport() <= gcWatermark.get(origin.replicaId());
    }

    public int operationsSinceSnapshot() {
        return operationsSinceSnapshot;
    }

    // ------------------------------------------------------------------
    // Causal stability for GC (ADR-0005)
    // ------------------------------------------------------------------

    public void reportProgress(int replicaId, VersionVector version, long nowMillis) {
        replicaProgress.put(replicaId, new ReplicaProgress(version, nowMillis));
    }

    public void forgetReplica(int replicaId) {
        replicaProgress.remove(replicaId);
    }

    public Map<Integer, ReplicaProgress> replicaProgress() {
        return Map.copyOf(replicaProgress);
    }

    /**
     * The boundary below which tombstones may be physically removed.
     *
     * <p>It is the meet of the version vectors of every <b>responsive</b> replica. A replica that
     * has not reported progress for {@code unresponsiveTimeoutMs} despite an active connection is
     * excluded from the calculation — otherwise one hung client would block GC for the whole
     * document (ADR-0005, the negative consequences section).
     */
    public VersionVector stableVersion(long nowMillis, long unresponsiveTimeoutMs) {
        List<VersionVector> responsive = new ArrayList<>();
        for (ReplicaProgress progress : replicaProgress.values()) {
            if (nowMillis - progress.reportedAtMillis() <= unresponsiveTimeoutMs) {
                responsive.add(progress.version());
            }
        }
        if (responsive.isEmpty()) {
            return VersionVector.empty();
        }
        // The durable state is a replica too: a tombstone the database write has not yet seen must
        // not be removed.
        responsive.add(withLock(RgaDocument::version));
        return VersionVector.meet(responsive);
    }
}
