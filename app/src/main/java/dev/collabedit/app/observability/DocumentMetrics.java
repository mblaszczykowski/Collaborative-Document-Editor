package dev.collabedit.app.observability;

import dev.collabedit.app.domain.DocumentRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

@Component
public class DocumentMetrics {
    private final MeterRegistry registry;

    private final Timer mergeDuration;
    private final Timer serverLatency;
    private final Timer persistDuration;

    private final Counter merges;
    private final Counter gapRepairs;
    private final Counter gcReclaimedCharacters;
    private final Counter rejectedStaleOperations;
    private final Counter rejectedForPendingOverflow;
    private final Counter rejectedReplicaIdConflicts;
    private final Counter failedWrites;
    private final Counter gcBlocked;
    private final Counter clusterMessagesDropped;

    private final Map<String, Counter> frameRefusals = new ConcurrentHashMap<>();

    private final Counter sessionsOpened;

    private final AtomicInteger activeSessions = new AtomicInteger();

    public DocumentMetrics(MeterRegistry registry, DocumentRegistry documents) {
        this.registry = registry;
        this.mergeDuration = Timer.builder("crdt.merge.duration")
            .description("Time to merge one operation into the in-memory replica")
            .publishPercentiles(0.5, 0.95, 0.99)
            .publishPercentileHistogram()
            .register(registry);

        this.serverLatency = Timer.builder("op.server.latency")
            .description("Operation intake -> broadcast to this instance's clients")
            .publishPercentiles(0.5, 0.95, 0.99)
            .publishPercentileHistogram()
            .register(registry);

        this.persistDuration = Timer.builder("op.persist.duration")
            .description("Time to write a batch of operations to the log")
            .publishPercentiles(0.5, 0.95, 0.99)
                .publishPercentileHistogram()
            .register(registry);

        this.merges = Counter.builder("crdt.merges.total")
            .description("Operations merged into this instance's replicas")
            .register(registry);

        this.gapRepairs = Counter.builder("cluster.gap.repairs.total")
            .description("Repairs of gaps in the numbering - that is, messages lost by Redis")
            .register(registry);

        this.gcReclaimedCharacters = Counter.builder("crdt.gc.reclaimed.characters.total")
            .description("Characters whose memory GC reclaimed - by removing a block or by "
                + "dropping its content")
            .register(registry);

        this.rejectedStaleOperations = Counter.builder("op.rejected.stale.total")
            .description("Operations rejected because GC removed their anchor point")
            .register(registry);

        this.rejectedForPendingOverflow = Counter.builder("op.rejected.pending.total")
            .description("Operations refused because the replica's buffer of operations waiting "
                + "for a cause is full (collabedit.persistence.max-pending-operations)")
            .register(registry);

        this.rejectedReplicaIdConflicts = Counter.builder("op.rejected.replica.conflict.total")
            .description("Operations refused because their replica id belongs to a different user")
            .register(registry);

        this.clusterMessagesDropped = Counter.builder("cluster.messages.dropped.total")
            .description("Cluster messages discarded because the listener pool was saturated")
            .register(registry);

        this.gcBlocked = Counter.builder("crdt.gc.blocked.total")
            .description("Snapshots where GC reclaimed nothing although tombstones were present - "
                + "a replica has not caught up")
            .register(registry);

        this.failedWrites = Counter.builder("op.write.failures.total")
            .description("Operations whose batch write failed and were returned to the buffer")
            .register(registry);

        this.sessionsOpened = Counter.builder("ws.sessions.opened.total")
            .description("WebSocket sessions established - one per STOMP CONNECT")
            .register(registry);

        Gauge.builder("ws.sessions.active", activeSessions, AtomicInteger::get)
            .description("Active WebSocket sessions on this instance")
            .register(registry);

        Gauge.builder("doc.loaded", documents, DocumentRegistry::loadedCount)
            .description("Documents held in this instance's memory")
            .register(registry);

        Gauge.builder("doc.tombstone.ratio", documents,
                loaded -> replicaCounts(loaded).tombstoneRatio())
            .description("Tombstones / all characters, across in-memory documents")
            .register(registry);

        Gauge.builder("crdt.pending.operations", documents,
                loaded -> replicaCounts(loaded).pending)
            .description("Operations waiting for their cause - a sustained value > 0 means corruption")
            .register(registry);
    }

    private record ReplicaCounts(long visible, long tombstones, long pending) {
        double tombstoneRatio() {
            long total = visible + tombstones;
            return total == 0 ? 0.0 : (double) tombstones / total;
        }
    }

    private static final long COUNTS_CACHE_MILLIS = 1_000;

    private volatile ReplicaCounts cachedCounts = new ReplicaCounts(0, 0, 0);
    private volatile long cachedCountsAtMillis;

    private ReplicaCounts replicaCounts(DocumentRegistry documents) {
        long now = System.currentTimeMillis();
        if (now - cachedCountsAtMillis < COUNTS_CACHE_MILLIS) {
            return cachedCounts;
        }
        long visible = 0;
        long tombstones = 0;
        long pending = 0;
        for (var replica : documents.loaded()) {
            long[] counts = replica.withLock(document -> new long[] {
                document.visibleLength(), document.tombstoneCount(), document.pendingCount()});
            visible += counts[0];
            tombstones += counts[1];
            pending += counts[2];
        }
        cachedCounts = new ReplicaCounts(visible, tombstones, pending);
        cachedCountsAtMillis = now;
        return cachedCounts;
    }

    public Timer.Sample startTimer() {
        return Timer.start();
    }

    public void recordMerge(Timer.Sample sample) {
        sample.stop(mergeDuration);
        merges.increment();
    }

    public void recordServerLatency(long elapsedNanos) {
        serverLatency.record(elapsedNanos, TimeUnit.NANOSECONDS);
    }

    public void recordPersist(Timer.Sample sample) {
        sample.stop(persistDuration);
    }

    public void recordRemoteMerge(int operationsMerged) {
        if (operationsMerged > 0) {
            merges.increment(operationsMerged);
        }
    }

    public void recordGapRepair() {
        gapRepairs.increment();
    }

    public void recordGc(int charactersReclaimed) {
        gcReclaimedCharacters.increment(charactersReclaimed);
    }

    public void recordGcBlocked() {
        gcBlocked.increment();
    }

    public void recordClusterMessageDropped() {
        clusterMessagesDropped.increment();
    }

    public void recordStaleRejection() {
        rejectedStaleOperations.increment();
    }

    public void recordPendingOverflow() {
        rejectedForPendingOverflow.increment();
    }

    public void recordReplicaIdConflict() {
        rejectedReplicaIdConflicts.increment();
    }

    public void recordWriteFailure(int operationsReturned) {
        failedWrites.increment(operationsReturned);
    }

    public void sessionOpened() {
        activeSessions.incrementAndGet();
        sessionsOpened.increment();
    }

    public void sessionClosed() {
        activeSessions.decrementAndGet();
    }

    public void recordSessionClose(String reason) {
        Counter.builder("ws.sessions.closed.total")
            .description("WebSocket sessions ended, by close reason")
            .tag("reason", reason)
            .register(registry)
            .increment();
    }

    public void recordFrameRefused(String reason) {
        frameRefusals.computeIfAbsent(reason, key -> Counter.builder("ws.frames.refused.total")
            .description("STOMP frames refused by the access interceptor, by reason")
            .tag("reason", key)
            .register(registry)).increment();
    }

    private final Map<String, Counter> outboundDrops = new ConcurrentHashMap<>();

    public void recordOutboundDrop(String reason) {
        outboundDrops.computeIfAbsent(reason, key -> Counter.builder("ws.frames.dropped.total")
            .description("Frames the server withheld on the outbound channel, by reason")
            .tag("reason", key)
            .register(registry)).increment();
    }
}
