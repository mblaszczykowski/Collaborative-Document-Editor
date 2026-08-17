package dev.collabedit.app.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("collabedit")
public record CollabEditProperties(
    WebSocket websocket,
    Persistence persistence,
    Cluster cluster,
    Gc gc,
    Auth auth
) {
    public static final class Defaults {
        private Defaults() {
        }

        public static final String BROADCAST_WINDOW_MS = "10";

        public static final String OP_BATCH_WINDOW_MS = "50";

        public static final String SNAPSHOT_SWEEP_INTERVAL_MS = "1000";

        public static final String ANTI_ENTROPY_INTERVAL_MS = "30000";

        public static final String IDLE_DOCUMENT_EVICTION_MS = "300000";

        public static final String IDLE_DOCUMENT_SWEEP_MS = "60000";
    }

    public CollabEditProperties {
        websocket = websocket == null ? WebSocket.defaults() : websocket;
        persistence = persistence == null ? Persistence.defaults() : persistence;
        cluster = cluster == null ? Cluster.defaults() : cluster;
        gc = gc == null ? Gc.defaults() : gc;
        auth = auth == null ? Auth.defaults() : auth;
    }

    public record WebSocket(
        long heartbeatIntervalMs,
        double heartbeatToleranceFactor,
        int maxMessageSizeBytes,
        int sendBufferSizeBytes,
        long sendTimeLimitMs,
        long broadcastWindowMs,
        List<String> allowedOriginPatterns
    ) {
        public WebSocket {
            allowedOriginPatterns = allowedOriginPatterns == null || allowedOriginPatterns.isEmpty()
                ? List.of("http://localhost:*", "http://127.0.0.1:*")
: List.copyOf(allowedOriginPatterns);
        }

        static WebSocket defaults() {
            return new WebSocket(10_000, 2.5, 65_536, 524_288, 20_000,
                Long.parseLong(Defaults.BROADCAST_WINDOW_MS), null);
        }
    }

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
            if (snapshotsKeptPerDocument < 1) {
                snapshotsKeptPerDocument = 3;
            }
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

    public record Gc(
        int windowDays,
        long unresponsiveReplicaTimeoutMs
    ) {
        static Gc defaults() {
            return new Gc(7, 60_000);
        }
    }

    public record Auth(
        boolean devLoginEnabled,
        String appUrl,
        Provider google,
        Provider github
    ) {
        static Auth defaults() {
            return new Auth(false, "http://localhost:5173", Provider.none(), Provider.none());
        }

        public Auth {
            google = google == null ? Provider.none() : google;
            github = github == null ? Provider.none() : github;
            appUrl = appUrl == null ? "http://localhost:5173" : appUrl;
        }

        public record Provider(String clientId, String clientSecret) {
            static Provider none() {
                return new Provider(null, null);
            }

            public boolean configured() {
                return clientId != null && !clientId.isBlank()
                    && clientSecret != null && !clientSecret.isBlank();
            }
        }

        public List<String> providers() {
            List<String> configured = new java.util.ArrayList<>(2);
            if (google.configured()) {
                configured.add("google");
            }
            if (github.configured()) {
                configured.add("github");
            }
            return List.copyOf(configured);
        }

        public boolean federatedLoginConfigured() {
            return !providers().isEmpty();
        }
    }
}
