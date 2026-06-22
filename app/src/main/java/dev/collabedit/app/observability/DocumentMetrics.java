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
 * caveats. <b>This row described an interval the code did not measure</b>: the timer was
 * stopped immediately after the merge, before the batching window, so it and
 * {@code crdt_merge_duration} bracketed almost the same lines. It now spans what it says
 * — see {@link #recordServerLatency(long)}.</td></tr>
 *   <tr><td>{@code doc_tombstone_ratio}</td>
 *       <td>Whether GC works at all. A monotonically rising value means causal stability is
 *           stalled — for instance by one hung client.</td></tr>
 *   <tr><td>{@code cluster_gap_repairs}</td>
 *       <td>How often Redis loses messages. A value of zero for a long time means the repair
 *           mechanism from the cluster design is not exercised in practice — that is, there is no telling
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

    /**
     * {@code crdt.merges.total}.
     *
     * <p>This field was called {@code concurrentMerges}, which describes neither the meter it holds
     * nor the number in it: the counter is every operation merged into any of this instance's
     * replicas, and concurrency does not enter into it. A field name that disagrees with its meter
     * is how somebody later reads a graph as answering a question it never asked.
     */
    private final Counter merges;
    private final Counter gapRepairs;
    private final Counter gcReclaimedCharacters;
    private final Counter rejectedStaleOperations;
    private final Counter rejectedForPendingOverflow;
    private final Counter rejectedReplicaIdConflicts;
    private final Counter failedWrites;
    private final Counter gcBlocked;
    private final Counter clusterMessagesDropped;

    /**
     * Refusal counters by reason — see {@link #recordFrameRefused}. Bounded by the enum that feeds
     * it, so this cannot grow with anything a client controls.
     */
    private final Map<String, Counter> frameRefusals = new ConcurrentHashMap<>();

    /**
     * {@code ws.sessions.opened.total}.
     *
     * <p>Three names for one thing used to meet here: the field said {@code reconnects}, the
     * description said "WebSocket sessions established", and the increment came from
     * {@code PresenceService.join} — that is, from JOINING A DOCUMENT, which is neither a reconnect
     * nor a session. The increment moved to {@code SessionLifecycle}, where a session actually
     * begins; the name now says what it holds.
     */
    private final Counter sessionsOpened;

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
            // Its two sibling timers above had this and it did not, which is not a style
            // difference: publishPercentiles alone emits pre-computed `quantile` series and NO
            // `_bucket` series at all. The Grafana dashboard's "batch write p95" panel queries
            // op_persist_duration_seconds_bucket, so it was querying a series this application has
            // never emitted — an empty legend that reads as "no writes are happening" rather than
            // as "this panel is wrong".
                .publishPercentileHistogram()
            .register(registry);

        this.merges = Counter.builder("crdt.merges.total")
            .description("Operations merged into this instance's replicas")
            .register(registry);

        this.gapRepairs = Counter.builder("cluster.gap.repairs.total")
            .description("Repairs of gaps in the numbering — that is, messages lost by Redis")
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
                + "dropping its content (CP18)")
            .register(registry);

        this.rejectedStaleOperations = Counter.builder("op.rejected.stale.total")
            .description("Operations rejected because GC removed their anchor point")
            .register(registry);

        // A separate series from the one above, although both end in a RESYNC_REQUIRED for the
        // client, because they say opposite things about where the fault is. A stale rejection
        // means the client was away too long — expected, and what the GC window is for. This one
        // means a client is sending operations whose causes never arrive, which is either a broken
        // client or a deliberate one, and it is the only visible sign of either.
        this.rejectedForPendingOverflow = Counter.builder("op.rejected.pending.total")
            .description("Operations refused because the replica's buffer of operations waiting "
                + "for a cause is full (collabedit.persistence.max-pending-operations)")
            .register(registry);

        // Zero here is the expected reading, and that is exactly why it is worth emitting: replica
        // identifiers are unique BY ALLOCATION (ReplicaIdAllocator), so any non-zero rate means
        // either the allocator is handing out collisions or somebody is submitting under an
        // identifier they read out of a presence frame. Both need to be seen, and neither leaves
        // any other trace.
        this.rejectedReplicaIdConflicts = Counter.builder("op.rejected.replica.conflict.total")
            .description("Operations refused because their replica id belongs to a different user")
            .register(registry);

        // A cluster message the listener pool had no room for. The cluster design makes this bus
        // best-effort, so dropping is a legitimate answer under load — but it must be a VISIBLE
        // one. A rising rate here means anti-entropy is doing work the fast path should have done.
        this.clusterMessagesDropped = Counter.builder("cluster.messages.dropped.total")
            .description("Cluster messages discarded because the listener pool was saturated")
            .register(registry);

        this.gcBlocked = Counter.builder("crdt.gc.blocked.total")
            .description("Snapshots where GC reclaimed nothing although tombstones were present — "
                + "a replica has not caught up")
            .register(registry);

        this.failedWrites = Counter.builder("op.write.failures.total")
            .description("Operations whose batch write failed and were returned to the buffer")
            .register(registry);

        this.sessionsOpened = Counter.builder("ws.sessions.opened.total")
            .description("WebSocket sessions established — one per STOMP CONNECT")
            .register(registry);

        Gauge.builder("ws.sessions.active", activeSessions, AtomicInteger::get)
            .description("Active WebSocket sessions on this instance")
            .register(registry);

        // `doc.loaded`, not `doc.loaded.count`. In Prometheus `_count` is RESERVED: it is the
        // sample a histogram or summary emits alongside its `_sum` and `_bucket` series. A gauge
        // called doc_loaded_count sits in the same namespace as those and reads, to anybody
        // scanning the metric list, as the observation count of a timer that does not exist.
        //
        // Renaming a meter breaks the continuity of the historical series and requires the
        // dashboard to be fixed in the same commit — ops/grafana/dashboards/collabedit.json, done
        // here. The same trade crdt.gc.reclaimed.characters.total made in CP18, for the same
        // reason: a name that says something other than what it holds costs more than a gap.
        //
        // loadedCount, not loaded.size: the gauge needs a number, and asking for the
        // collection to call size on it was an allocation of the whole thing per scrape.
        Gauge.builder("doc.loaded", documents, DocumentRegistry::loadedCount)
            .description("Documents held in this instance's memory")
            .register(registry);

        // The tombstone share computed across all in-memory documents. A high value sustained
        // over time signals that GC is not working — not that users delete a lot.
        // Bound to the registry rather than to `this`, so that a DocumentMetrics built without one
        // — DocumentAccessInterceptorTest does exactly that, because a frame refusal has nothing to
        // do with replicas — reports NaN instead of throwing inside a scrape.
        Gauge.builder("doc.tombstone.ratio", documents,
                loaded -> replicaCounts(loaded).tombstoneRatio())
            .description("Tombstones / all characters, across in-memory documents")
            .register(registry);

        Gauge.builder("crdt.pending.operations", documents,
                loaded -> replicaCounts(loaded).pending)
            .description("Operations waiting for their cause — a sustained value > 0 means corruption")
            .register(registry);
    }

    /**
     * The three per-replica numbers, taken in <b>one pass and one lock acquisition per replica</b>.
     *
     * @param pending operations buffered waiting for a cause, summed over loaded replicas
     */
    private record ReplicaCounts(long visible, long tombstones, long pending) {

        double tombstoneRatio() {
            long total = visible + tombstones;
            return total == 0 ? 0.0 : (double) tombstones / total;
        }
    }

    /**
     * How long a sweep is reused before the next one is taken.
     *
     * <p>Shorter than any scrape interval anybody configures (Prometheus defaults to 15 s and
     * {@code ops/prometheus.yml} sets 5 s), so consecutive gauges within ONE scrape share a sweep
     * and two scrapes never do. That is the whole intent: the cache exists to collapse the gauges
     * of a single scrape, not to serve stale numbers between them.
     */
    private static final long COUNTS_CACHE_MILLIS = 1_000;

    private volatile ReplicaCounts cachedCounts = new ReplicaCounts(0, 0, 0);
    private volatile long cachedCountsAtMillis;

    /**
     * The per-replica numbers behind {@code doc.tombstone.ratio} and
     * {@code crdt.pending.operations}.
     *
     * <h2>Why one pass and not two gauges each walking the registry</h2>
     * The two were registered as independent gauges, each iterating every loaded replica and taking
     * its lock. At {@code max-loaded-documents: 200} that is <b>400 lock acquisitions per
     * Prometheus scrape</b>, on precisely the lock the 10 ms broadcast needs — so the cost of
     * observing the system landed on the path whose latency is the thing being observed.
     *
     * <p>The argument was already written down, three lines above where {@code tombstoneRatio} used
     * to be: "ONE acquisition, not two … it doubled the number of times a Prometheus scrape takes
     * the lock the 10 ms broadcast needs". It was applied WITHIN one gauge and not BETWEEN the two,
     * which is the same mistake at the next level up. Collapsing them halves it again, and the
     * short cache means the count is 200 acquisitions per scrape rather than per gauge.
     *
     * <p>Not synchronised, deliberately: two scrapes racing here take two sweeps and one wins, which
     * costs a duplicate pass and cannot produce a wrong number. A lock would be protecting a cache
     * of a diagnostic reading against a race whose worst outcome is doing the work twice.
     */
    private ReplicaCounts replicaCounts(DocumentRegistry documents) {
        long now = System.currentTimeMillis();
        if (now - cachedCountsAtMillis < COUNTS_CACHE_MILLIS) {
            return cachedCounts;
        }
        long visible = 0;
        long tombstones = 0;
        long pending = 0;
        for (var replica : documents.loaded()) {
            // ONE acquisition per replica for all three numbers. Separate acquisitions would also
            // let the halves of the ratio come from different instants, which is a wrong number
            // rather than merely a slow one.
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

    /**
     * How long one operation took from intake to going out on the wire.
     *
     * <p>Takes an elapsed time rather than a {@code Timer.Sample}, because the two ends are on
     * DIFFERENT THREADS: the operation arrives on the inbound channel and is broadcast up to
     * {@code broadcast-window-ms} later on the flow scheduler. A {@code Sample} is started and
     * stopped by one caller; this interval has two, and the instant travels between them inside
     * {@code PendingOperation}.
     *
     * <p>That is the whole correction. This used to be stopped in {@code DocumentService.submit}
     * the moment the merge returned — so {@code op.server.latency} and {@code crdt.merge.duration}
     * measured almost the same code, and the table at the top of this class described the first as
     * "intake -> broadcast", which it was not.
     *
     * <p>Operations that arrived from the CLUSTER are measured too, from the instant they were
     * merged here rather than from the instant their author typed them. That is the same quantity
     * this instance is answerable for — how long WE took to put it on the wire — and it is also the
     * only one measurable on one clock, which is the whole reason this metric exists and the
     * end-to-end one deliberately does not.
     */
    public void recordServerLatency(long elapsedNanos) {
        serverLatency.record(elapsedNanos, TimeUnit.NANOSECONDS);
    }

    public void recordPersist(Timer.Sample sample) {
        sample.stop(persistDuration);
    }

    /**
     * Operations merged from the cluster's fast path.
     *
     * <p>{@code crdt.merges.total} counted local submissions and gap-repaired operations and
     * missed this one entirely — so on a cluster it under-reported by roughly the cross-instance
     * traffic while over-weighting the rare repair path, and any rate built on it misread the load.
     */
    public void recordRemoteMerge(int operationsMerged) {
        if (operationsMerged > 0) {
            merges.increment(operationsMerged);
        }
    }

    public void recordGapRepair() {
        gapRepairs.increment();
        // The merge count comes from applyRemote now, which sees every path — including this one.
        // Incrementing here as well would double-count a repaired operation.
    }

    public void recordGc(int charactersReclaimed) {
        gcReclaimedCharacters.increment(charactersReclaimed);
    }

    /**
     * A snapshot where GC reclaimed nothing while tombstones were present.
     *
     * <p>The tombstone-collection design promised a "why is GC stuck" signal and nothing produced one, so the only way to
     * tell "GC is working and there is nothing to collect" from "GC is blocked" was to watch the
     * tombstone ratio climb and infer it. A rising rate here says the second, and the DEBUG line
     * beside the increment names the replica holding it back.
     */
    public void recordGcBlocked() {
        gcBlocked.increment();
    }

    /** A cluster message the listener pool had no room for — see {@code RedisConfig}. */
    public void recordClusterMessageDropped() {
        clusterMessagesDropped.increment();
    }

    public void recordStaleRejection() {
        rejectedStaleOperations.increment();
    }

    /** An operation refused because the replica's buffer of causeless operations is full. */
    public void recordPendingOverflow() {
        rejectedForPendingOverflow.increment();
    }

    /** An operation refused because its replica id is already in use by a different user. */
    public void recordReplicaIdConflict() {
        rejectedReplicaIdConflicts.increment();
    }

    /**
     * A batch write that threw.
     *
     * <p>The operations are put back in the buffer and retried in the next window, so this is not
     * data loss — but it IS the one place where operations are already broadcast and not yet in the
     * source of truth, and before this counter existed the only trace was a log line. A non-zero
     * rate means the database is the problem.
     */
    public void recordWriteFailure(int operationsReturned) {
        failedWrites.increment(operationsReturned);
    }

    /**
     * A WebSocket session began — one {@code CONNECT} frame.
     *
     * <p>Called from {@code SessionLifecycle}, which is where a session begins. It used to be
     * called from {@code PresenceService.join}, which is where a session joins a DOCUMENT — a
     * different event that can happen zero times or, under the frame-ordering race that lifecycle
     * class exists to close, twice. Both mistakes landed here: {@code ws.sessions.opened.total}
     * double-counted and {@code ws.sessions.active} drifted upwards and never came back down.
     */
    public void sessionOpened() {
        activeSessions.incrementAndGet();
        sessionsOpened.increment();
    }

    public void sessionClosed() {
        activeSessions.decrementAndGet();
    }

    /**
     * Records the REASON a session closed. It does not touch the active-session gauge — that comes
     * down in {@link #sessionClosed}, called once from the session teardown. The two describe the
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

    /**
     * A STOMP frame the interceptor refused.
     *
     * <p>The reason set is CLOSED — {@code DocumentAccessInterceptor.Refusal} is an enum — for the
     * same cardinality reason {@code recordSessionClose} collapses unknown close codes into one
     * bucket. A tag a client can influence is a way to make Prometheus run out of memory.
     *
     * <p>This did not exist, which is why the README's rejection figures come from the CP15
     * harness rather than from the server: every refusal was a WARN line and nothing else, so
     * 1614 of them in one run were 1614 log lines and zero data points.
     *
     * <p>The counters are memoised, unlike {@link #recordSessionClose}'s, and the difference is
     * the call rate rather than a change of mind. A session closes once; a frame is refused as
     * often as a client sends one, which under the rate limiter this backs is exactly when the
     * client is sending as fast as it can. Building a {@code Counter.Builder} and re-resolving it
     * through the registry on that path is work done per rejected frame, and rejecting cheaply is
     * the whole point of rejecting.
     */
    public void recordFrameRefused(String reason) {
        frameRefusals.computeIfAbsent(reason, key -> Counter.builder("ws.frames.refused.total")
            .description("STOMP frames refused by the access interceptor, by reason")
            .tag("reason", key)
            .register(registry)).increment();
    }

    /** Outbound drops by reason — see {@link #recordOutboundDrop}. Bounded by two constants. */
    private final Map<String, Counter> outboundDrops = new ConcurrentHashMap<>();

    /**
     * A frame the server built and then did NOT deliver.
     *
     * <h2>Why this is separate from {@code ws.frames.refused.total}</h2>
     * That counter is about frames a client sent and we would not accept. This one is about frames
     * WE sent and then withheld, which is a different event with a different reading: a rising
     * {@code reason="revoked"} is the access model working, while a rising
     * {@code reason="unknown_session"} means broadcasts are being withheld from sessions we cannot
     * identify — and that is a symptom, not a policy.
     *
     * <p>It has to be a number rather than a log line because of what a dropped broadcast costs
     * here specifically: an operation that arrived from the cluster is broadcast to this instance's
     * clients WITHOUT an acknowledgement, and the client's only gap detector fires on
     * acknowledgements. So a frame dropped on the outbound channel has nothing behind it — the
     * client does not learn it missed anything. See {@code RevokedAccessFilter}.
     *
     * <p>The reason set is closed, for the same cardinality argument
     * {@link #recordFrameRefused(String)} makes, and memoised for the same reason: this runs per
     * frame per subscriber.
     */
    public void recordOutboundDrop(String reason) {
        outboundDrops.computeIfAbsent(reason, key -> Counter.builder("ws.frames.dropped.total")
            .description("Frames the server withheld on the outbound channel, by reason")
            .tag("reason", key)
            .register(registry)).increment();
    }
}
