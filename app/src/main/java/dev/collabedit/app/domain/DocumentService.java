package dev.collabedit.app.domain;

import dev.collabedit.app.cluster.ClusterBus;
import dev.collabedit.app.config.CollabEditProperties;
import dev.collabedit.app.config.SchedulingConfig;
import dev.collabedit.app.persist.DocumentCatalog;
import dev.collabedit.app.persist.OperationStore;
import dev.collabedit.app.persist.PendingOperation;
import dev.collabedit.app.persist.ReplicaProgressStore;
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

@Service
public class DocumentService {
    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    public static final int MAX_INSERT_CHARACTERS = 8192;

    public static List<String> splitForInsert(String text) {
        List<String> pieces = new ArrayList<>();
        int from = 0;
        while (from < text.length()) {
            int to = Math.min(text.length(), from + MAX_INSERT_CHARACTERS);
            if (to < text.length() && Character.isHighSurrogate(text.charAt(to - 1))) {
                to--;
            }
            pieces.add(text.substring(from, to));
            from = to;
        }
        return pieces;
    }

    private final DocumentRegistry registry;
    private final OperationStore operations;
    private final SnapshotStore snapshots;
    private final ReplicaProgressStore progress;
    private final DocumentCatalog catalog;
    private final ClusterBus cluster;
    private final CollabEditProperties properties;
    private final DocumentMetrics metrics;
    private final EditTracer tracer;

    private volatile BiConsumer<UUID, List<StoredOperation>> onBroadcast = (id, ops) -> { };
    private volatile BiConsumer<UUID, List<Op>> onFastPath = (id, ops) -> { };

    public DocumentService(DocumentRegistry registry, OperationStore operations,
                           SnapshotStore snapshots, ReplicaProgressStore progress,
                           DocumentCatalog catalog, ClusterBus cluster,
                           CollabEditProperties properties, DocumentMetrics metrics,
                           EditTracer tracer) {
        this.registry = registry;
        this.operations = operations;
        this.snapshots = snapshots;
        this.progress = progress;
        this.catalog = catalog;
        this.cluster = cluster;
        this.properties = properties;
        this.metrics = metrics;
        this.tracer = tracer;
    }

    @PostConstruct
    void subscribeToCluster() {
        cluster.onMessage(this::onClusterMessage);
        cluster.interestedIn(registry::isLoaded);
    }

    public void onFastPath(BiConsumer<UUID, List<Op>> handler) {
        this.onFastPath = handler;
    }

    public void onAcknowledged(BiConsumer<UUID, List<StoredOperation>> handler) {
        this.onBroadcast = handler;
    }

    public List<Op> generateAndSubmit(UUID documentId, Function<RgaDocument, List<Op>> generator,
                                      String userId) {
        long now = System.currentTimeMillis();
        List<List<Op>> result = new ArrayList<>(1);
        result.add(null);
        onActiveReplica(documentId,
            replica -> result.set(0, replica.generateAndEnqueue(generator, userId, now)));
        return result.getFirst();
    }

    private void onActiveReplica(UUID documentId, Consumer<DocumentReplica> action) {
        try {
            action.accept(registry.get(documentId));
        } catch (DocumentReplica.StaleReplicaException stale) {
            log.debug("Replica of {} was evicted mid-operation; reloading and retrying", documentId);
            action.accept(registry.get(documentId));
        }
    }

    public static class ResyncRequiredException extends RuntimeException {
        public ResyncRequiredException(String message) {
            super(message);
        }
    }

    public static class ReplicaIdConflictException extends RuntimeException {
        public ReplicaIdConflictException(String message) {
            super(message);
        }
    }

    public void submit(UUID documentId, Op op, String userId) {
        DocumentReplica replica = registry.get(documentId);

        if (replica.isPermanentlyUnanchored(op)) {
            metrics.recordStaleRejection();
            throw new ResyncRequiredException(
                "operation " + op.id() + " points at a character removed by garbage collection");
        }

        refuseIfPendingBufferFull(replica);

        boolean isNews;
        String traceparent = tracer.currentTraceparent();
        var merging = metrics.startTimer();
        try (var span = tracer.startStage("crdt.merge")) {
            isNews = mergeRetryingIfEvicted(documentId, replica, op, userId, traceparent);
            span.attribute("collabedit.op.news", String.valueOf(isNews));
        }
        metrics.recordMerge(merging);
    }

    private void refuseIfPendingBufferFull(DocumentReplica replica) {
        int cap = properties.persistence().maxPendingOperations();
        int pending = replica.pendingCount();
        if (pending < cap) {
            return;
        }
        metrics.recordPendingOverflow();
        throw new ResyncRequiredException(
            "this replica is already holding " + pending + " operations waiting for a cause, "
                + "which is the configured maximum - resynchronise instead of sending more");
    }

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

    @Scheduled(
        fixedDelayString = "${collabedit.websocket.broadcast-window-ms:"
            + CollabEditProperties.Defaults.BROADCAST_WINDOW_MS + "}",
        scheduler = SchedulingConfig.FLOW)
    public void broadcastAll() {
        for (DocumentReplica replica : registry.loaded()) {
            List<PendingOperation> batch = List.of();
            try {
                if (!replica.hasPendingBroadcast()) {
                    continue;
                }
                batch = replica.drainBroadcast();
                broadcast(replica.documentId(), batch);
            } catch (RuntimeException e) {
                replica.requeueBroadcast(batch);
                log.error("Failed to broadcast operations for document {}; the batch was returned "
                    + "to the buffer and will go out on the next pass", replica.documentId(), e);
            }
        }
    }

    private void broadcast(UUID documentId, List<PendingOperation> batch) {
        if (batch.isEmpty()) {
            return;
        }
        try (var span = tracer.startBatch("op.broadcast.local",
            batch.stream().map(PendingOperation::traceparent).toList())) {
            span.attribute("collabedit.document", documentId.toString());
            span.attribute("collabedit.batch.size", batch.size());
            onFastPath.accept(documentId, batch.stream().map(PendingOperation::op).toList());
        }
        long now = System.nanoTime();
        for (PendingOperation pending : batch) {
            metrics.recordServerLatency(now - pending.enqueuedNanos());
        }
    }

    @Scheduled(
        fixedDelayString = "${collabedit.persistence.op-batch-window-ms:"
            + CollabEditProperties.Defaults.OP_BATCH_WINDOW_MS + "}",
        scheduler = SchedulingConfig.FLOW)
    public void flushAll() {
        for (DocumentReplica replica : registry.loaded()) {
            try {
                if (replica.hasPendingWork()) {
                    flush(replica);
                }
            } catch (RuntimeException e) {
                log.error("Failed to write the batch for document {}", replica.documentId(), e);
            }
        }
    }

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
        if (replica.pendingCount() > 0) {
            return false;
        }
        if (replica.unwrittenCount() > 0) {
            return false;
        }
        if (replica.snapshotDue(properties.persistence().snapshotEveryNOps())) {
            return true;
        }
        boolean quiet =
            nowMillis - replica.lastEditMillis() >= properties.persistence().snapshotAfterIdleMs();
        if (quiet) {
            log.debug("Document {} went quiet - snapshotting {} operations after the last one",
                replica.documentId(), replica.operationsSinceSnapshot());
        }
        return quiet;
    }

    private void flush(DocumentReplica replica) {
        DocumentReplica.OutboundBatch outbound =
            replica.drainForFlush(properties.persistence().opBatchMaxSize());

        List<PendingOperation> batch = outbound.writes();
        if (batch.isEmpty()) {
            try {
                broadcast(replica.documentId(), outbound.broadcast());
            } catch (RuntimeException e) {
                replica.requeueBroadcast(outbound.broadcast());
                throw e;
            }
            return;
        }

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
            replica.requeueBroadcast(outbound.broadcast());

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
    }

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

    private record Captured(DocumentSnapshot snapshot, String text, long seq, int count) {
    }

    private void takeSnapshot(DocumentReplica replica) {
        long gcWindowMillis = Duration.ofDays(properties.gc().windowDays()).toMillis();

        List<VersionVector> live = progress.liveFor(replica.documentId(),
            properties.gc().unresponsiveReplicaTimeoutMs(), gcWindowMillis);

        VersionVector stable = replica.stableVersion(live);

        var maybeCaptured = replica.withConsistentState((document, seq) -> {
            int reclaimed = stable.isEmpty() ? 0 : document.gc(stable);
            if (reclaimed > 0) {
                metrics.recordGc(reclaimed);
                log.debug("GC of document {}: reclaimed {} tombstone characters",
                    replica.documentId(), reclaimed);
            } else if (document.tombstoneCount() > 0) {
                Integer lagging = VersionVector.laggingReplica(stable, document.version());
                if (lagging != null) {
                    metrics.recordGcBlocked();
                    log.debug("GC of document {} reclaimed nothing: replica {} is furthest behind",
                        replica.documentId(), lagging);
                }
            }
            return new Captured(document.snapshot(), document.text(), seq,
                replica.operationsSinceSnapshot());
        });

        if (maybeCaptured.isEmpty()) {
            return;
        }
        Captured captured = maybeCaptured.get();
        snapshots.save(replica.documentId(), captured.seq(), captured.snapshot());

        replica.snapshotTaken(captured.count);

        try {
            catalog.updatePreview(replica.documentId(), captured.text);
        } catch (RuntimeException e) {
            log.warn("The preview and search vector for document {} could not be updated; the "
                + "snapshot itself is stored", replica.documentId(), e);
        }
    }

    public void recordProgress(UUID documentId, int replicaId, VersionVector version) {
        registry.get(documentId).reportProgress(System.currentTimeMillis());
        try {
            progress.record(documentId, replicaId, version);
        } catch (RuntimeException e) {
            log.warn("Progress for replica {} of document {} was not recorded; garbage collection "
                + "will use the previous vector", replicaId, documentId, e);
        }
    }

    public void markReplicaDeparted(UUID documentId, int replicaId) {
        progress.markDeparted(documentId, replicaId);
    }

    private void onClusterMessage(ClusterBus.ClusterMessage message) {
        if (!registry.isLoaded(message.documentId())) {
            return;
        }
        DocumentReplica replica = registry.get(message.documentId());

        long expected = replica.appliedServerSeq() + 1;
        long firstIncoming = message.operations().getFirst().serverSeq();
        if (firstIncoming > expected) {
            log.info("Numbering gap in document {}: I have {}, received {} - pulling from the log",
                message.documentId(), replica.appliedServerSeq(), firstIncoming);
            repairFrom(replica, replica.appliedServerSeq());
        }

        metrics.recordRemoteMerge(applyRemote(replica, message.operations()));
    }

    private int applyRemote(DocumentReplica replica, List<StoredOperation> incoming) {
        if (incoming.isEmpty()) {
            return 0;
        }
        long now = System.currentTimeMillis();
        try {
            return replica.applyRemote(incoming, now).size();
        } catch (DocumentReplica.StaleReplicaException stale) {
            log.debug("Replica of {} was evicted mid-merge of remote operations; reloading",
                replica.documentId());
            return registry.get(replica.documentId()).applyRemote(incoming, now).size();
        }
    }

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

    @Scheduled(
        fixedDelayString = "${collabedit.cluster.anti-entropy-interval-ms:"
            + CollabEditProperties.Defaults.ANTI_ENTROPY_INTERVAL_MS + "}",
        scheduler = SchedulingConfig.MAINTENANCE)
    public void antiEntropySweep() {
        for (DocumentReplica replica : registry.loaded()) {
            try {
                long head = operations.maxServerSeq(replica.documentId());
                if (head > replica.appliedServerSeq()) {
                    log.info("Anti-entropy for document {}: the log has {}, the replica {} - repairing",
                        replica.documentId(), head, replica.appliedServerSeq());
                    repairFrom(replica, replica.appliedServerSeq());
                }
            } catch (RuntimeException e) {
                log.warn("The anti-entropy sweep for document {} failed", replica.documentId(), e);
            }
        }
    }

    @Scheduled(
        fixedDelayString = "${collabedit.cluster.idle-document-sweep-ms:"
            + CollabEditProperties.Defaults.IDLE_DOCUMENT_SWEEP_MS + "}",
        scheduler = SchedulingConfig.MAINTENANCE)
    public void evictIdleDocuments() {
        registry.evictIdle(System.currentTimeMillis());

        try {
            int removed = progress.pruneExpired(
                Duration.ofDays(properties.gc().windowDays()).toMillis());
            if (removed > 0) {
                log.debug("Pruned {} replica progress rows past the GC window", removed);
            }
        } catch (RuntimeException e) {
            log.warn("Expired replica progress could not be pruned", e);
        }
    }

    public int markCountOf(UUID documentId) {
        return registry.get(documentId).withLock(RgaDocument::markCount);
    }

    public String textOf(UUID documentId) {
        return registry.get(documentId).withLock(RgaDocument::text);
    }

    public List<StoredOperation> operationsSince(UUID documentId, long sinceServerSeq, int limit) {
        return operations.loadAfter(documentId, sinceServerSeq, limit);
    }

    public long headServerSeq(UUID documentId) {
        return operations.maxServerSeq(documentId);
    }
}
