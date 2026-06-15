package dev.collabedit.app.observability;

import dev.collabedit.app.domain.DocumentRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * The system's metrics (CP7).
 *
 * <p>The choice of metrics follows from the questions one has to be able to answer about <b>this
 * particular</b> system, not from a list of "what people usually measure":
 *
 * <table>
 *   <caption>Metric -> the question it answers</caption>
 *   <tr><th>Metric</th><th>Question</th></tr>
 *   <tr><td>{@code crdt_merge_duration}</td>
 *       <td>Is merging becoming a bottleneck as the document grows? This is where the difference
 *           between O(n) and O(log n) becomes visible.</td></tr>
 *   <tr><td>{@code op_server_latency}</td>
 *       <td>How long an operation spends on the server: intake -> broadcast. The only latency
 *           measured on a single clock, hence the only one whose number can be quoted without
 *           caveats.</td></tr>
 *   <tr><td>{@code doc_tombstone_ratio}</td>
 *       <td>Whether GC works at all. A monotonically rising value means causal stability is
 *           stalled — for instance by one hung client (ADR-0005).</td></tr>
 *   <tr><td>{@code cluster_gap_repairs}</td>
 *       <td>How often Redis loses messages. A value of zero for a long time means the repair
 *           mechanism from ADR-0004 is not exercised in practice — that is, there is no telling
 *           whether it works.</td></tr>
 *   <tr><td>{@code ws_sessions_closed_total{reason}}</td>
 *       <td>Why sessions end. A {@code slow_client_buffer_overflow} value rising with the number of
 *           clients is a direct answer to the open question in the README ("Degradation point"):
 *           the client cannot keep up with delivery, the server tears its session down, and it
 *           comes back by reconnecting — so from the outside nothing is visible but the
 *           divergence.</td></tr>
 *   <tr><td>{@code crdt_pending_operations}</td>
 *       <td>How many operations are waiting for their cause. A value that stays above zero means a
 *           permanently missing operation, that is, corruption — not a transient effect of network
 *           ordering.</td></tr>
 * </table>
 *
 * <p><b>What is deliberately NOT here:</b> an "end-to-end propagation latency" metric. It would
 * require comparing times from two different clocks (the sender's browser and the receiver's), and
 * the difference between their settings can exceed the quantity being measured. That number is
 * measured only in the benchmark harness (CP8), where both sides share one monotonic clock — and
 * it is quoted only there.
 */
@Component
public class DocumentMetrics {

    /** Kept because tagged counters are created on demand — see {@link #recordSessionClose}. */
    private final MeterRegistry registry;

    private final Timer mergeDuration;
    private final Timer serverLatency;
    private final Timer persistDuration;
    private final Counter concurrentMerges;
    private final Counter gapRepairs;
    private final Counter gcReclaimedCharacters;
    private final Counter rejectedStaleOperations;
    private final Counter reconnects;

    private final AtomicInteger activeSessions = new AtomicInteger();

    public DocumentMetrics(MeterRegistry registry, DocumentRegistry documents) {
        this.registry = registry;
        this.mergeDuration = Timer.builder("crdt.merge.duration")
            .description("Time to merge one operation into the in-memory replica")
            // Percentiles computed in the application rather than from a histogram in Prometheus.
            // The reason: we want to be able to quote p99 for a single instance without summing
            // buckets, while still keeping the histogram for aggregation across instances.
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
            .register(registry);

        this.concurrentMerges = Counter.builder("crdt.merges.total")
            .description("Operations merged into this instance's replicas")
            .register(registry);

        this.gapRepairs = Counter.builder("cluster.gap.repairs.total")
            .description("Repairs of gaps in the numbering — that is, messages lost by Redis (ADR-0004)")
            .register(registry);

        // Renamed in CP18 from ".removed." to ".reclaimed.", because the counter stopped meaning
        // what it used to. GC does not only REMOVE blocks — since CP18 a block that must not be
        // removed (an origin, a mark anchor) DROPS ITS CONTENT and stays in the structure. Memory
        // comes back in both cases, but "removed" now describes only one of them.
        //
        // Renaming a metric breaks the continuity of the historical series and requires a fix in
        // the dashboard (ops/grafana/dashboards/collabedit.json). Deliberately: a counter whose
        // name says something other than its value is worse than a gap in a graph.
        this.gcReclaimedCharacters = Counter.builder("crdt.gc.reclaimed.characters.total")
            .description("Characters whose memory GC reclaimed — by removing a block or by "
                + "dropping its content (ADR-0005, CP18)")
            .register(registry);

        this.rejectedStaleOperations = Counter.builder("op.rejected.stale.total")
            .description("Operations rejected because GC removed their anchor point (ADR-0005)")
            .register(registry);

        this.reconnects = Counter.builder("ws.sessions.opened.total")
            .description("WebSocket sessions established")
            .register(registry);

        Gauge.builder("ws.sessions.active", activeSessions, AtomicInteger::get)
            .description("Active WebSocket sessions on this instance")
            .register(registry);

        Gauge.builder("doc.loaded.count", documents, registry2 -> registry2.loaded().size())
            .description("Documents held in this instance's memory")
            .register(registry);

        // The tombstone share computed across all in-memory documents. A high value sustained
        // over time signals that GC is not working — not that users delete a lot.
        Gauge.builder("doc.tombstone.ratio", documents, DocumentMetrics::tombstoneRatio)
            .description("Tombstones / all characters, across in-memory documents")
            .register(registry);

        Gauge.builder("crdt.pending.operations", documents, DocumentMetrics::pendingOperations)
            .description("Operations waiting for their cause — a sustained value > 0 means corruption")
            .register(registry);
    }

    private static double tombstoneRatio(DocumentRegistry registry) {
        long visible = 0;
        long tombstones = 0;
        for (var replica : registry.loaded()) {
            visible += replica.withLock(document -> document.visibleLength());
            tombstones += replica.withLock(document -> document.tombstoneCount());
        }
        long total = visible + tombstones;
        return total == 0 ? 0.0 : (double) tombstones / total;
    }

    private static double pendingOperations(DocumentRegistry registry) {
        long pending = 0;
        for (var replica : registry.loaded()) {
            pending += replica.withLock(document -> document.pendingCount());
        }
        return pending;
    }

    public Timer.Sample startTimer() {
        return Timer.start();
    }

    public void recordMerge(Timer.Sample sample) {
        sample.stop(mergeDuration);
        concurrentMerges.increment();
    }

    public void recordServerLatency(Timer.Sample sample) {
        sample.stop(serverLatency);
    }

    public void recordPersist(Timer.Sample sample) {
        sample.stop(persistDuration);
    }

    public void recordGapRepair(int operationsRecovered) {
        gapRepairs.increment();
        if (operationsRecovered > 0) {
            concurrentMerges.increment(operationsRecovered);
        }
    }

    public void recordGc(int charactersReclaimed) {
        gcReclaimedCharacters.increment(charactersReclaimed);
    }

    public void recordStaleRejection() {
        rejectedStaleOperations.increment();
    }

    public void sessionOpened() {
        activeSessions.incrementAndGet();
        reconnects.increment();
    }

    public void sessionClosed() {
        activeSessions.decrementAndGet();
    }

    /**
     * Records the REASON a session closed. It does not touch the active-session gauge — that comes
     * down in {@link #sessionClosed()}, called from the presence path. The two events describe the
     * same disconnect, and decrementing twice would take the gauge below zero.
     *
     * <p>The counter is created on demand, because the {@code reason} tag is part of its identity.
     * Micrometer returns the same object for the same name+tags pair, so no new counter is created
     * per disconnect.
     */
    public void recordSessionClose(String reason) {
        Counter.builder("ws.sessions.closed.total")
            .description("WebSocket sessions ended, by close reason")
            .tag("reason", reason)
            .register(registry)
            .increment();
    }
}
