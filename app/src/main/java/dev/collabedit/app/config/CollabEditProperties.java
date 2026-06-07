package dev.collabedit.app.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Parametry strojenia systemu. Każdy z nich ma uzasadnienie w konkretnym ADR-ze —
 * komentarze przy wartościach domyślnych są w {@code application.yml}, a nie tutaj,
 * żeby osoba zmieniająca konfigurację widziała powód w miejscu zmiany.
 *
 * <p>Wartości domyślne w konstruktorach są celowo takie same jak w {@code application.yml}:
 * uruchomienie z pustą konfiguracją ma dać system, który działa, a nie taki, który
 * rzuca wyjątkiem przy starcie.
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
        long broadcastWindowMs
    ) {
        static WebSocket defaults() {
            return new WebSocket(10_000, 2.5, 65_536, 524_288, 20_000, 10);
        }

        /** Po jakim czasie bez heartbeatu uznajemy sesję za martwą. */
        public long heartbeatTimeoutMs() {
            return Math.round(heartbeatIntervalMs * heartbeatToleranceFactor);
        }
    }

    /** ADR-0003 — log operacji i snapshoty. */
    public record Persistence(
        long opBatchWindowMs,
        int opBatchMaxSize,
        int snapshotEveryNOps,
        long snapshotAfterIdleMs
    ) {
        static Persistence defaults() {
            return new Persistence(50, 100, 500, 30_000);
        }
    }

    /** ADR-0004 — skalowanie poziome. */
    public record Cluster(
        long antiEntropyIntervalMs,
        long idleDocumentEvictionMs
    ) {
        static Cluster defaults() {
            return new Cluster(30_000, 300_000);
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
