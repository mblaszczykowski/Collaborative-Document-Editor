package dev.collabedit.app.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The system's tuning parameters. Every one of them is justified — the comments
 * next to the default values live in {@code application.yml} rather than here, so that whoever
 * changes the configuration sees the reason at the point of change.
 *
 * <p>The defaults in the constructors deliberately match those in {@code application.yml}: starting
 * with an empty configuration should give a system that works rather than one that throws at
 * startup.
 */
@ConfigurationProperties("collabedit")
public record CollabEditProperties(
    WebSocket websocket,
    Persistence persistence,
    Cluster cluster,
    Gc gc
) {

    /**
     * The defaults of the values that <b>also appear inside a {@code @Scheduled} annotation</b>.
     *
     * <h2>Why these five are constants and the rest are not</h2>
     * {@code broadcast-window-ms}, {@code op-batch-window-ms}, {@code snapshot-sweep-interval-ms},
     * {@code anti-entropy-interval-ms} and {@code idle-document-eviction-ms} drive a periodic job,
     * and Spring resolves a job's period from the literal inside
     * {@code @Scheduled(fixedDelayString = "${…:10}")}. So each default used to exist in THREE
     * places — {@code application.yml}, the {@code defaults} factory below, and the annotation —
     * and only the annotation was load-bearing. Editing {@code defaults} changed nothing, which
     * made four record components read as settings while being decoration. This project already
     * dismantled that exact pattern once, for {@code heartbeatTimeoutMs}.
     *
     * <p>The fix is not a comment saying "the annotation wins". It is making them <b>unable to
     * disagree</b>: the annotation and {@code defaults} now read the same constant.
     *
     * <h2>Why the accessors still have no callers, and why that is not dead code</h2>
     * {@code broadcastWindowMs()}, {@code opBatchWindowMs()}, {@code snapshotSweepIntervalMs()} and
     * {@code antiEntropyIntervalMs()} are called from nowhere, and a search for unused API finds
     * them. They stay, deliberately, and the reason is the one thing a record component can do that
     * a constant cannot: <b>be the place a reader finds the setting</b>. Spring resolves these four
     * from the property placeholder inside the annotation, so nothing will ever call the accessor —
     * but a configuration surface documented in two halves, some of it modelled here and some of it
     * only in string literals scattered across {@code @Scheduled} methods, is worse than four
     * accessors nobody calls.
     *
     * <p>Which is a different judgement from the one made about {@code heartbeatTimeoutMs} and about
     * the getters removed from {@code DocumentRegistry} and {@code DocumentReplica}, and the
     * difference is worth naming: those described state, and state nobody reads is state nobody
     * maintains. These describe configuration, and the configuration is real whether or not this
     * type is how it is read.
     *
     * <h2>Why they are strings</h2>
     * An annotation attribute must be a compile-time constant expression, and
     * {@code fixedDelayString} takes a {@code String} — so the shared constant has to be one, and
     * the numeric side is parsed from it. That is the inelegant half of the trade, and it is the
     * cheaper half: parsing a literal cannot drift, whereas two literals silently can.
     */
    public static final class Defaults {

        private Defaults() {
        }

        /** the transport decision — the broadcast coalescing window. */
        public static final String BROADCAST_WINDOW_MS = "10";

        /** the operation-log decision — how often the write buffers are drained. */
        public static final String OP_BATCH_WINDOW_MS = "50";

        /** the operation-log decision — how often the maintenance pool asks whether a snapshot is due. */
        public static final String SNAPSHOT_SWEEP_INTERVAL_MS = "1000";

        /** the cluster design — the anti-entropy sweep. */
        public static final String ANTI_ENTROPY_INTERVAL_MS = "30000";

        /** the cluster design — how long a replica with no clients stays in memory. */
        public static final String IDLE_DOCUMENT_EVICTION_MS = "300000";

        /**
         * How often the maintenance pool LOOKS for idle replicas.
         *
         * <p>Separate from the threshold above, because the sweep used to run at exactly the
         * threshold and use it as both period and TTL. A replica going idle just after a pass waits
         * a full period for the next one, so the real bound was [5, 10) minutes while the setting,
         * its comment in {@code application.yml} and the cluster design all say five.
         *
         * <p>One minute, so the bound is [5, 6) minutes and the stated number is true to within the
         * sweep. Not shorter: the pass walks every loaded replica and takes each one's lock to claim
         * it, and nothing here is urgent — this reclaims memory from documents nobody has open.
         */
        public static final String IDLE_DOCUMENT_SWEEP_MS = "60000";
    }

    public CollabEditProperties {
        websocket = websocket == null ? WebSocket.defaults() : websocket;
        persistence = persistence == null ? Persistence.defaults() : persistence;
        cluster = cluster == null ? Cluster.defaults() : cluster;
        gc = gc == null ? Gc.defaults() : gc;
    }

    /** the transport decision — transport. */
    public record WebSocket(
        long heartbeatIntervalMs,
        double heartbeatToleranceFactor,
        int maxMessageSizeBytes,
        int sendBufferSizeBytes,
        long sendTimeLimitMs,
        long broadcastWindowMs,
        List<String> allowedOriginPatterns
    ) {
        /**
         * Origins allowed to open the WebSocket.
         *
         * <p>Configurable rather than compiled in, because the previous version pinned
         * {@code http://localhost:*} in {@code WebSocketConfig} and thereby made every hosted
         * deployment fail at the handshake — the browser sends the deployed origin, the server
         * compares it against localhost and refuses. That was one of four code-level reasons
         * plan v3 §0.1 could not ship, none of which its audit records, because the audit
         * looked for a hosting account rather than at the code.
         *
         * <p>The default stays local-only. An origin allow-list that defaults to permissive is
         * the same class of mistake as a signing secret that defaults to a value in the repo
         * ({@link dev.collabedit.app.auth.TokenService}): it works everywhere, including
         * everywhere it should not.
         */
        public WebSocket {
            allowedOriginPatterns = allowedOriginPatterns == null || allowedOriginPatterns.isEmpty()
                ? List.of("http://localhost:*", "http://127.0.0.1:*")
: List.copyOf(allowedOriginPatterns);
        }

        static WebSocket defaults() {
            return new WebSocket(10_000, 2.5, 65_536, 524_288, 20_000,
                Long.parseLong(Defaults.BROADCAST_WINDOW_MS), null);
        }

        // heartbeatTimeoutMs used to be computed here from heartbeatToleranceFactor, and
        // NOTHING read either of them: WebSocketConfig passes only the interval to the broker, and
        // the simple broker has no tolerance factor to configure. So the transport decision's row for it, the
        // comment in application.yml and the derivation of unresponsive-replica-timeout-ms all
        // rested on a value the system does not have. The factor stays in the properties as the
        // DOCUMENTED intent — it is what the transport decision argues for and what a broker that accepted one
        // would be given — and the accessor is gone rather than left looking load-bearing.
    }

    /** the operation-log decision — operation log and snapshots. */
    public record Persistence(
        long opBatchWindowMs,
        int opBatchMaxSize,
        int snapshotEveryNOps,
        long snapshotAfterIdleMs,
        long snapshotSweepIntervalMs,
        int snapshotsKeptPerDocument,
        int maxPendingOperations,
        int maxSyncOperations
    ) {
        public Persistence {
            // A retention of zero would delete the snapshot being written, which is worse than
            // having no retention at all: every document open would replay its whole log.
            if (snapshotsKeptPerDocument < 1) {
                snapshotsKeptPerDocument = 3;
            }
            // Zero or negative would mean "refuse everything" and "send nothing" respectively,
            // which are worse than the value being absent — a configuration mistake would take
            // the whole editing path down rather than one document.
            if (maxPendingOperations < 1) {
                maxPendingOperations = 10_000;
            }
            if (maxSyncOperations < 1) {
                maxSyncOperations = 5_000;
            }
        }

        static Persistence defaults() {
            return new Persistence(
                Long.parseLong(Defaults.OP_BATCH_WINDOW_MS), 100, 500, 30_000,
                Long.parseLong(Defaults.SNAPSHOT_SWEEP_INTERVAL_MS), 3, 10_000, 5_000);
        }
    }

    /** the cluster design — horizontal scaling. */
    public record Cluster(
        long antiEntropyIntervalMs,
        long idleDocumentEvictionMs,
        long idleDocumentSweepMs,
        int maxLoadedDocuments
    ) {
        static Cluster defaults() {
            return new Cluster(
                Long.parseLong(Defaults.ANTI_ENTROPY_INTERVAL_MS),
                Long.parseLong(Defaults.IDLE_DOCUMENT_EVICTION_MS),
                Long.parseLong(Defaults.IDLE_DOCUMENT_SWEEP_MS),
                200);
        }
    }

    /** the tombstone-collection design — tombstone garbage collection. */
    public record Gc(
        int windowDays,
        long unresponsiveReplicaTimeoutMs
    ) {
        static Gc defaults() {
            return new Gc(7, 60_000);
        }
    }
}
