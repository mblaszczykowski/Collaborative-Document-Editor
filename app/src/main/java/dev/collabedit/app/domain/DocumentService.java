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
import dev.collabedit.crdt.Op;
import dev.collabedit.crdt.RgaDocument;
import dev.collabedit.crdt.VersionVector;
import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * The operation write path — an implementation of the step order from ADR-0004.
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
     * <p>Used when restoring a version from history (ADR-0003 point 6): the operations are created
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
    private void onActiveReplica(UUID documentId, java.util.function.Consumer<DocumentReplica> action) {
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

    public void submit(UUID documentId, Op op, String userId) {
        DocumentReplica replica = registry.get(documentId);

        // ADR-0005: we refuse EXPLICITLY, rather than appending the text in a random place
        // (silent content corruption) or ignoring it (silently losing work). Both alternatives are
        // worse than an honest "I cannot merge this".
        if (replica.isPermanentlyUnanchored(op)) {
            metrics.recordStaleRejection();
            throw new ResyncRequiredException(
                "operation " + op.id() + " points at a character removed by garbage collection");
        }

        // Two separate measurements, because they answer two different questions: how long the
        // algorithm itself takes (which scales with document size) and how long the whole
        // server-side handling takes (which includes the broadcast). One combined timer would not
        // let them be separated, and when diagnosing slow propagation that is the first question.
        var wholeCall = metrics.startTimer();
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

        if (!isNews) {
            // A duplicate — there is nothing to write or broadcast. It happens on retransmission
            // after a reconnect, so it is a normal situation rather than an error.
            return;
        }
        // The operation is already in the broadcast buffer (merge put it there) and will go out in
        // the next window — see broadcastAll. There is nothing to do here.
        metrics.recordServerLatency(wholeCall);
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
    // Step 2: broadcasting — in batches (ADR-0002, ADR-0004)
    // ------------------------------------------------------------------

    /**
     * Sends out the collected operations of every document.
     *
     * <p><b>Why in batches, when ADR-0004 said "immediately".</b> Broadcasting one by one means one
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
        fixedDelayString = "${collabedit.websocket.broadcast-window-ms:10}",
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
        fixedDelayString = "${collabedit.persistence.op-batch-window-ms:50}",
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
     * Snapshots every replica that is due for one (ADR-0003 pkt 4).
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
        fixedDelayString = "${collabedit.persistence.snapshot-sweep-interval-ms:1000}",
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
        boolean quiet =
            nowMillis - replica.lastActivityMillis() >= properties.persistence().snapshotAfterIdleMs();
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
        broadcast(replica.documentId(), outbound.broadcast());

        List<PendingOperation> batch = outbound.writes();
        if (batch.isEmpty()) {
            // Nothing was drained for writing, so drainForFlush counted no flush in flight and
            // there is nothing to close.
            return;
        }

        // From here the replica considers a flush IN FLIGHT, and every path out has to end it —
        // including the failure path. Leaving the counter raised would stop this document
        // snapshotting for the rest of the process's life.
        List<StoredOperation> appended = List.of();
        try {
            var persisting = metrics.startTimer();
            try (var span = tracer.startBatch("op.persist",
                batch.stream().map(PendingOperation::traceparent).toList())) {
                appended = operations.append(replica.documentId(), batch);
                span.attribute("collabedit.batch.size", batch.size());
                span.attribute("collabedit.batch.written", appended.size());
            }
            metrics.recordPersist(persisting);
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

    private void takeSnapshot(DocumentReplica replica) {
        // Tombstone GC happens DURING snapshotting rather than as a separate process (ADR-0005):
        // a snapshot rewrites the whole structure anyway, so compaction is free at this point.
        VersionVector stable = replica.stableVersion(
            System.currentTimeMillis(),
            properties.gc().unresponsiveReplicaTimeoutMs(),
            // The GC window. Configured since CP5 and read by nothing until now: ADR-0005 defines
            // the replicas that constrain GC as "connected now, OR disconnected for less than the
            // GC window", and only the first half was implemented.
            java.time.Duration.ofDays(properties.gc().windowDays()).toMillis());

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
            }
            // The whole text, not just the preview: since CP29 the same call also builds the
            // search vector, and searching the first 200 characters is what CP29 exists to stop.
            return new Captured(document.snapshot(), document.text(), seq);
        });

        if (maybeCaptured.isEmpty()) {
            // A flush is in flight or operations are waiting: the document holds work whose
            // server_seq is not settled yet, so no label would be true. The sweep runs every
            // second and a snapshot is a cache, so skipping a round costs nothing.
            return;
        }
        Captured captured = maybeCaptured.get();
        snapshots.save(replica.documentId(), captured.seq(), captured.snapshot());
        // The preview for the document list. Materialised HERE, because this is the only moment
        // the content is already in hand — the list cannot afford to load a replica for every entry
        // (CP11).
        catalog.updatePreview(replica.documentId(), captured.text());
        replica.snapshotTaken();
        if (!stable.isEmpty()) {
            replica.recordGc(stable);
        }
    }

    // ------------------------------------------------------------------
    // Receiving from the cluster + anti-entropy (ADR-0004)
    // ------------------------------------------------------------------

    private void onClusterMessage(ClusterBus.ClusterMessage message) {
        // A document this instance does not hold in memory is none of our business: there is no
        // client here, and the state will be read from the database on first open anyway. Loading
        // it just to merge an operation would be exactly the memory multiplication ADR-0004 says to
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

        applyRemote(replica, message.operations());
        replica.advanceServerSeq(message.operations().getLast().serverSeq());
    }

    /**
     * Merges operations coming from the cluster or from a gap repair and queues them for
     * broadcast.
     *
     * @return how many of them were news to this replica
     */
    private int applyRemote(DocumentReplica replica, List<StoredOperation> incoming) {
        long now = System.currentTimeMillis();
        int applied = 0;
        for (StoredOperation stored : incoming) {
            // Operations from the cluster are already in the log — we merge them into the
            // in-memory replica but do NOT append them again. Hence going directly into
            // RgaDocument rather than DocumentReplica.merge, which would queue them for writing.
            RgaDocument.ApplyResult result =
                replica.withLock(document -> document.apply(stored.op()));
            if (result.isNews()) {
                // An eviction here loses nothing durable — these operations came from the
                // cluster and are already in the log — but the clients connected to this
                // instance still have to see them, so reload and enqueue.
                onActiveReplica(replica.documentId(),
                    active -> active.enqueueForBroadcast(stored.op(), now));
                applied++;
            }
        }
        return applied;
    }

    private void repairFrom(DocumentReplica replica, long fromServerSeq) {
        List<StoredOperation> missing = operations.loadAfter(replica.documentId(), fromServerSeq);
        metrics.recordGapRepair(applyRemote(replica, missing));
        if (!missing.isEmpty()) {
            replica.advanceServerSeq(missing.getLast().serverSeq());
        }
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
        fixedDelayString = "${collabedit.cluster.anti-entropy-interval-ms:30000}",
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
        fixedDelayString = "${collabedit.cluster.idle-document-eviction-ms:300000}",
        scheduler = SchedulingConfig.MAINTENANCE)
    public void evictIdleDocuments() {
        registry.evictIdle(System.currentTimeMillis());
    }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    /**
     * State taken under one lock: the snapshot to store, and the content the document list and the
     * search index are cut from.
     *
     * @param text the whole content — the preview is its first 200 characters and the search
     *             vector is built from all of it (CP29)
     */
    private record Captured(dev.collabedit.crdt.DocumentSnapshot snapshot, String text,
                            long seq) {
    }

    /** The number of mark operations in the replica — for diagnostics and tests (CP13). */
    public int markCountOf(UUID documentId) {
        return registry.get(documentId).withLock(RgaDocument::markCount);
    }

    public String textOf(UUID documentId) {
        return registry.get(documentId).withLock(RgaDocument::text);
    }

    /** The operations a client is missing — the answer to a resynchronisation request (CP6). */
    public List<StoredOperation> operationsSince(UUID documentId, long sinceServerSeq) {
        return operations.loadAfter(documentId, sinceServerSeq);
    }

    public long headServerSeq(UUID documentId) {
        return operations.maxServerSeq(documentId);
    }
}
