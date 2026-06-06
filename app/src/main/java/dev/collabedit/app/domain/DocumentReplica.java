package dev.collabedit.app.domain;

import dev.collabedit.app.persist.PendingOperation;
import dev.collabedit.crdt.Op;
import dev.collabedit.crdt.RgaDocument;
import dev.collabedit.crdt.TreapBlockList;
import dev.collabedit.crdt.VersionVector;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Replika dokumentu żyjąca w pamięci tej instancji backendu.
 *
 * <p>Nazwa jest dosłowna i wynika z ADR-0004: <b>instancja backendu jest zwykłą repliką
 * CRDT</b>, równorzędną wobec innych instancji i wobec przeglądarek. Nie ma tu właściciela
 * dokumentu, wyboru lidera ani rozproszonej blokady — bo algorytm z ADR-0001 ich nie
 * potrzebuje, a każdy z tych mechanizmów wprowadzałby całą klasę błędów, których w ten
 * sposób w ogóle nie ma.
 *
 * <p>Struktura danych to {@link TreapBlockList}, czyli wariant O(log n). Wersja naiwna
 * została użyta do zbudowania i przetestowania algorytmu (i nadal przechodzi ten sam zestaw
 * property testów), ale na ścieżce produkcyjnej jej złożoność byłaby widoczna jako opóźnienie
 * na każde naciśnięcie klawisza — patrz benchmark JMH w README.
 *
 * <p><b>Synchronizacja:</b> {@link RgaDocument} nie jest bezpieczny wątkowo i celowo taki
 * pozostaje — rdzeń algorytmu nie ma się zajmować współbieżnością. Serializacją dostępu
 * zajmuje się ta klasa, jedną blokadą na dokument. Ziarnistość „jedna blokada na dokument"
 * jest właściwa, bo operacje na różnych dokumentach są całkowicie niezależne, a operacje
 * na tym samym i tak muszą być uszeregowane.
 */
public class DocumentReplica {

    private final UUID documentId;
    private final RgaDocument document;
    private final ReentrantLock lock = new ReentrantLock();

    /** Najwyższy {@code server_seq} zastosowany do tej repliki — kursor dla anti-entropy. */
    private volatile long appliedServerSeq;

    /** Operacje przyjęte, ale jeszcze niezapisane. Wsad opróżniany co {@code opBatchWindowMs}. */
    private final List<PendingOperation> unwritten = new ArrayList<>();

    /** Ile operacji przybyło od ostatniego snapshotu — próg z ADR-0003. */
    private int operationsSinceSnapshot;

    private volatile long lastActivityMillis;

    /**
     * Version vectory podłączonych klientów — podstawa wyliczenia stabilności przyczynowej
     * dla GC (ADR-0005). Klucz to identyfikator repliki klienta.
     */
    private final Map<Integer, ReplicaProgress> replicaProgress = new ConcurrentHashMap<>();

    public record ReplicaProgress(VersionVector version, long reportedAtMillis) {
    }

    public DocumentReplica(UUID documentId, RgaDocument document, long appliedServerSeq, long nowMillis) {
        this.documentId = documentId;
        this.document = document;
        this.appliedServerSeq = appliedServerSeq;
        this.lastActivityMillis = nowMillis;
    }

    public UUID documentId() {
        return documentId;
    }

    public long appliedServerSeq() {
        return appliedServerSeq;
    }

    public long lastActivityMillis() {
        return lastActivityMillis;
    }

    /**
     * Wykonuje operację pod blokadą dokumentu.
     *
     * <p>Wszystkie ścieżki dotykające {@link RgaDocument} przechodzą tędy — również odczyt
     * tekstu, bo odczyt w trakcie modyfikacji struktury dałby wynik z rozjechanym stanem.
     */
    public <T> T withLock(java.util.function.Function<RgaDocument, T> action) {
        lock.lock();
        try {
            return action.apply(document);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Scala operację i zapamiętuje ją do zapisu.
     *
     * @return {@code true} gdy operacja wniosła coś nowego; {@code false} gdy była duplikatem
     *         i nie ma czego zapisywać ani rozsyłać
     */
    public boolean merge(Op op, String userId, long nowMillis) {
        lock.lock();
        try {
            RgaDocument.ApplyResult result = document.apply(op);
            lastActivityMillis = nowMillis;
            if (result.isNews()) {
                // Do zapisu trafiają również operacje zbuforowane (BUFFERED). Są kompletne
                // i poprawne — brakuje im tylko przyczyny, która dotrze później. Odkładanie
                // ich zapisu do czasu integracji oznaczałoby, że pad instancji gubi operację,
                // którą już przyjęliśmy od klienta.
                unwritten.add(new PendingOperation(op, userId));
                operationsSinceSnapshot++;
            }
            return result.isNews();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Dopisuje do wsadu operację, która została już scalona (przywracanie wersji, CP5).
     * Pomija scalanie, bo operacja powstała na tej właśnie replice.
     */
    public void enqueueForWrite(Op op, String userId, long nowMillis) {
        lock.lock();
        try {
            unwritten.add(new PendingOperation(op, userId));
            operationsSinceSnapshot++;
            lastActivityMillis = nowMillis;
        } finally {
            lock.unlock();
        }
    }

    /** Wyjmuje wsad do zapisu i czyści bufor. */
    public List<PendingOperation> drainUnwritten() {
        lock.lock();
        try {
            if (unwritten.isEmpty()) {
                return List.of();
            }
            List<PendingOperation> batch = List.copyOf(unwritten);
            unwritten.clear();
            return batch;
        } finally {
            lock.unlock();
        }
    }

    public int unwrittenCount() {
        lock.lock();
        try {
            return unwritten.size();
        } finally {
            lock.unlock();
        }
    }

    public void advanceServerSeq(long serverSeq) {
        if (serverSeq > appliedServerSeq) {
            appliedServerSeq = serverSeq;
        }
    }

    public boolean snapshotDue(int threshold) {
        return operationsSinceSnapshot >= threshold;
    }

    public void snapshotTaken() {
        operationsSinceSnapshot = 0;
    }

    public int operationsSinceSnapshot() {
        return operationsSinceSnapshot;
    }

    // ------------------------------------------------------------------
    // Stabilność przyczynowa dla GC (ADR-0005)
    // ------------------------------------------------------------------

    public void reportProgress(int replicaId, VersionVector version, long nowMillis) {
        replicaProgress.put(replicaId, new ReplicaProgress(version, nowMillis));
    }

    public void forgetReplica(int replicaId) {
        replicaProgress.remove(replicaId);
    }

    public Map<Integer, ReplicaProgress> replicaProgress() {
        return Map.copyOf(replicaProgress);
    }

    /**
     * Granica, poniżej której tombstone'y można fizycznie usunąć.
     *
     * <p>Jest to element wspólny version vectorów wszystkich <b>reagujących</b> replik.
     * Replika, która nie zgłosiła postępu przez {@code unresponsiveTimeoutMs} mimo aktywnego
     * połączenia, jest z tego rachunku wykluczana — inaczej jeden zawieszony klient
     * blokowałby GC całemu dokumentowi (ADR-0005, sekcja konsekwencji negatywnych).
     */
    public VersionVector stableVersion(long nowMillis, long unresponsiveTimeoutMs) {
        List<VersionVector> responsive = new ArrayList<>();
        for (ReplicaProgress progress : replicaProgress.values()) {
            if (nowMillis - progress.reportedAtMillis() <= unresponsiveTimeoutMs) {
                responsive.add(progress.version());
            }
        }
        if (responsive.isEmpty()) {
            return VersionVector.empty();
        }
        // Stan trwały też jest repliką: nie wolno skasować tombstone'u, którego nie widział
        // jeszcze zapis do bazy.
        responsive.add(withLock(RgaDocument::version));
        return VersionVector.meet(responsive);
    }
}
