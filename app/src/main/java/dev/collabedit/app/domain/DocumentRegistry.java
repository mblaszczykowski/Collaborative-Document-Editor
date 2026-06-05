package dev.collabedit.app.domain;

import dev.collabedit.app.config.CollabEditProperties;
import dev.collabedit.app.persist.OperationStore;
import dev.collabedit.app.persist.SnapshotStore;
import dev.collabedit.app.persist.StoredOperation;
import dev.collabedit.crdt.RgaDocument;
import dev.collabedit.crdt.TreapBlockList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Repliki dokumentów trzymane w pamięci tej instancji — ładowanie na żądanie, zwalnianie
 * po bezczynności.
 *
 * <p>Wynika z ADR-0004: dokument otwarty przez klientów podłączonych do trzech instancji
 * żyje w trzech kopiach. To jest <b>świadomy koszt</b> rezygnacji z właściciela dokumentu
 * i sticky sessions — zapisany wprost w konsekwencjach negatywnych tamtego ADR-a. Przy
 * dokumentach rzędu megabajtów albo przy kilkunastu instancjach właściwą odpowiedzią
 * byłaby zmiana architektury, a nie optymalizacja tego miejsca.
 */
@Component
public class DocumentRegistry {

    private static final Logger log = LoggerFactory.getLogger(DocumentRegistry.class);

    /**
     * Identyfikator repliki tej instancji backendu.
     *
     * <p>Ujemny celowo: klienty dostają identyfikatory dodatnie (licznik w Redisie), więc
     * przestrzenie nie mogą się przeciąć nawet przy błędzie w przydzielaniu. Instancja
     * serwera prawie nigdy nie generuje własnych operacji — wyjątkiem jest przywracanie
     * wersji z historii (CP5), które zgodnie z ADR-0003 zapisujemy jako nowe operacje.
     */
    private final int serverReplicaId = -Math.abs(UUID.randomUUID().hashCode() % 1_000_000) - 1;

    private final Map<UUID, DocumentReplica> replicas = new ConcurrentHashMap<>();
    private final OperationStore operations;
    private final SnapshotStore snapshots;
    private final CollabEditProperties properties;

    public DocumentRegistry(OperationStore operations, SnapshotStore snapshots,
                            CollabEditProperties properties) {
        this.operations = operations;
        this.snapshots = snapshots;
        this.properties = properties;
    }

    public int serverReplicaId() {
        return serverReplicaId;
    }

    /**
     * Replika dokumentu, ładowana z bazy przy pierwszym użyciu.
     *
     * <p>Ładowanie to snapshot (jeśli jest czytelny) plus odtworzenie ogona logu operacji.
     * Gdy snapshotu nie ma albo ma nieobsługiwany format, odtwarzamy z całego logu — wolniej,
     * ale z identycznym wynikiem. Ta równoważność jest testowana wprost (CP5), bo bez testu
     * cache po cichu stałby się źródłem prawdy.
     */
    public DocumentReplica get(UUID documentId) {
        return replicas.computeIfAbsent(documentId, this::load);
    }

    private DocumentReplica load(UUID documentId) {
        long started = System.nanoTime();
        long headSeq = operations.maxServerSeq(documentId);

        var snapshot = snapshots.findLatestUpTo(documentId, headSeq);
        RgaDocument document;
        long fromSeq;
        if (snapshot.isPresent()) {
            document = RgaDocument.restore(serverReplicaId, new TreapBlockList(),
                snapshot.get().snapshot());
            fromSeq = snapshot.get().upToServerSeq();
        } else {
            document = new RgaDocument(serverReplicaId, new TreapBlockList());
            fromSeq = 0;
        }

        List<StoredOperation> tail = operations.loadAfter(documentId, fromSeq);
        for (StoredOperation stored : tail) {
            document.apply(stored.op());
        }

        if (document.pendingCount() > 0) {
            // Operacja bez swojej przyczyny w logu oznaczałaby uszkodzony log — coś, czego
            // przy poprawnym zapisie nie da się wytworzyć. Logujemy głośno zamiast milczeć,
            // bo cicha utrata treści jest znacznie gorsza niż widoczny alarm.
            log.error("Dokument {} po odtworzeniu ma {} operacji bez przyczyny — log może być uszkodzony",
                documentId, document.pendingCount());
        }

        log.info("Załadowano dokument {}: snapshot={}, operacji z logu={}, znaków={}, tombstonów={}, czas={}ms",
            documentId, snapshot.isPresent(), tail.size(), document.visibleLength(),
            document.tombstoneCount(), (System.nanoTime() - started) / 1_000_000);

        return new DocumentReplica(documentId, document, headSeq, System.currentTimeMillis());
    }

    public Collection<DocumentReplica> loaded() {
        return List.copyOf(replicas.values());
    }

    public boolean isLoaded(UUID documentId) {
        return replicas.containsKey(documentId);
    }

    /**
     * Zwalnia z pamięci repliki dokumentów, które od dłuższego czasu nikogo nie interesują.
     *
     * <p>Replika z niezapisanym wsadem nie jest zwalniana — inaczej zwolnienie pamięci
     * kasowałoby przyjęte, ale jeszcze nietrwałe operacje.
     */
    public int evictIdle(long nowMillis) {
        long ttl = properties.cluster().idleDocumentEvictionMs();
        int evicted = 0;
        for (Map.Entry<UUID, DocumentReplica> entry : replicas.entrySet()) {
            DocumentReplica replica = entry.getValue();
            if (nowMillis - replica.lastActivityMillis() < ttl) {
                continue;
            }
            if (replica.unwrittenCount() > 0) {
                continue;
            }
            replicas.remove(entry.getKey(), replica);
            evicted++;
        }
        if (evicted > 0) {
            log.debug("Zwolniono {} bezczynnych dokumentów", evicted);
        }
        return evicted;
    }

    /** Wyłącznie do testów — wymusza ponowne odczytanie stanu z bazy. */
    public void forget(UUID documentId) {
        replicas.remove(documentId);
    }
}
