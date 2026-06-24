package dev.collabedit.app.domain;

import dev.collabedit.app.persist.PendingOperation;
import dev.collabedit.app.persist.StoredOperation;
import dev.collabedit.crdt.Anchor;
import dev.collabedit.crdt.Id;
import dev.collabedit.crdt.Op;
import dev.collabedit.crdt.RgaDocument;
import dev.collabedit.crdt.TreapBlockList;
import dev.collabedit.crdt.VersionVector;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * A document replica living in this backend instance's memory.
 *
 * <p>The name is literal and follows from the cluster design: <b>a backend instance is an ordinary CRDT
 * replica</b>, a peer of the other instances and of the browsers. There is no document owner, no
 * leader election and no distributed lock — because the algorithm from the CRDT decision needs none of them,
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

    /**
     * How many flushes have drained a write batch but not yet reached the log.
     *
     * <p>Exists so a snapshot can tell whether the document's state corresponds to any
     * {@code server_seq} at all. A flush drains operations under this lock, appends them to the
     * log <b>outside</b> it, and only then advances {@link #appliedServerSeq}. In between, the
     * document holds work that is in neither {@code unwritten} nor the sequence number — so a
     * snapshot taken there would be labelled with a number lower than the state it contains.
     *
     * <p>That mislabelling is invisible to document loading, which re-applies the tail from the
     * log and is idempotent. It is not invisible to {@code HistoryService.textAt}, which trusts
     * the label to mean "exactly the operations up to here" and would show a state from slightly
     * in the future.
     *
     * <p>The window existed before the scheduler split too — a merge between reading the number
     * and taking the snapshot did the same thing — but both ran on one thread, so a whole flush
     * could never fit inside it. Moving snapshots to their own pool made it wide enough to matter,
     * which is why the counter is here rather than in a note.
     */
    private int flushesInFlight;

    /**
     * How many operations have arrived since the last snapshot — the threshold from the operation-log decision.
     *
     * <p><b>Read and written under {@link #lock} only</b>, and that is a correction rather than a
     * detail. This was a plain {@code int} incremented under the lock by {@link #merge} and the
     * enqueue paths, and read AND RESET without it from the maintenance thread — the same
     * read-modify-write hazard that cost `advanceServerSeq` five paragraphs before it was removed. Two
     * snapshot sweeps and an edit interleaving could lose an increment or, worse, reset a count
     * that had just been raised.
     *
     * <p>The reset is now a SUBTRACTION of the count observed at capture time rather than a zeroing
     * — see {@link #snapshotTaken(int)}. Zeroing threw away every operation merged between the
     * capture and the reset, so a document under continuous editing under-counted towards the
     * "500 operations" threshold by however many arrived during the database write. The effect was
     * a snapshot that kept being late in exactly the case the operation-log decision wrote the threshold for.
     */
    private int operationsSinceSnapshot;

    /**
     * When somebody last showed interest in this document — an edit OR a progress report.
     *
     * <p>Drives EVICTION, and only eviction. "Interest" is the right notion there: the cluster design says a
     * replica with no connected clients lives another five minutes, and a client reporting progress
     * every 15 seconds is a connected client.
     */
    private volatile long lastActivityMillis;

    /**
     * When this document was last CHANGED.
     *
     * <p>Separate from {@link #lastActivityMillis}, and the separation is the point. The snapshot
     * sweep's quiet branch asks "has this document been still for 30 seconds", which is a question
     * about edits — while eviction asks "is anybody here", which is a question about clients.
     *
     * <p>One field served both until a progress report started refreshing it, and the two questions
     * then answered each other's: a client reports every 15 s and the quiet threshold is 30 s, so
     * an open tab made the threshold unreachable. A document edited twenty characters and left open
     * was never snapshotted, so {@code catalog.updatePreview} never ran — its search vector and its
     * list preview stayed as they were, and the log tail replayed on the next load grew without
     * bound. Silent, and worse the longer somebody keeps the document open.
     */
    private volatile long lastEditMillis;

    // The GC boundary used to live here, as a field of the replica. It now lives in RgaDocument
    // and travels in the snapshot, because a value held only in memory was reset to empty by every
    // reload — and a replica is reloaded after five minutes of quiet, while the window the boundary
    // is compared against is seven days. See DocumentSnapshot.gcWatermark.

    // The version vectors of connected clients used to live here too, in a ConcurrentHashMap keyed
    // by replica identifier, and they had the same defect for the same reason — one instance's
    // memory holding a fact the whole cluster depends on, reset by every eviction. They now live in
    // `replica_progress` (V11). See stableVersion below for what that cost, and
    // ReplicaProgressStore for why a table rather than the snapshot this note is next to.

    public DocumentReplica(UUID documentId, RgaDocument document, long appliedServerSeq, long nowMillis) {
        this.documentId = documentId;
        this.document = document;
        this.appliedServerSeq = appliedServerSeq;
        this.lastActivityMillis = nowMillis;
        this.lastEditMillis = nowMillis;
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

    /** When the document last changed — the clock the snapshot sweep's quiet branch asks about. */
    public long lastEditMillis() {
        return lastEditMillis;
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
     *
     * <h2>Why an in-flight FLUSH also refuses, although both buffers are empty</h2>
     * {@link #drainForFlush} empties both buffers and only then raises {@link #flushesInFlight},
     * and the append that follows runs OUTSIDE this lock — it is a JDBC round trip holding the
     * document's row lock. For that whole duration the two emptiness checks above said "idle", so
     * a replica in the middle of writing up to {@code op-batch-max-size} operations was a legal
     * eviction candidate.
     *
     * <p>What that costs is exactly the failure {@link #requeueUnwritten} exists to prevent,
     * reached through the one door it did not cover: if the append throws, {@code requeueUnwritten}
     * hits {@link #refuseIfEvicted} and up to a hundred operations are visible on every connected
     * client and absent from the source of truth. Anti-entropy cannot see that either — the log's
     * head is not ahead of {@link #appliedServerSeq}, so no gap is detected — and the text silently
     * rolls back at the next restart.
     */
    boolean tryEvict() {
        lock.lock();
        try {
            if (!unwritten.isEmpty() || !pendingBroadcast.isEmpty() || flushesInFlight > 0) {
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
    public <T> T withLock(Function<RgaDocument, T> action) {
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
            lastEditMillis = nowMillis;
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
            lastEditMillis = nowMillis;
        } finally {
            lock.unlock();
        }
    }

    // enqueueForBroadcast used to sit here: "adds to the broadcast an operation we do NOT write —
    // because it is already in the log", described as the path for operations from the cluster and
    // from gap repair. It had no production caller. applyRemote does that work now, and does it
    // under ONE acquisition of the lock together with advancing the label, which is what stops the
    // state and the sequence number disagreeing. Two tests kept it compiling; they use applyRemote.


    /**
     * Applies a whole batch of operations that are ALREADY IN THE LOG, and moves this replica's
     * label to match — <b>under one acquisition of the lock</b>.
     *
     * <h2>Why the two halves may not be separate</h2>
     * The cluster path used to apply operations one at a time through {@code withLock}, queue each
     * new one for broadcast, and advance {@link #appliedServerSeq} only after the whole loop had
     * finished. Between the first {@code apply} and that advance, the replica held operations
     * ABOVE its own label while reporting itself consistent: {@link #withConsistentState} guards
     * only {@code flushesInFlight} and {@code unwritten}, and this path touches neither.
     *
     * <p>That window is not theoretical. The snapshot sweep runs on the maintenance pool (two
     * threads), cluster messages arrive on the listener pool (four to eight), and anti-entropy
     * runs alongside both. A snapshot taken inside it is stored with a {@code server_seq} LOWER
     * than the state it contains — and {@code HistoryService.textAt} trusts that label to mean
     * "exactly the operations up to here", so it returns a state from the future and
     * {@code /history/restore} builds its target from that state.
     *
     * <p>The second reason is cost, and it is the reason this shape is better rather than merely
     * safer: the old loop paid a lock acquisition, a registry lookup and a {@code PendingOperation}
     * allocation PER OPERATION on the cluster path — the path that carries every other instance's
     * traffic.
     *
     * @param incoming operations in {@code server_seq} order, as the cluster and the log deliver
     * them
     * @return the ones that were news to this replica, in arrival order — the caller broadcasts
     * and counts them
     */
    public List<Op> applyRemote(List<StoredOperation> incoming, long nowMillis) {
        lock.lock();
        try {
            refuseIfEvicted();
            List<Op> news = new ArrayList<>();
            long highest = appliedServerSeq;
            for (StoredOperation stored : incoming) {
                if (document.apply(stored.op()).isNews()) {
                    pendingBroadcast.add(new PendingOperation(stored.op(), null, null));
                    news.add(stored.op());
                }
                // Advanced for DUPLICATES too, and deliberately: the label says how far the LOG has
                // been seen, not how much of it changed anything. A replica that already held an
                // operation has still seen it.
                highest = Math.max(highest, stored.serverSeq());
            }
            appliedServerSeq = highest;
            if (!news.isEmpty()) {
                // Only when something actually changed, which is what the two clocks mean:
                // lastEditMillis drives the snapshot sweep's quiet branch and lastActivityMillis
                // drives eviction. A stream of duplicates is neither an edit nor a reason to keep
                // a replica that nobody is connected to alive.
                lastActivityMillis = nowMillis;
                lastEditMillis = nowMillis;

                // Counted toward the snapshot threshold, which it was not. `isSnapshotDue` returns
                // false the moment this counter is zero, and only the LOCAL write paths — merge,
                // enqueueForWrite, generateAndEnqueue — were touching it. So an instance holding
                // readers of a document edited elsewhere never reached a snapshot, and a snapshot is
                // the only thing that calls RgaDocument.gc: it accumulated every tombstone the
                // cluster produced and collected none of them, never pruned replicaProgress (whose
                // only pruner is stableVersion, called from the same place), and never refreshed the
                // list preview or the search vector.
                //
                // That is the ordinary steady state wherever readers outnumber writers, which is
                // what the cluster's round-robin routing produces. The operation-log design states the
                // snapshot triggers unconditionally; this is what made them conditional on an edit
                // happening to arrive locally.
                //
                // What this costs, stated in full rather than by the one reassuring half. The
                // snapshot ROW is idempotent — ON CONFLICT (doc_id, up_to_server_seq) DO NOTHING —
                // so two instances reaching the threshold together write it once. The work leading
                // up to it is not shared: each instance runs its own gc() and its own structure
                // copy under its own document lock, and its own catalog.updatePreview, which is an
                // UPDATE running to_tsvector over the whole text. On a three-instance cluster with
                // remote traffic that is three times the tsvector load.
                //
                // Paid deliberately. GC is per replica — an instance that never snapshots never
                // collects, which is the leak this line exists to close — and the preview is the
                // cost of not building a second mechanism to decide which instance owns it.
                operationsSinceSnapshot += news.size();
            }
            return news;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Generates operations against the current state and queues them for writing and broadcasting
     * — <b>under one acquisition of the lock</b>.
     *
     * <h2>The window this closes</h2>
     * Version restore, suggestions and recovered drafts all did this in two steps: take the replica
     * from the registry, generate operations under {@code withLock}, then hand them to
     * {@code DocumentService.submitAlreadyMerged} — which asks the registry for the replica AGAIN.
     * Between those two lookups the replica can be evicted: both buffers are empty at that moment,
     * so it is a perfectly legal candidate. A fresh replica is then loaded that never saw the
     * generated operations, and {@code enqueueForWrite} on it succeeds with no
     * {@link StaleReplicaException} at all — because the fresh replica is not evicted.
     *
     * <p>The result is the server's in-memory text disagreeing with its own log until the next
     * eviction, silently. Generating and enqueueing under one lock turns that window into a
     * {@code StaleReplicaException}, which the existing retry answers by regenerating against the
     * replica that is actually current — which is the only correct answer, because operations
     * generated against a stale state describe a change to a document that no longer exists.
     *
     * @param generator produces the operations; may return {@code null} to decline, in which case
     * nothing is enqueued and {@code null} comes back to the caller
     */
    public List<Op> generateAndEnqueue(Function<RgaDocument, List<Op>> generator, String userId,
                                       long nowMillis) {
        lock.lock();
        try {
            refuseIfEvicted();
            List<Op> generated = generator.apply(document);
            if (generated == null) {
                return null;
            }
            for (Op op : generated) {
                PendingOperation pending = new PendingOperation(op, userId, null);
                unwritten.add(pending);
                pendingBroadcast.add(pending);
                operationsSinceSnapshot++;
            }
            if (!generated.isEmpty()) {
                lastActivityMillis = nowMillis;
                lastEditMillis = nowMillis;
            }
            return generated;
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

    /**
     * Puts a drained write batch back at the head of the buffer, after the write failed.
     *
     * <h2>Why this exists</h2>
     * {@link #drainForFlush} REMOVES the operations from {@link #unwritten}. If
     * {@code OperationStore.append} then throws — a connection-pool timeout, a deadlock on the
     * document's row lock, a serialisation failure — nothing used to put them back. The flush loop
     * logged the exception and moved on, and up to {@code op-batch-max-size} operations were gone
     * from the source of truth while every connected client could already see them, because the
     * broadcast goes first.
     *
     * <p>Anti-entropy could not repair it either: the log's head is not AHEAD of
     * {@code appliedServerSeq}, so no gap is detected. The next eviction or restart simply rolled
     * the text back.
     *
     * <p>At the head rather than appended, because the batch came off the head and the log is
     * ordered. {@link #refuseIfEvicted} still applies: a replica the registry has dropped must not
     * accept work, and the caller reports that case rather than hiding it — those operations are
     * still in the client's unacknowledged buffer, which is the recovery path.
     */
    public void requeueUnwritten(List<PendingOperation> batch) {
        if (batch.isEmpty()) {
            return;
        }
        lock.lock();
        try {
            refuseIfEvicted();
            unwritten.addAll(0, batch);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Puts a drained broadcast batch back, because sending it failed.
     *
     * <p>At the FRONT, like {@link #requeueUnwritten}, so the operations keep their order relative
     * to anything merged while the send was in flight. Order matters less here than on the write
     * path — a client applies operations in any order by the CRDT's own guarantee — but a batch that
     * arrives shuffled relative to the log is harder to reason about in a trace for no gain.
     *
     * <p>Dropped if the replica has since been evicted — reachable only in the window between
     * {@link #drainBroadcast} emptying the buffer and this putting it back, because {@code tryEvict}
     * refuses while the buffer is non-empty.
     *
     * <p>Dropping is right for the operations this instance WROTE: they are still in
     * {@code unwritten}, which eviction refuses to leave behind. It is <b>not</b> a recovery for the
     * ones that arrived from the cluster — they are in the log, so the reloaded replica's
     * {@code appliedServerSeq} covers them and no gap is ever detected, and this instance's
     * still-connected clients do not see them until they reconnect. That is accepted rather than
     * solved: the alternative is holding operations for a replica that no longer exists, and the
     * window is one that requires an eviction to land between two statements on the flow thread.
     */
    public void requeueBroadcast(List<PendingOperation> batch) {
        if (batch.isEmpty()) {
            return;
        }
        lock.lock();
        try {
            if (evicted) {
                return;
            }
            pendingBroadcast.addAll(0, batch);
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
            OutboundBatch outbound =
                new OutboundBatch(drainBroadcast(), drainUnwritten(writeLimit));
            if (!outbound.writes().isEmpty()) {
                flushesInFlight++;
            }
            return outbound;
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

    // pendingBroadcastCount used to sit here beside unwrittenCount, symmetrically and with no
    // caller anywhere in main or in tests. The broadcast buffer is asked about by
    // hasPendingBroadcast, which is the lock-free check the 10 ms loop actually uses.

    /**
     * How many operations are BUFFERED waiting for their cause — the bound the app layer enforces.
     *
     * <p>Read from the document rather than counted here, because buffering is the algorithm's
     * business ({@code RgaDocument} holds an operation whose origin it has not seen yet) and the
     * LIMIT is the application's. See {@code collabedit.persistence.max-pending-operations} for
     * why a policy that could have lived in {@code crdt-core} deliberately does not.
     */
    public int pendingCount() {
        return withLock(RgaDocument::pendingCount);
    }

    /**
     * Ends a flush started by {@link #drainForFlush}, recording how far the log now reaches.
     *
     * <p>Must be called on every path out of the flush, including failure — otherwise
     * {@link #flushesInFlight} never returns to zero and this document stops snapshotting for the
     * lifetime of the process. Pass 0 when nothing was written.
     */
    public void flushCompleted(long highestServerSeq) {
        lock.lock();
        try {
            if (highestServerSeq > appliedServerSeq) {
                appliedServerSeq = highestServerSeq;
            }
            flushesInFlight--;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Runs an action only if the document's state corresponds <b>exactly</b> to a sequence number.
     *
     * <p>The action receives the replica and that number. It is refused — empty result — while a
     * flush is in flight or operations are waiting to be written, because in both cases the
     * document contains work whose {@code server_seq} is not settled and any label would be wrong.
     *
     * <p>Refusing rather than waiting is deliberate: the caller is the snapshot sweep, it runs
     * every second, and a snapshot is a cache. Blocking the maintenance thread on the write path
     * would trade a skipped round for exactly the coupling the scheduler split removed.
     */
    public <T> Optional<T> withConsistentState(BiFunction<RgaDocument, Long, T> action) {
        lock.lock();
        try {
            if (flushesInFlight > 0 || !unwritten.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(action.apply(document, appliedServerSeq));
        } finally {
            lock.unlock();
        }
    }

    // advanceServerSeq used to sit here, with a javadoc naming three concurrent writers of this
    // label — "the Redis listener here, the maintenance pool through gap repair, and the flow pool
    // through flushCompleted" — and explaining the read-modify-write hazard between them. By the
    // time it was removed there were ZERO: applyRemote took over the first two under the same lock
    // that applies the operations, and flushCompleted sets the field itself. A setter with a
    // documented concurrency contract and no callers is worse than no setter, because the contract
    // reads as something the code still has to honour.


    public boolean snapshotDue(int threshold) {
        lock.lock();
        try {
            return operationsSinceSnapshot >= threshold;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Records that a snapshot covering {@code countAtCapture} operations has been stored.
     *
     * <p><b>A subtraction, not a reset.</b> {@code DocumentService.takeSnapshot} captures the state
     * under the lock, writes it to the database — outside the lock, because that is a network round
     * trip — and only then comes here. Operations merged in between belong to the NEXT snapshot,
     * and zeroing the counter discarded them: a document being edited continuously under-counted
     * towards the "500 operations" threshold by exactly the number that arrived during the write,
     * so the threshold fired late in the one case the operation-log decision wrote it for.
     *
     * <p>Clamped at zero, because {@code countAtCapture} is what the caller observed and a
     * concurrent {@link #snapshotTaken(int)} could in principle have subtracted it already. A
     * negative count would make {@link #snapshotDue} unreachable for however long it took to climb
     * back, which is the failure this method exists to avoid, inverted.
     */
    public void snapshotTaken(int countAtCapture) {
        lock.lock();
        try {
            operationsSinceSnapshot = Math.max(0, operationsSinceSnapshot - countAtCapture);
        } finally {
            lock.unlock();
        }
    }

    /** The boundary below which tombstones have already been removed — read from the document. */
    public VersionVector gcWatermark() {
        return withLock(RgaDocument::gcWatermark);
    }

    /**
     * Whether an operation is permanently unanchorable because its anchor point was removed by GC.
     *
     * <p>This is the situation from the tombstone-collection design: a client was offline longer than the GC window and
     * comes back with an operation pointing at a tombstone that no longer exists. The information
     * about where those characters belong has been irretrievably lost — <b>there is no honest way
     * to merge it</b>.
     *
     * <p>The condition is deliberately narrow: an identifier must be BOTH absent and at or below
     * the GC boundary. Absence alone means nothing (the operation may have outrun its cause), and
     * being below the boundary alone means nothing either (the character may still be alive,
     * because GC removes only tombstones). Too wide a condition would reject valid operations —
     * that is, it would lose the user's work in the name of protecting it from being lost.
     *
     * <h2>Why all four operation types, when only insertions used to be checked</h2>
     * This method began with {@code if (!(op instanceof Op.Insert insert)) return false;}, so a
     * DELETE whose target characters GC had removed, and a MARK whose anchor characters it had
     * removed, were declared mergeable. They are not. {@code RgaDocument.apply} answers BUFFERED
     * for them — it cannot see the target, so it holds the operation waiting for a cause that will
     * never arrive — and {@link #merge} treats BUFFERED as news on purpose (an early operation is
     * complete, it is simply early). So the operation was written to the log and ACKNOWLEDGED to
     * the client, which released it from its offline buffer, while it never applied to anything.
     *
     * <p>That is precisely the outcome the tombstone-collection design rejects in writing — "silently losing the user's
     * work" — reached through the operation types the refusal did not cover. It also grew the
     * pending buffer permanently, which is the other half of what
     * {@code max-pending-operations} now bounds.
     *
     * <p>The per-type rules follow from what each operation needs in order to apply at all:
     * <ul>
     * <li><b>Insert</b> — its {@code originLeft}. A {@code null} origin means the start of the
     * document, which cannot be collected.</li>
     * <li><b>Delete</b> — ANY target range whose start is gone is enough, because
     * {@code tryApplyDelete} is all-or-nothing: one unreachable range holds the whole
     * operation back.</li>
     * <li><b>AddMark / RemoveMark</b> — EITHER anchor. A mark with one collected end has no
     * range to cover, and a mark is defined by both of them.</li>
     * </ul>
     */
    public boolean isPermanentlyUnanchored(Op op) {
        // Every identifier is tested against ONE reading of the boundary, under ONE acquisition of
        // the lock. Asking per identifier would let two answers come from different instants — and
        // the boundary is only stable relative to the document it was read from, which is why it
        // lives in RgaDocument rather than in a field here (a field was empty on any replica loaded
        // from a snapshot).
        return withLock(document -> {
            VersionVector collected = document.gcWatermark();
            return switch (op) {
                case Op.Insert insert -> insert.originLeft() != null
                    && isCollected(document, collected, insert.originLeft());
                case Op.Delete delete -> delete.targets().stream()
                    .anyMatch(range -> isCollected(document, collected, range.start()));
                case Op.AddMark mark ->
                    anchorCollected(document, collected, mark.start(), mark.end());
                case Op.RemoveMark mark ->
                    anchorCollected(document, collected, mark.start(), mark.end());
            };
        });
    }

    private static boolean anchorCollected(RgaDocument document, VersionVector collected,
                                           Anchor start, Anchor end) {
        return isCollected(document, collected, start.charId())
            || isCollected(document, collected, end.charId());
    }

    /** Absent from the document AND at or below the boundary — both halves, see above. */
    private static boolean isCollected(RgaDocument document, VersionVector collected, Id id) {
        return !document.contains(id) && id.lamport() <= collected.get(id.replicaId());
    }

    public int operationsSinceSnapshot() {
        lock.lock();
        try {
            return operationsSinceSnapshot;
        } finally {
            lock.unlock();
        }
    }

    // ------------------------------------------------------------------
    // Causal stability for GC
    // ------------------------------------------------------------------

    /**
     * Notes that a replica reported progress.
     *
     * <p>What it has SEEN is no longer recorded here — see {@code ReplicaProgressStore}, which
     * keeps it durably and for the whole cluster. What remains is the part that is genuinely about
     * this in-memory replica: a report is a sign of life.
     */
    public void reportProgress(long nowMillis) {
        // A progress report IS activity, and this line is what makes idle eviction mean what
        // the cluster design and application.yml both say it means: "a replica with NO CONNECTED CLIENTS
        // lives another five minutes". lastActivityMillis used to be refreshed only by merge and
        // the two enqueue paths — that is, by EDITS — so a document with people reading it and
        // nobody typing was evicted after five minutes.
        //
        // The consequence was not just a reload. onClusterMessage discards operations for a
        // document this instance does not hold, so between the eviction and the next load, remote
        // edits were dropped for the viewers connected here — and nothing repaired them, because
        // the reloaded replica's appliedServerSeq already covered the operations they never saw,
        // so no gap was ever detected.
        //
        // Every connected client sends one of these every 15 seconds (connection.ts), which is a
        // liveness signal already on the wire. Using it costs one field write and needs no
        // dependency from the domain on the WebSocket layer.
        //
        // lastActivityMillis ONLY. Refreshing lastEditMillis here would make the snapshot sweep's
        // quiet branch — "still for 30 seconds" — unreachable while any tab is open, because the
        // report arrives every 15. See the field's javadoc.
        lastActivityMillis = nowMillis;
    }

    /**
     * The boundary below which tombstones may be physically removed.
     *
     * <p>The meet of every version vector that still counts, plus this replica's own — the durable
     * state is a replica too, and a tombstone the database write has not yet seen must not be
     * removed.
     *
     * <h2>Why the vectors are a parameter now</h2>
     * They used to come from a {@code Map} on this object, filled by {@code reportProgress} from
     * this instance's own STOMP sessions. Two things were wrong with that, and neither was visible
     * from here:
     *
     * <ul>
     *   <li><b>It saw one instance.</b> Nothing carried version vectors between instances, so this
     *       returned a boundary computed from a fraction of the replicas that could still send an
     *       operation — on the multi-instance deployment the cluster design is written for. Collecting past
     *       another instance's client leaves that client's next operation permanently unmergeable.</li>
     *   <li><b>It did not survive eviction.</b> A reloaded replica started with an empty map, so a
     *       client that departed with unsynchronised work stopped constraining anything as soon as
     *       the document went quiet for five minutes — against a stated window of seven days.</li>
     * </ul>
     *
     * <p>Both are properties of where the set comes from, not of how the meet is computed, so the
     * sourcing moved out to {@code ReplicaProgressStore} and this method kept the arithmetic. The
     * two timeout rules moved with it, into the query that applies them.
     *
     * @param live the vectors of the replicas that still count, cluster-wide; empty means nothing
     *     is known, and the caller must then collect NOTHING rather than everything
     */
    public VersionVector stableVersion(Collection<VersionVector> live) {
        if (live.isEmpty()) {
            return VersionVector.empty();
        }
        List<VersionVector> responsive = new ArrayList<>(live);
        responsive.add(withLock(RgaDocument::version));
        return VersionVector.meet(responsive);
    }
}
