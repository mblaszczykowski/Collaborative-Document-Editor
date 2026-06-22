package dev.collabedit.app.domain;

import dev.collabedit.app.cluster.ClusterBus;
import dev.collabedit.app.config.CollabEditProperties;
import dev.collabedit.app.config.SchedulingConfig;
import dev.collabedit.app.persist.DocumentCatalog;
import dev.collabedit.app.persist.OperationStore;
import dev.collabedit.app.persist.PendingOperation;
import dev.collabedit.app.persist.SnapshotStore;
import dev.collabedit.app.observability.DocumentMetrics;
import dev.collabedit.app.observability.EditTracer;
import dev.collabedit.app.persist.StoredOperation;
import dev.collabedit.crdt.DocumentSnapshot;
import dev.collabedit.crdt.Op;
import dev.collabedit.crdt.RgaDocument;
import dev.collabedit.crdt.VersionVector;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * The operation write path — an implementation of the step order from the cluster design.
 *
 * <pre>
 *   client ──op──► 1. merge into the in-memory replica    (immediately)
 *                  2. broadcast to our own clients        (batched, every 10 ms)
 *                  3. write to the operation log          (batched, every 50 ms)
 *                  4. acknowledgement with a server_seq   (after the write)
 *                  5. publish to Redis                    (after the write, best-effort)
 * </pre>
 *
 * <p>The order is not arbitrary:
 * <ul>
 *   <li><b>Broadcast before the write</b>, because the broadcast is what the user sees. Waiting for
 *       the disk would add latency to the most noticeable thing in the system. Both batches are
 *       taken under a single acquisition of the lock and the broadcast goes first — an
 *       acknowledgement that outruns the broadcast is read by the client as a gap, and it asks for
 *       a full resynchronisation (see {@link DocumentReplica#drainForFlush}).</li>
 *   <li><b>Acknowledgement after the write</b>, because only then may the client release the
 *       operation from its offline buffer. Otherwise an instance dying in that window leaves an
 *       operation others can already see and nobody can reconstruct.</li>
 *   <li><b>Publish after the write</b>, because the receiving instance treats the
 *       {@code server_seq} it gets as reached. Publishing before the write could announce a number
 *       that will never be in the database — and that nobody will ever be able to pull.</li>
 * </ul>
 */
@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    private final DocumentRegistry registry;
    private final OperationStore operations;
    private final SnapshotStore snapshots;
    private final DocumentCatalog catalog;
    private final ClusterBus cluster;
    private final CollabEditProperties properties;
    private final DocumentMetrics metrics;
    private final EditTracer tracer;

    /** Called when operations are broadcast; wired up by the WebSocket layer. */
    private volatile BiConsumer<UUID, List<StoredOperation>> onBroadcast = (id, ops) -> { };
    private volatile BiConsumer<UUID, List<Op>> onFastPath = (id, ops) -> { };

    public DocumentService(DocumentRegistry registry, OperationStore operations,
                           SnapshotStore snapshots, DocumentCatalog catalog, ClusterBus cluster,
                           CollabEditProperties properties, DocumentMetrics metrics,
                           EditTracer tracer) {
        this.registry = registry;
        this.operations = operations;
        this.snapshots = snapshots;
        this.catalog = catalog;
        this.cluster = cluster;
        this.properties = properties;
        this.metrics = metrics;
        this.tracer = tracer;
    }

    @PostConstruct
    void subscribeToCluster() {
        cluster.onMessage(this::onClusterMessage);
        // Filtering by channel name, BEFORE parsing the payload. The subscription is a pattern
        // subscription, so without this every instance would parse the JSON of the whole cluster's
        // traffic.
        cluster.interestedIn(registry::isLoaded);
    }

    /** Broadcasting operations to this instance's clients (step 2) — with no {@code server_seq}. */
    public void onFastPath(BiConsumer<UUID, List<Op>> handler) {
        this.onFastPath = handler;
    }

    /** Acknowledgements after the write (step 4) — carrying a {@code server_seq}. */
    public void onAcknowledged(BiConsumer<UUID, List<StoredOperation>> handler) {
        this.onBroadcast = handler;
    }

    // ------------------------------------------------------------------
    // Steps 1-2: accepting an operation from a client
    // ------------------------------------------------------------------

    /**
     * Accepts an operation that has already been merged into the server's replica.
     *
     * <p>Used when restoring a version from history (point 6): the operations are created
     * on the server's replica, so they are already part of its state and only need writing and
     * broadcasting. Merging them again would be harmless but misleading — the code should make
     * clear this is not the same path as an operation from a client.
     */
    public void submitAlreadyMerged(UUID documentId, Op op, String userId) {
        // Queues for the write AND for the broadcast — both paths go out in batches.
        onActiveReplica(documentId,
            replica -> replica.enqueueForWrite(op, userId, System.currentTimeMillis()));
    }

    /**
     * Generates operations against the document's current state and submits them — <b>as one
     * step</b>.
     *
     * <h2>Why this exists rather than "generate, then call submitAlreadyMerged"</h2>
     * That is what version restore, suggestions and recovered drafts used to do, and it has a
     * window in it: generating takes the replica from the registry and {@code submitAlreadyMerged}
     * asks the registry for it AGAIN. In between, the replica can be evicted — both buffers are
     * empty at exactly that moment, so it is a legal candidate — and a FRESH replica is loaded that
     * never saw the generated operations. {@code enqueueForWrite} then succeeds on it without a
     * {@link DocumentReplica.StaleReplicaException}, because the fresh replica is not evicted, and
     * the server's in-memory text silently disagrees with its own log until the next eviction.
     *
     * <p>Generating and enqueueing under one lock turns that into a stale-replica exception, and
     * {@link #onActiveReplica} answers it by running the generator AGAIN against the replica that
     * is actually current. Regenerating is the only correct retry here: operations computed against
     * a state that has been replaced describe a change to a document that no longer exists.
     *
     * @param generator produces the operations against the live document; may return {@code null}
     * to decline — an anchor that has been deleted, say — in which case nothing is
     * enqueued and {@code null} comes back
     * @return the operations generated and submitted, or {@code null} if the generator declined
     */
    public List<Op> generateAndSubmit(UUID documentId, Function<RgaDocument, List<Op>> generator,
                                      String userId) {
        long now = System.currentTimeMillis();
        List<List<Op>> result = new ArrayList<>(1);
        result.add(null);
        onActiveReplica(documentId,
            replica -> result.set(0, replica.generateAndEnqueue(generator, userId, now)));
        return result.getFirst();
    }

    /**
     * Runs an action against a replica that is still the registry's, retrying once if it is not.
     *
     * <h2>The window this closes</h2>
     * A caller takes a replica from the registry and then uses it. Between those two moments the
     * memory limit can evict it, and until now the operation would land in a replica nothing
     * iterates: {@code flushAll} and {@code broadcastAll} both walk {@code registry.loaded()}, so
     * it would never be written and never broadcast — with no error raised anywhere.
     *
     * <p>{@code DocumentReplica.tryEvict} now claims the replica under its own lock and the
     * mutating methods refuse afterwards, which converts that silence into a
     * {@link DocumentReplica.StaleReplicaException}. Here is where it is answered: reload and try
     * again. Once, not in a loop — a second eviction of a document being actively written to,
     * inside a single operation, would mean the limit is so far below the working set that
     * retrying is not the problem to solve.
     */
    private void onActiveReplica(UUID documentId, Consumer<DocumentReplica> action) {
        try {
            action.accept(registry.get(documentId));
        } catch (DocumentReplica.StaleReplicaException stale) {
            log.debug("Replica of {} was evicted mid-operation; reloading and retrying", documentId);
            action.accept(registry.get(documentId));
        }
    }

    /** Thrown when an operation can no longer be merged because GC removed its anchor point. */
    public static class ResyncRequiredException extends RuntimeException {
        public ResyncRequiredException(String message) {
            super(message);
        }
    }

    /**
     * Thrown when a client submits an operation under a replica identifier that belongs to
     * somebody else.
     *
     * <p>Separate from {@link ResyncRequiredException} because the client's correct response is
     * different: a resynchronisation would not help, and repeating the operation will not either.
     *
     * <p>The check itself lives in {@code DocumentStompController}, not here. It needs to know
     * which sessions are CONNECTED — see {@code SessionLifecycle.userOfReplica} for why liveness is
     * the right question and why the two obvious alternatives, session equality and the operation
     * log's {@code user_id}, are each wrong in a different direction. This layer keeps the
     * exception because it is part of the submit contract; it does not keep the transport state
     * the answer depends on.
     */
    public static class ReplicaIdConflictException extends RuntimeException {
        public ReplicaIdConflictException(String message) {
            super(message);
        }
    }

    public void submit(UUID documentId, Op op, String userId) {
        DocumentReplica replica = registry.get(documentId);

        // the tombstone-collection design: we refuse EXPLICITLY, rather than appending the text in a random place
        // (silent content corruption) or ignoring it (silently losing work). Both alternatives are
        // worse than an honest "I cannot merge this".
        if (replica.isPermanentlyUnanchored(op)) {
            metrics.recordStaleRejection();
            throw new ResyncRequiredException(
                "operation " + op.id() + " points at a character removed by garbage collection");
        }

        refuseIfPendingBufferFull(replica);

        boolean isNews;
        // The context is captured NOW, while we are still inside the span for accepting the
        // operation. The write batch runs later, on a different thread, where that context is gone.
        String traceparent = tracer.currentTraceparent();
        var merging = metrics.startTimer();
        try (var span = tracer.startStage("crdt.merge")) {
            isNews = mergeRetryingIfEvicted(documentId, replica, op, userId, traceparent);
            span.attribute("collabedit.op.news", String.valueOf(isNews));
        }
        metrics.recordMerge(merging);

        // A duplicate has nothing to write or broadcast; it happens on retransmission after a
        // reconnect, so it is a normal situation rather than an error. A new operation is already
        // in the broadcast buffer (merge put it there) and goes out in the next window.
        //
        // op.server.latency is NOT recorded here, and that is the fix rather than an omission. It
        // used to stop on this line, immediately after the merge — while the metric's own
        // description says "intake -> broadcast", which happens up to broadcast-window-ms later on
        // the flow scheduler. Two timers were bracketing almost the same code under different
        // names. The real interval is recorded in broadcast, from the instant PendingOperation
        // carries.
    }


    /**
     * Refuses when the replica is already holding the maximum number of operations waiting for a
     * cause.
     *
     * <p>The buffer is where {@code RgaDocument} keeps an operation whose origin it has not seen
     * yet, and {@link DocumentReplica#merge} deliberately writes and broadcasts buffered operations
     * — they are complete, they are simply early. Nothing checked that an origin was plausible, so
     * operations naming invented origins grew this instance's memory, the operation log and every
     * other client's pending map without bound, and every reload of the document re-applied them
     * and logged "the log may be corrupt" for good.
     *
     * <p>Refused rather than accepted-and-dropped, for the reason the tombstone-collection design gives about the other
     * refusal on this path: an explicit "I cannot take this" leaves the client holding its
     * operation and able to act, while silence loses it. The distinct counter is so the state is
     * visible as a number rather than inferred from memory graphs.
     *
     * <p>Checked BEFORE the merge and after the GC refusal, because the two answer different
     * questions and the GC one is about this operation while this one is about the replica's
     * condition. The cost is one uncontended lock acquisition on the submit path, which already
     * takes two.
     */
    private void refuseIfPendingBufferFull(DocumentReplica replica) {
        int cap = properties.persistence().maxPendingOperations();
        int pending = replica.pendingCount();
        if (pending < cap) {
            return;
        }
        metrics.recordPendingOverflow();
        throw new ResyncRequiredException(
            "this replica is already holding " + pending + " operations waiting for a cause, "
                + "which is the configured maximum — resynchronise instead of sending more");
    }

    /** {@link #onActiveReplica} for the merge path, which needs the return value. */
    private boolean mergeRetryingIfEvicted(UUID documentId, DocumentReplica replica, Op op,
                                           String userId, String traceparent) {
        try {
            return replica.merge(op, userId, traceparent, System.currentTimeMillis());
        } catch (DocumentReplica.StaleReplicaException stale) {
            log.debug("Replica of {} was evicted mid-merge; reloading and retrying", documentId);
            return registry.get(documentId)
                .merge(op, userId, traceparent, System.currentTimeMillis());
        }
    }

    // ------------------------------------------------------------------
    // Step 2: broadcasting — in batches (the cluster design)
    // ------------------------------------------------------------------

    /**
     * Sends out the collected operations of every document.
     *
     * <p><b>Why in batches, when the cluster design said "immediately".</b> Broadcasting one by one means one
     * frame per keystroke <b>times the number of recipients</b>. The number of frames therefore
     * grows with the square of the client count, and each carries a few dozen bytes of content in a
     * STOMP envelope of comparable size. At 50 clients the send queue of the slowest one exceeds
     * {@code send-buffer-size-bytes} and Spring tears its session down — which is exactly the
     * symptom the README recorded as unresolved ("some clients permanently behind, the server
     * holding every operation").
     *
     * <p>The cost is a delay of up to {@code broadcast-window-ms}. At 10 ms that is an order of
     * magnitude below the threshold of noticeability in typing and an order of magnitude below the
     * measured propagation latencies (22–553 ms), so the trade-off is not close.
     *
     * <p>Runs on the single-threaded flow pool, together with {@link #flushAll()} and nothing
     * else — see {@link SchedulingConfig}. That the two never overlap is a requirement, not a
     * convenience: it is the second half of the guarantee that an acknowledgement cannot
     * overtake the broadcast of its own operation.
     */
    @Scheduled(
        fixedDelayString = "${collabedit.websocket.broadcast-window-ms:"
            + CollabEditProperties.Defaults.BROADCAST_WINDOW_MS + "}",
        scheduler = SchedulingConfig.FLOW)
    public void broadcastAll() {
        for (DocumentReplica replica : registry.loaded()) {
            try {
                // A lock-free check before taking the lock — for the same reason as in flushAll,
                // only stronger: this loop runs every 10 ms, so with many open documents it, rather
                // than user traffic, would be the biggest consumer of locks in the system.
                if (!replica.hasPendingBroadcast()) {
                    continue;
                }
                broadcast(replica.documentId(), replica.drainBroadcast());
            } catch (RuntimeException e) {
                log.error("Failed to broadcast operations for document {}", replica.documentId(), e);
            }
        }
    }

    private void broadcast(UUID documentId, List<PendingOperation> batch) {
        if (batch.isEmpty()) {
            return;
        }
        // Tracing context is connected with LINKS rather than nesting — the same as for the write.
        // A batch covers operations from many users and runs on a scheduler thread, so nesting
        // would show each of them the time taken by somebody else's operations.
        try (var span = tracer.startBatch("op.broadcast.local",
            batch.stream().map(PendingOperation::traceparent).toList())) {
            span.attribute("collabedit.document", documentId.toString());
            span.attribute("collabedit.batch.size", batch.size());
            onFastPath.accept(documentId, batch.stream().map(PendingOperation::op).toList());
        }
        // HERE, and this is the point of the whole PendingOperation.enqueuedNanos field.
        //
        // op.server.latency is described as "operation intake -> broadcast to this instance's
        // clients", and it used to be stopped in submit the instant the merge returned — before
        // the broadcast window, before the flow scheduler picked the batch up, before a single
        // frame left. It therefore measured the same interval as crdt.merge.duration, under a name
        // claiming otherwise, on the ONE latency in this system that can be quoted without clock
        // caveats (DocumentMetrics explains why every other one cannot).
        //
        // Recorded per operation rather than per batch, because the batch's oldest and newest
        // members can be a whole window apart and the question is what ONE edit cost. Recorded
        // after the send rather than before, because the send is what the number is about.
        long now = System.nanoTime();
        for (PendingOperation pending : batch) {
            metrics.recordServerLatency(now - pending.enqueuedNanos());
        }
    }

    // ------------------------------------------------------------------
    // Steps 3-5: batched write, acknowledgements, publication
    // ------------------------------------------------------------------

    /**
     * Drains the write buffers of every loaded document.
     *
     * <p>The interval is shorter than the batching window from the configuration, so that an
     * operation waits for its write at most as long as {@code op-batch-window-ms} claims rather
     * than twice that.
     *
     * <p>Snapshotting used to happen here and no longer does — see {@link #snapshotSweep()}.
     * This loop is on the flow pool, where the 10 ms broadcast also lives, and copying the
     * whole block structure of a 100k-character document is not work that belongs on the
     * thread responsible for what the user sees.
     */
    @Scheduled(
        fixedDelayString = "${collabedit.persistence.op-batch-window-ms:"
            + CollabEditProperties.Defaults.OP_BATCH_WINDOW_MS + "}",
        scheduler = SchedulingConfig.FLOW)
    public void flushAll() {
        for (DocumentReplica replica : registry.loaded()) {
            try {
                // A lock-free check for whether there is anything to do. With many open documents
                // most passes of this loop find empty buffers, and taking the lock costs more than
                // reading two sizes.
                if (replica.hasPendingWork()) {
                    flush(replica);
                }
            } catch (RuntimeException e) {
                log.error("Failed to write the batch for document {}", replica.documentId(), e);
            }
        }
    }

    /**
     * Snapshots every replica that is due for one (pkt 4).
     *
     * <h2>Two thresholds, because they catch two different documents</h2>
     * The <b>operation count</b> catches the heavily edited document. The <b>quiet period</b>
     * catches the one that took a hundred changes and fell silent — which is the case where a
     * snapshot is worth most, because that document is about to be evicted from memory
     * ({@code idle-document-eviction-ms}) and replayed from the log next time it opens.
     *
     * <h2>Why this is its own job on the maintenance pool</h2>
     * It used to be two calls inside the flush path: {@code maybeSnapshot} after a successful
     * write and {@code snapshotIfQuiet} once per flush loop. Both therefore ran on the thread
     * that also broadcasts every 10 ms, and a snapshot walks the entire block structure under
     * the document lock. Moving it here costs one comparison per replica per sweep and removes
     * the largest single piece of work from the latency-visible path.
     *
     * <p>The sweep interval is deliberately much shorter than either threshold: it only decides
     * <i>whether</i> to snapshot, so its cost is two integer comparisons per loaded document,
     * and a longer interval would make the op-count threshold fire late for no saving.
     */
    @Scheduled(
        fixedDelayString = "${collabedit.persistence.snapshot-sweep-interval-ms:"
            + CollabEditProperties.Defaults.SNAPSHOT_SWEEP_INTERVAL_MS + "}",
        scheduler = SchedulingConfig.MAINTENANCE)
    public void snapshotSweep() {
        long now = System.currentTimeMillis();
        for (DocumentReplica replica : registry.loaded()) {
            try {
                if (!isSnapshotDue(replica, now)) {
                    continue;
                }
                takeSnapshot(replica);
            } catch (RuntimeException e) {
                log.error("Failed to write a snapshot for document {}", replica.documentId(), e);
            }
        }
    }

    private boolean isSnapshotDue(DocumentReplica replica, long nowMillis) {
        if (replica.operationsSinceSnapshot() == 0) {
            return false;
        }
        // Unwritten operations take priority: a snapshot over state that is not yet in the log
        // would be stored with a server_seq from before those operations, and on restore they would
        // be applied a second time. That would be harmless (idempotence), but the snapshot would
        // stop matching its own number.
        if (replica.unwrittenCount() > 0) {
            return false;
        }
        if (replica.snapshotDue(properties.persistence().snapshotEveryNOps())) {
            return true;
        }
        // lastEditMillis, not lastActivityMillis. The question here is "has this document been
        // STILL", which is about edits; eviction's question is "is anybody here", which is about
        // clients and is refreshed by a progress report every 15 seconds. While the two shared one
        // field, an open tab made this threshold unreachable and a lightly-edited document was
        // never snapshotted — so its search vector and list preview never updated.
        boolean quiet =
            nowMillis - replica.lastEditMillis() >= properties.persistence().snapshotAfterIdleMs();
        if (quiet) {
            log.debug("Document {} went quiet — snapshotting {} operations after the last one",
                replica.documentId(), replica.operationsSinceSnapshot());
        }
        return quiet;
    }

    private void flush(DocumentReplica replica) {
        // Both buffers are taken TOGETHER, under a single acquisition of the lock, and the
        // broadcast goes FIRST. The reason is in DocumentReplica.drainForFlush: a write
        // acknowledgement that outruns the broadcast is read by the client as a gap, and it asks
        // for a full resynchronisation.
        DocumentReplica.OutboundBatch outbound =
            replica.drainForFlush(properties.persistence().opBatchMaxSize());

        List<PendingOperation> batch = outbound.writes();
        if (batch.isEmpty()) {
            // Nothing was drained for writing, so drainForFlush counted no flush in flight and
            // there is nothing to close.
            broadcast(replica.documentId(), outbound.broadcast());
            return;
        }

        // From here the replica considers a flush IN FLIGHT, and every path out has to end it —
        // including the failure path. Leaving the counter raised would stop this document
        // snapshotting until it goes quiet long enough to be evicted and reloaded.
        //
        // The broadcast is INSIDE this block for exactly that reason. It used to run between
        // drainForFlush and the try, so a MessageDeliveryException from the broker left the
        // counter raised with nothing to lower it — the one leak the paragraph above warns about,
        // through the one door it did not cover.
        List<StoredOperation> appended = List.of();
        try {
            broadcast(replica.documentId(), outbound.broadcast());

            var persisting = metrics.startTimer();
            try (var span = tracer.startBatch("op.persist",
                batch.stream().map(PendingOperation::traceparent).toList())) {
                appended = operations.append(replica.documentId(), batch);
                span.attribute("collabedit.batch.size", batch.size());
                span.attribute("collabedit.batch.written", appended.size());
            }
            metrics.recordPersist(persisting);
        } catch (RuntimeException e) {
            // The batch is already OUT of the replica's buffer — drainForFlush removed it — so
            // without this it is simply gone from the source of truth, while every connected
            // client can already see it. Anti-entropy cannot repair that: the log's head is not
            // ahead of appliedServerSeq, so no gap is detected, and the next eviction or restart
            // silently rolls the text back.
            //
            // Only when nothing was written: append is transactional, so a throw means no row
            // landed, and requeueing after a partial success would write the batch twice.
            //
            // When it is the BROADCAST that threw, the drained broadcast batch is gone and this
            // instance's clients do not see those operations. That case repairs itself through the
            // mechanism already built for it: the operations are written on the retry, the
            // acknowledgement goes out, and a client acknowledged an operation it does not hold
            // sets `gapDetected` and asks for a resynchronisation (connection.ts).
            if (appended.isEmpty()) {
                returnToBuffer(replica, batch);
            }
            throw e;
        } finally {
            replica.flushCompleted(appended.isEmpty() ? 0 : appended.getLast().serverSeq());
        }

        if (appended.isEmpty()) {
            return;
        }
        onBroadcast.accept(replica.documentId(), appended);
        cluster.publish(replica.documentId(), appended);
        // No snapshot here any more: {@link #snapshotSweep()} owns that, on the maintenance
        // pool. Taking it inline meant the flow thread walked the whole block structure of the
        // document while the 10 ms broadcast waited behind it.
    }

    /**
     * Returns a batch whose write failed to the replica's buffer, so the next window retries it.
     *
     * <p><b>The case this cannot serve is now unreachable, and the paragraph that used to be here
     * described it as reachable.</b> It read: "eviction requires both buffers to be empty, which
     * they are for exactly this window, so it is reachable". That was true, and it was a defect
     * rather than a fact of life — {@code DocumentReplica.tryEvict} now also refuses while a flush
     * is in flight, which is precisely this window. A replica cannot be evicted between the drain
     * and the failure any more.
     *
     * <p>The catch below stays, and stays LOUD. It is now a claim about an invariant rather than a
     * handler for an expected case: reaching it means {@code tryEvict}'s guard has been weakened
     * or bypassed, and the consequence — operations broadcast to every client and present in no
     * log, invisible to anti-entropy because the log's head is not ahead of
     * {@code appliedServerSeq} — is exactly the kind that must not be discovered from a graph.
     */
    private void returnToBuffer(DocumentReplica replica, List<PendingOperation> batch) {
        metrics.recordWriteFailure(batch.size());
        try {
            replica.requeueUnwritten(batch);
        } catch (DocumentReplica.StaleReplicaException stale) {
            log.error("The write of {} operations for document {} failed and the replica was "
                    + "evicted before they could be put back; they are not in the log and will "
                    + "return only when the client retransmits them",
                batch.size(), replica.documentId());
        }
    }

    /**
     * State taken under one lock: the snapshot to store, and the content the document list and the
     * search index are cut from.
     *
     * <p>Declared next to its only use. It used to sit ~150 lines away under the "Reads" banner,
     * which is a section about something else entirely.
     *
     * @param text the whole content — the preview is its first 200 characters and the search
     * vector is built from all of it (CP29)
     * @param count how many operations the replica had accumulated at the moment of capture. Not
     * decoration: the counter is reduced by exactly this afterwards rather than
     * zeroed, so operations merged during the database write are not lost from the
     * next snapshot's threshold — see {@link DocumentReplica#snapshotTaken(int)}
     */
    private record Captured(DocumentSnapshot snapshot, String text, long seq, int count) {
    }

    private void takeSnapshot(DocumentReplica replica) {
        // Tombstone GC happens DURING snapshotting rather than as a separate process:
        // a snapshot rewrites the whole structure anyway, so compaction is free at this point.
        VersionVector stable = replica.stableVersion(
            System.currentTimeMillis(),
            properties.gc().unresponsiveReplicaTimeoutMs(),
            // The GC window. Configured since CP5 and read by nothing until now: the tombstone-collection design defines
            // the replicas that constrain GC as "connected now, OR disconnected for less than the
            // GC window", and only the first half was implemented.
            Duration.ofDays(properties.gc().windowDays()).toMillis());

        // The sequence number is read under THE SAME lock acquisition as the snapshot, and the
        // replica refuses if the two would not match — see DocumentReplica.withConsistentState.
        // Reading it beforehand, as this did, meant a flush completing in between produced a
        // snapshot labelled with a number lower than the state it holds. Loading a document
        // survives that (re-applying the tail is idempotent); HistoryService.textAt does not, and
        // would show a state from slightly in the future.
        var maybeCaptured = replica.withConsistentState((document, seq) -> {
            // "reclaimed", not "removed": since CP18 a block that must not be removed gives up its
            // content and stays in the structure. Both paths reclaim memory, but only one removes
            // anything — and a log saying "removed" while the block count is unchanged sends
            // whoever is diagnosing it in the wrong direction.
            int reclaimed = stable.isEmpty() ? 0 : document.gc(stable);
            if (reclaimed > 0) {
                metrics.recordGc(reclaimed);
                log.debug("GC of document {}: reclaimed {} tombstone characters",
                    replica.documentId(), reclaimed);
            } else if (document.tombstoneCount() > 0) {
                // "Why is GC stuck" — the question the tombstone-collection design promises an answer to and nothing
                // computed. A tombstone survives because SOMEBODY has not caught up, and
                // laggingReplica names which one relative to this replica's own state.
                //
                // Counted without a replicaId tag, and named in a DEBUG line instead: a metric
                // tagged by replica is a series per client, which is how a Prometheus runs out of
                // memory. The count answers "is it stuck"; the log answers "on whom".
                Integer lagging = VersionVector.laggingReplica(stable, document.version());
                if (lagging != null) {
                    metrics.recordGcBlocked();
                    log.debug("GC of document {} reclaimed nothing: replica {} is furthest behind",
                        replica.documentId(), lagging);
                }
            }
            // The whole text, not just the preview: since CP29 the same call also builds the
            // search vector, and searching the first 200 characters is what CP29 exists to stop.
            //
            // The operation count is read HERE, inside the same lock as the state, because that is
            // what makes the subtraction below correct: it is the number this snapshot covers.
            return new Captured(document.snapshot(), document.text(), seq,
                replica.operationsSinceSnapshot());
        });

        if (maybeCaptured.isEmpty()) {
            // A flush is in flight or operations are waiting: the document holds work whose
            // server_seq is not settled yet, so no label would be true. The sweep runs every
            // second and a snapshot is a cache, so skipping a round costs nothing.
            return;
        }
        Captured captured = maybeCaptured.get();
        snapshots.save(replica.documentId(), captured.seq(), captured.snapshot());

        // The counter is reset HERE, the moment the snapshot is actually stored, and not after the
        // preview below. The two are separate transactions on separate things — one is the state,
        // the other a cache for the document list — and while they were reset together, anything
        // throwing in updatePreview left operationsSinceSnapshot above the threshold. The sweep
        // then repeated the whole thing every second for the life of the process: a full structure
        // copy under the document lock, plus a to_tsvector of up to 250 000 characters. That is the
        // loop DocumentCatalog reasons about and guards for the one cause it knew.
        //
        // The GC boundary is no longer recorded here either: RgaDocument.gc records it itself, so
        // it is in the snapshot that was just written rather than in a field that a reload forgets.
        //
        // A SUBTRACTION of the count observed at capture, not a reset to zero. The capture and this
        // line are separated by a database write, and everything merged in between belongs to the
        // NEXT snapshot — zeroing threw those away, so a document under continuous editing kept
        // arriving at the 500-operation threshold late, which is the one case the operation-log decision wrote that
        // threshold for.
        replica.snapshotTaken(captured.count);

        // The preview for the document list. Materialised HERE, because this is the only moment
        // the content is already in hand — the list cannot afford to load a replica for every entry
        // (CP11).
        try {
            catalog.updatePreview(replica.documentId(), captured.text);
        } catch (RuntimeException e) {
            log.warn("The preview and search vector for document {} could not be updated; the "
                + "snapshot itself is stored", replica.documentId(), e);
        }
    }

    // ------------------------------------------------------------------
    // Receiving from the cluster + anti-entropy
    // ------------------------------------------------------------------

    private void onClusterMessage(ClusterBus.ClusterMessage message) {
        // A document this instance does not hold in memory is none of our business: there is no
        // client here, and the state will be read from the database on first open anyway. Loading
        // it just to merge an operation would be exactly the memory multiplication the cluster design says to
        // avoid.
        if (!registry.isLoaded(message.documentId())) {
            return;
        }
        DocumentReplica replica = registry.get(message.documentId());

        long expected = replica.appliedServerSeq() + 1;
        long firstIncoming = message.operations().getFirst().serverSeq();
        if (firstIncoming > expected) {
            // A gap in the numbering — the missing operations are pulled from the log, that is,
            // from the authoritative path. Redis lost a message; this is anticipated rather than
            // exceptional.
            log.info("Numbering gap in document {}: I have {}, received {} — pulling from the log",
                message.documentId(), replica.appliedServerSeq(), firstIncoming);
            repairFrom(replica, replica.appliedServerSeq());
        }

        metrics.recordRemoteMerge(applyRemote(replica, message.operations()));
        // No advanceServerSeq here any more. The label is moved INSIDE applyRemote, under the same
        // lock acquisition that applies the operations — see DocumentReplica.applyRemote for the
        // snapshot mislabelling that the gap between the two used to allow.
    }

    /**
     * Merges operations coming from the cluster or from a gap repair, queues them for broadcast and
     * moves the replica's label — all under one lock.
     *
     * <p>Operations from the cluster are already in the log, so they are merged into the in-memory
     * replica but never appended again. That is why this does not go through
     * {@link DocumentReplica#merge}, which would queue them for writing.
     *
     * @return how many of them were news to this replica
     */
    private int applyRemote(DocumentReplica replica, List<StoredOperation> incoming) {
        if (incoming.isEmpty()) {
            return 0;
        }
        long now = System.currentTimeMillis();
        try {
            return replica.applyRemote(incoming, now).size();
        } catch (DocumentReplica.StaleReplicaException stale) {
            // An eviction here loses nothing durable — these operations came from the cluster and
            // are already in the log — but the clients connected to this instance still have to see
            // them, so reload and apply to the replica that is now the registry's.
            log.debug("Replica of {} was evicted mid-merge of remote operations; reloading",
                replica.documentId());
            return registry.get(replica.documentId()).applyRemote(incoming, now).size();
        }
    }

    /**
     * Pulls everything the log holds above a cursor and merges it — <b>in bounded batches</b>.
     *
     * <h2>Why this one loops instead of taking the cap the sync path takes</h2>
     * {@code onSyncRequest} answers a client, and above its cap it can honestly say "re-open
     * through the snapshot endpoint" — the client has somewhere better to go. A gap repair has
     * nowhere else to go: it is the mechanism the cluster design relies on to make a lost Redis message a
     * non-event, and stopping it early would leave this replica permanently short of operations
     * that nothing else will deliver. Anti-entropy would notice and call it again, so a truncated
     * repair would converge eventually — but "eventually, on a 30 second timer, in slices" is a
     * worse property than the one the cluster design claims.
     *
     * <p>So it drains, and the cap becomes a BATCH size instead of a limit: memory stays bounded
     * because only one batch is decoded at a time, and correctness is unchanged because the loop
     * only stops when the log has nothing more above the cursor.
     */
    private void repairFrom(DocumentReplica replica, long fromServerSeq) {
        int batchSize = properties.persistence().maxSyncOperations();
        long cursor = fromServerSeq;
        while (true) {
            List<StoredOperation> missing =
                operations.loadAfter(replica.documentId(), cursor, batchSize);
            if (missing.isEmpty()) {
                break;
            }
            metrics.recordRemoteMerge(applyRemote(replica, missing));
            cursor = missing.getLast().serverSeq();
            if (missing.size() < batchSize) {
                break;
            }
        }
        metrics.recordGapRepair();
    }

    /**
     * A periodic sweep detecting the divergence gap detection cannot: <b>losing the last
     * operation</b>, after which no further one arrived.
     *
     * <p>Without it a replica affected by a lost message keeps stale text until the document is
     * next edited — that is, potentially forever if the document went quiet. The upper bound on how
     * long an undetected divergence can live is this sweep's interval.
     */
    @Scheduled(
        fixedDelayString = "${collabedit.cluster.anti-entropy-interval-ms:"
            + CollabEditProperties.Defaults.ANTI_ENTROPY_INTERVAL_MS + "}",
        scheduler = SchedulingConfig.MAINTENANCE)
    public void antiEntropySweep() {
        for (DocumentReplica replica : registry.loaded()) {
            try {
                long head = operations.maxServerSeq(replica.documentId());
                if (head > replica.appliedServerSeq()) {
                    log.info("Anti-entropy for document {}: the log has {}, the replica {} — repairing",
                        replica.documentId(), head, replica.appliedServerSeq());
                    repairFrom(replica, replica.appliedServerSeq());
                }
            } catch (RuntimeException e) {
                log.warn("The anti-entropy sweep for document {} failed", replica.documentId(), e);
            }
        }
    }

    @Scheduled(
        fixedDelayString = "${collabedit.cluster.idle-document-eviction-ms:"
            + CollabEditProperties.Defaults.IDLE_DOCUMENT_EVICTION_MS + "}",
        scheduler = SchedulingConfig.MAINTENANCE)
    public void evictIdleDocuments() {
        registry.evictIdle(System.currentTimeMillis());
    }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    /** The number of mark operations in the replica — for diagnostics and tests (CP13). */
    public int markCountOf(UUID documentId) {
        return registry.get(documentId).withLock(RgaDocument::markCount);
    }

    public String textOf(UUID documentId) {
        return registry.get(documentId).withLock(RgaDocument::text);
    }

    /**
     * The operations a client is missing — the answer to a resynchronisation request (CP6).
     *
     * <p>The limit is required rather than optional, and the caller passes {@code cap + 1} on
     * purpose: reading one more than it is willing to send is how it tells "exactly the cap" from
     * "more than the cap" without a second query, and the difference decides between an answer and
     * a {@code SNAPSHOT_REQUIRED} refusal. See {@code DocumentStompController.onSyncRequest}.
     */
    public List<StoredOperation> operationsSince(UUID documentId, long sinceServerSeq, int limit) {
        return operations.loadAfter(documentId, sinceServerSeq, limit);
    }

    public long headServerSeq(UUID documentId) {
        return operations.maxServerSeq(documentId);
    }
}
