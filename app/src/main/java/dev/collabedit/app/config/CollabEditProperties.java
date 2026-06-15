package dev.collabedit.app.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The system's tuning parameters. Every one of them is justified in a specific ADR — the comments
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

    public CollabEditProperties {
        websocket = websocket == null ? WebSocket.defaults() : websocket;
        persistence = persistence == null ? Persistence.defaults() : persistence;
        cluster = cluster == null ? Cluster.defaults() : cluster;
        gc = gc == null ? Gc.defaults() : gc;
    }

    /** ADR-0002 — transport. */
    public record WebSocket(
        long heartbeatIntervalMs,
        double heartbeatToleranceFactor,
        int maxMessageSizeBytes,
        int sendBufferSizeBytes,
        long sendTimeLimitMs,
        long broadcastWindowMs,
        java.util.List<String> allowedOriginPatterns
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
                ? java.util.List.of("http://localhost:*", "http://127.0.0.1:*")
                : java.util.List.copyOf(allowedOriginPatterns);
        }

        static WebSocket defaults() {
            return new WebSocket(10_000, 2.5, 65_536, 524_288, 20_000, 10, null);
        }

        /** How long without a heartbeat before we treat a session as dead. */
        public long heartbeatTimeoutMs() {
            return Math.round(heartbeatIntervalMs * heartbeatToleranceFactor);
        }
    }

    /** ADR-0003 — log operacji i snapshoty. */
    public record Persistence(
        long opBatchWindowMs,
        int opBatchMaxSize,
        int snapshotEveryNOps,
        long snapshotAfterIdleMs,
        long snapshotSweepIntervalMs,
        int snapshotsKeptPerDocument
    ) {
        public Persistence {
            // A retention of zero would delete the snapshot being written, which is worse than
            // having no retention at all: every document open would replay its whole log.
            if (snapshotsKeptPerDocument < 1) {
                snapshotsKeptPerDocument = 3;
            }
        }

        static Persistence defaults() {
            return new Persistence(50, 100, 500, 30_000, 1_000, 3);
        }
    }

    /** ADR-0004 — skalowanie poziome. */
    public record Cluster(
        long antiEntropyIntervalMs,
        long idleDocumentEvictionMs,
        int maxLoadedDocuments
    ) {
        static Cluster defaults() {
            return new Cluster(30_000, 300_000, 200);
        }
    }

    /** ADR-0005 — garbage collection tombstone'ów. */
    public record Gc(
        int windowDays,
        long unresponsiveReplicaTimeoutMs
    ) {
        static Gc defaults() {
            return new Gc(7, 60_000);
        }
    }
}
