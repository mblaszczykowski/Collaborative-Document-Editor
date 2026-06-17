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

    /**
     * What one replica last told us, and whether it is still here.
     *
     * @param departedAtMillis when the replica disconnected, or 0 while it is still connected.
     *                         Kept rather than deleted on disconnect — see
     *                         {@link #markDeparted(int, long)}.
     */
    public record ReplicaProgress(VersionVector version, long reportedAtMillis,
                                  long departedAtMillis) {

        boolean stillConnected() {
            return departedAtMillis == 0;
        }
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
     * Whether this replica has been dropped from the registry and must not be written to.
     *
     * <p>Only ever set under {@link #lock}, by {@link #tryEvict()}, and only ever from false to
     * true — an evicted replica is never revived, it is replaced by a fresh load.
     */
    private boolean evicted;

    /** Thrown when an operation reaches a replica the registry has already dropped. */
    public static class StaleReplicaException extends RuntimeException {
        public StaleReplicaException(UUID documentId) {
            super("replica of document " + documentId + " was evicted while an operation "
                + "was in flight; the caller should reload it and retry");
        }
    }

    /**
     * Claims this replica for eviction, refusing if it still holds work.
     *
     * <h2>Why the check and the claim have to be one step</h2>
     * The registry used to ask {@code unwrittenCount() > 0} and then remove the replica from its
     * map, as two separate operations. Between them, another thread could take a reference from
     * the registry, merge an operation into it, and find that operation in a replica nothing
     * iterates any more: {@code flushAll} and {@code broadcastAll} both walk {@code registry
     * .loaded()}, so an orphaned replica is never written and never broadcast.
     *
     * <p>The effect is subtle rather than loud, which is what makes it worth closing. The client
     * gets no acknowledgement, so it keeps the operation in its offline buffer and resends on
     * reconnect — nothing is permanently lost. But until then <b>nobody sees the edit</b>,
     * including the author's collaborators, and no error is raised anywhere.
     *
     * <p>It needs the memory limit to be exceeded at the moment somebody edits a document being
     * evicted, so with {@code max-loaded-documents: 200} it is rare. Rare and silent is the
     * combination this project treats as worse than frequent and loud.
     */
    boolean tryEvict() {
        lock.lock();
        try {
            if (!unwritten.isEmpty() || !pendingBroadcast.isEmpty()) {
                return false;
            }
            evicted = true;
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Refuses to accept work into a replica the registry has already dropped.
     *
     * <p>Called under {@link #lock} from every mutating path, which is what makes
     * {@link #tryEvict()} an actual claim rather than a hint: once eviction has taken the lock and
     * set the flag, no operation can be added behind it. Without this the check and the removal
     * are two steps with a window between them, and an operation landing in that window goes into
     * a replica nothing iterates — never written, never broadcast, and no error anywhere.
     *
     * <p>The caller's correct response is to reload from the registry and try again, which is
     * cheap and rare. {@code DocumentService} does exactly that, once.
     */
    private void refuseIfEvicted() {
        if (evicted) {
            throw new StaleReplicaException(documentId);
        }
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
            refuseIfEvicted();
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
            refuseIfEvicted();
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
            refuseIfEvicted();
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
        // A reconnecting replica is connected again, so the departure is cleared.
        replicaProgress.put(replicaId, new ReplicaProgress(version, nowMillis, 0));
    }

    /**
     * Records that a replica disconnected — <b>keeping</b> what it had seen.
     *
     * <h2>This used to delete the entry, and the ADR said otherwise</h2>
     * [ADR-0005](../../../../../../../docs/adr/0005-tombstone-gc.md) defines the replicas that
     * constrain GC as those <i>"connected now, <b>or disconnected for less than the GC window</b>"</i>,
     * with the window set to seven days and a whole section on what happens to a client that
     * returns after it. The code implemented the first half and dropped the entry outright on
     * disconnect, so a departed replica constrained nothing at all.
     *
     * <p>The gap between the document and the code was five orders of magnitude. README describes
     * {@code RESYNC_REQUIRED} as what happens to "a client offline longer than the GC window
     * (7 days)"; what actually happened is that closing a laptop removed that client from the
     * calculation immediately, so the tombstones its pending operations were anchored to became
     * collectable as soon as everyone still present moved past them and a snapshot ran. Minutes,
     * not days — on the one path in this system that refuses a user's work by design.
     *
     * <p>Keeping the last known vector is also the semantically correct thing rather than merely
     * the documented one: what may be collected is what <b>everyone who might come back has
     * already seen</b>. Forgetting a replica collects things it never saw, which is exactly the
     * state that makes its return unmergeable.
     */
    public void markDeparted(int replicaId, long nowMillis) {
        replicaProgress.computeIfPresent(replicaId, (id, progress) ->
            new ReplicaProgress(progress.version(), progress.reportedAtMillis(), nowMillis));
    }

    public Map<Integer, ReplicaProgress> replicaProgress() {
        return Map.copyOf(replicaProgress);
    }

    /**
     * The boundary below which tombstones may be physically removed.
     *
     * <p>It is the meet of the version vectors of every replica that still counts, and two
     * different rules decide that, because two different failure modes are involved:
     *
     * <ul>
     *   <li><b>Connected but silent</b> — excluded after {@code unresponsiveTimeoutMs}. Otherwise
     *       one hung client blocks GC for the whole document (ADR-0005, negative consequences).</li>
     *   <li><b>Disconnected</b> — kept for {@code offlineGraceMillis}, the GC window. This is the
     *       half ADR-0005 specifies and the code did not implement; see
     *       {@link #markDeparted(int, long)}.</li>
     * </ul>
     *
     * <p>The distinction matters: silence from a client that is still connected is evidence
     * something is wrong with it, while silence from one that has closed its laptop is exactly
     * what is expected and is the case the window exists for.
     *
     * @param offlineGraceMillis how long a departed replica keeps constraining GC — the GC window
     */
    public VersionVector stableVersion(long nowMillis, long unresponsiveTimeoutMs,
                                       long offlineGraceMillis) {
        // Entries past the window are pruned here rather than on a timer: this is the only reader,
        // it runs at every snapshot, and a map that is only ever added to is the kind of unbounded
        // growth this class is otherwise careful about.
        replicaProgress.values().removeIf(progress ->
            !progress.stillConnected()
                && nowMillis - progress.departedAtMillis() > offlineGraceMillis);

        List<VersionVector> responsive = new ArrayList<>();
        for (ReplicaProgress progress : replicaProgress.values()) {
            boolean counts = progress.stillConnected()
                ? nowMillis - progress.reportedAtMillis() <= unresponsiveTimeoutMs
                : nowMillis - progress.departedAtMillis() <= offlineGraceMillis;
            if (counts) {
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
