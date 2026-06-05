package dev.collabedit.app.domain;

import dev.collabedit.app.cluster.ClusterBus;
import dev.collabedit.app.config.CollabEditProperties;
import dev.collabedit.app.persist.OperationStore;
import dev.collabedit.app.persist.PendingOperation;
import dev.collabedit.app.persist.SnapshotStore;
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
 * Ścieżka zapisu operacji — realizacja kolejności kroków z ADR-0004.
 *
 * <pre>
 *   klient ──op──► 1. scalenie do repliki w pamięci      (natychmiast)
 *                  2. rozgłoszenie do własnych klientów  (natychmiast)
 *                  3. zapis do logu operacji             (wsadowo, co 50 ms)
 *                  4. potwierdzenie z numerem server_seq (po zapisie)
 *                  5. publikacja do Redisa               (po zapisie, best-effort)
 * </pre>
 *
 * <p>Kolejność nie jest dowolna:
 * <ul>
 *   <li><b>Rozgłoszenie przed zapisem</b>, bo to ono odpowiada za to, co użytkownik widzi.
 *       Czekanie na dysk dodałoby opóźnienie do najbardziej zauważalnej rzeczy w systemie.</li>
 *   <li><b>Potwierdzenie po zapisie</b>, bo dopiero wtedy wolno klientowi zwolnić operację
 *       z bufora offline. Inaczej pad instancji w tym oknie zostawia operację, którą inni
 *       już widzą, a której nikt nie potrafi odtworzyć.</li>
 *   <li><b>Publikacja po zapisie</b>, bo instancja odbierająca uznaje otrzymany
 *       {@code server_seq} za osiągnięty. Publikacja przed zapisem mogłaby ogłosić numer,
 *       którego w bazie nigdy nie będzie — i którego nikt już nie dociągnie.</li>
 * </ul>
 */
@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    private final DocumentRegistry registry;
    private final OperationStore operations;
    private final SnapshotStore snapshots;
    private final ClusterBus cluster;
    private final CollabEditProperties properties;

    /** Wywoływane przy rozgłoszeniu operacji; podpinane przez warstwę WebSocket. */
    private volatile BiConsumer<UUID, List<StoredOperation>> onBroadcast = (id, ops) -> { };
    private volatile BiConsumer<UUID, List<Op>> onFastPath = (id, ops) -> { };

    public DocumentService(DocumentRegistry registry, OperationStore operations,
                           SnapshotStore snapshots, ClusterBus cluster,
                           CollabEditProperties properties) {
        this.registry = registry;
        this.operations = operations;
        this.snapshots = snapshots;
        this.cluster = cluster;
        this.properties = properties;
    }

    @PostConstruct
    void subscribeToCluster() {
        cluster.onMessage(this::onClusterMessage);
    }

    /** Rozgłoszenie operacji do klientów tej instancji (krok 2) — bez numeru {@code server_seq}. */
    public void onFastPath(BiConsumer<UUID, List<Op>> handler) {
        this.onFastPath = handler;
    }

    /** Potwierdzenia po zapisie (krok 4) — z numerem {@code server_seq}. */
    public void onAcknowledged(BiConsumer<UUID, List<StoredOperation>> handler) {
        this.onBroadcast = handler;
    }

    // ------------------------------------------------------------------
    // Kroki 1–2: przyjęcie operacji od klienta
    // ------------------------------------------------------------------

    public void submit(UUID documentId, Op op, String userId) {
        DocumentReplica replica = registry.get(documentId);
        if (!replica.merge(op, userId, System.currentTimeMillis())) {
            // Duplikat — nie ma czego zapisywać ani rozsyłać. Zdarza się przy retransmisji
            // po ponownym połączeniu, więc jest sytuacją normalną, a nie błędem.
            return;
        }
        onFastPath.accept(documentId, List.of(op));
    }

    // ------------------------------------------------------------------
    // Kroki 3–5: wsadowy zapis, potwierdzenia, publikacja
    // ------------------------------------------------------------------

    /**
     * Opróżnia bufory zapisu wszystkich załadowanych dokumentów.
     *
     * <p>Odstęp jest krótszy niż okno wsadowania z konfiguracji, żeby operacja czekała
     * na zapis najwyżej tyle, ile deklaruje {@code op-batch-window-ms}, a nie dwa razy tyle.
     */
    @Scheduled(fixedDelayString = "${collabedit.persistence.op-batch-window-ms:50}")
    public void flushAll() {
        for (DocumentReplica replica : registry.loaded()) {
            try {
                flush(replica);
            } catch (RuntimeException e) {
                log.error("Nie udało się zapisać wsadu dla dokumentu {}", replica.documentId(), e);
            }
        }
    }

    private void flush(DocumentReplica replica) {
        List<PendingOperation> batch = replica.drainUnwritten();
        if (batch.isEmpty()) {
            return;
        }

        List<StoredOperation> appended = operations.append(replica.documentId(), batch);
        if (appended.isEmpty()) {
            return;
        }

        long highest = appended.getLast().serverSeq();
        replica.advanceServerSeq(highest);

        onBroadcast.accept(replica.documentId(), appended);
        cluster.publish(replica.documentId(), appended);

        maybeSnapshot(replica);
    }

    private void maybeSnapshot(DocumentReplica replica) {
        if (!replica.snapshotDue(properties.persistence().snapshotEveryNOps())) {
            return;
        }
        // GC tombstone'ów dzieje się PRZY snapshotowaniu, a nie jako osobny proces (ADR-0005):
        // snapshot i tak przepisuje całą strukturę, więc kompakcja jest w tym miejscu darmowa.
        VersionVector stable = replica.stableVersion(
            System.currentTimeMillis(), properties.gc().unresponsiveReplicaTimeoutMs());

        long seq = replica.appliedServerSeq();
        var snapshot = replica.withLock(document -> {
            int removed = stable.isEmpty() ? 0 : document.gc(stable);
            if (removed > 0) {
                log.debug("GC dokumentu {}: usunięto {} znaków tombstone'ów", replica.documentId(), removed);
            }
            return document.snapshot();
        });

        snapshots.save(replica.documentId(), seq, snapshot);
        replica.snapshotTaken();
    }

    // ------------------------------------------------------------------
    // Odbiór z klastra + anti-entropy (ADR-0004)
    // ------------------------------------------------------------------

    private void onClusterMessage(ClusterBus.ClusterMessage message) {
        // Dokument, którego ta instancja nie trzyma w pamięci, jest nas nie obchodzi:
        // nie ma tu żadnego klienta, a stan i tak zostanie odczytany z bazy przy
        // pierwszym otwarciu. Ładowanie go tylko po to, żeby scalić operację, byłoby
        // dokładnie tym mnożeniem pamięci, którego ADR-0004 każe unikać.
        if (!registry.isLoaded(message.documentId())) {
            return;
        }
        DocumentReplica replica = registry.get(message.documentId());

        long expected = replica.appliedServerSeq() + 1;
        long firstIncoming = message.operations().getFirst().serverSeq();
        if (firstIncoming > expected) {
            // Luka w numeracji — brakujące operacje dociągamy z logu, czyli ze ścieżki
            // autorytatywnej. Redis zgubił wiadomość; to jest przewidziane, nie awaryjne.
            log.info("Luka w numeracji dokumentu {}: mam {}, przyszło {} — dociągam z logu",
                message.documentId(), replica.appliedServerSeq(), firstIncoming);
            repairFrom(replica, replica.appliedServerSeq());
        }

        List<Op> applied = applyRemote(replica, message.operations());
        if (!applied.isEmpty()) {
            onFastPath.accept(message.documentId(), applied);
        }
        replica.advanceServerSeq(message.operations().getLast().serverSeq());
    }

    private List<Op> applyRemote(DocumentReplica replica, List<StoredOperation> incoming) {
        List<Op> applied = new java.util.ArrayList<>(incoming.size());
        for (StoredOperation stored : incoming) {
            // Operacje z klastra są już w logu — scalamy je do repliki w pamięci,
            // ale NIE dopisujemy ponownie. Stąd bezpośrednie wejście w RgaDocument
            // zamiast DocumentReplica.merge, które kolejkowałoby je do zapisu.
            RgaDocument.ApplyResult result =
                replica.withLock(document -> document.apply(stored.op()));
            if (result.isNews()) {
                applied.add(stored.op());
            }
        }
        return applied;
    }

    private void repairFrom(DocumentReplica replica, long fromServerSeq) {
        List<StoredOperation> missing = operations.loadAfter(replica.documentId(), fromServerSeq);
        List<Op> applied = applyRemote(replica, missing);
        if (!applied.isEmpty()) {
            onFastPath.accept(replica.documentId(), applied);
        }
        if (!missing.isEmpty()) {
            replica.advanceServerSeq(missing.getLast().serverSeq());
        }
    }

    /**
     * Okresowy przegląd wykrywający rozjazd, którego wykrywanie luk wykryć nie może:
     * <b>utratę ostatniej operacji</b>, po której nie przyszła już żadna następna.
     *
     * <p>Bez tego replika dotknięta zgubioną wiadomością zostaje z nieaktualnym tekstem
     * aż do następnej edycji dokumentu — czyli potencjalnie na zawsze, jeśli dokument
     * ucichł. Górna granica czasu życia niewykrytego rozjazdu to odstęp tego przeglądu.
     */
    @Scheduled(fixedDelayString = "${collabedit.cluster.anti-entropy-interval-ms:30000}")
    public void antiEntropySweep() {
        for (DocumentReplica replica : registry.loaded()) {
            try {
                long head = operations.maxServerSeq(replica.documentId());
                if (head > replica.appliedServerSeq()) {
                    log.info("Anti-entropy dokumentu {}: log ma {}, replika {} — naprawiam",
                        replica.documentId(), head, replica.appliedServerSeq());
                    repairFrom(replica, replica.appliedServerSeq());
                }
            } catch (RuntimeException e) {
                log.warn("Przegląd anti-entropy dokumentu {} nieudany", replica.documentId(), e);
            }
        }
    }

    @Scheduled(fixedDelayString = "${collabedit.cluster.idle-document-eviction-ms:300000}")
    public void evictIdleDocuments() {
        registry.evictIdle(System.currentTimeMillis());
    }

    // ------------------------------------------------------------------
    // Odczyt
    // ------------------------------------------------------------------

    public String textOf(UUID documentId) {
        return registry.get(documentId).withLock(RgaDocument::text);
    }

    /** Operacje, których klientowi brakuje — odpowiedź na prośbę o resynchronizację (CP6). */
    public List<StoredOperation> operationsSince(UUID documentId, long sinceServerSeq) {
        return operations.loadAfter(documentId, sinceServerSeq);
    }

    public long headServerSeq(UUID documentId) {
        return operations.maxServerSeq(documentId);
    }
}
