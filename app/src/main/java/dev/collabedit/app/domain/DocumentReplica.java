package dev.collabedit.app.domain;

import dev.collabedit.app.persist.PendingOperation;
import dev.collabedit.crdt.Id;
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

    /**
     * Operacje scalone, ale jeszcze nierozgłoszone. Opróżniane co {@code broadcastWindowMs}.
     *
     * <p>Osobna lista od {@link #unwritten}, bo <b>oba wsady mają inne rytmy i inne cele</b>:
     * rozgłoszenie odpowiada za to, co użytkownik widzi (szybko, 10 ms), a zapis za trwałość
     * (rzadziej, 50 ms, bo dotyka dysku i blokady wiersza). Jedna wspólna lista wymusiłaby
     * wspólne tempo, czyli albo wolniejsze rozgłaszanie, albo częstsze transakcje.
     */
    private final List<PendingOperation> pendingBroadcast = new ArrayList<>();

    /** Ile operacji przybyło od ostatniego snapshotu — próg z ADR-0003. */
    private int operationsSinceSnapshot;

    private volatile long lastActivityMillis;

    /**
     * Granica, poniżej której tombstone'y zostały już fizycznie usunięte.
     *
     * <p>Bez zapamiętania tej wartości nie da się odróżnić dwóch sytuacji, które z zewnątrz
     * wyglądają identycznie: operacja przyszła przed swoją przyczyną (poczeka w buforze,
     * normalne) od operacji, której przyczyna została skasowana przez GC (nie doczeka się
     * nigdy — ADR-0005). Pierwsza wymaga cierpliwości, druga jawnej odmowy.
     */
    private volatile VersionVector gcWatermark = VersionVector.empty();

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
    public boolean merge(Op op, String userId, String traceparent, long nowMillis) {
        lock.lock();
        try {
            RgaDocument.ApplyResult result = document.apply(op);
            lastActivityMillis = nowMillis;
            if (result.isNews()) {
                // Do zapisu trafiają również operacje zbuforowane (BUFFERED). Są kompletne
                // i poprawne — brakuje im tylko przyczyny, która dotrze później. Odkładanie
                // ich zapisu do czasu integracji oznaczałoby, że pad instancji gubi operację,
                // którą już przyjęliśmy od klienta.
                PendingOperation pending = new PendingOperation(op, userId, traceparent);
                unwritten.add(pending);
                pendingBroadcast.add(pending);
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
            PendingOperation pending = new PendingOperation(op, userId, null);
            unwritten.add(pending);
            pendingBroadcast.add(pending);
            operationsSinceSnapshot++;
            lastActivityMillis = nowMillis;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Dopisuje do rozgłoszenia operację, której NIE zapisujemy — bo jest już w logu.
     *
     * <p>Ścieżka operacji przyjętych z klastra i dociągniętych przy naprawie luki (ADR-0004).
     * Trafiły do bazy na innej instancji, więc ponowny zapis byłby duplikatem, ale klienci
     * podłączeni tutaj muszą je zobaczyć.
     */
    public void enqueueForBroadcast(Op op, long nowMillis) {
        lock.lock();
        try {
            pendingBroadcast.add(new PendingOperation(op, null, null));
            lastActivityMillis = nowMillis;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Wyjmuje wsad do zapisu, najwyżej {@code limit} operacji.
     *
     * <p>Górna granica nie jest ozdobna. Bez niej jedna transakcja może objąć dowolnie wiele
     * operacji — a {@link dev.collabedit.app.persist.OperationStore#append} trzyma przy tym
     * blokadę wiersza dokumentu, czyli blokuje <b>wszystkich piszących do tego dokumentu,
     * również z innych instancji</b>. Wsad ograniczony to ograniczony czas trzymania blokady.
     *
     * <p>Reszta zostaje w buforze i wyjdzie w następnym oknie, czyli za
     * {@code op-batch-window-ms}. Opóźnienie jest tu właściwym zachowaniem: przy nagłym
     * zalewie operacji wolimy stały, przewidywalny rytm zapisu niż jedną wielką transakcję,
     * która na czas swojego trwania zatrzymuje dokument.
     */
    public List<PendingOperation> drainUnwritten(int limit) {
        lock.lock();
        try {
            if (unwritten.isEmpty()) {
                return List.of();
            }
            if (unwritten.size() <= limit) {
                List<PendingOperation> batch = List.copyOf(unwritten);
                unwritten.clear();
                return batch;
            }
            List<PendingOperation> batch = List.copyOf(unwritten.subList(0, limit));
            unwritten.subList(0, limit).clear();
            return batch;
        } finally {
            lock.unlock();
        }
    }

    /** Wyjmuje wszystko, co czeka na rozgłoszenie. Bez limitu — patrz {@link OutboundBatch}. */
    public List<PendingOperation> drainBroadcast() {
        lock.lock();
        try {
            if (pendingBroadcast.isEmpty()) {
                return List.of();
            }
            List<PendingOperation> batch = List.copyOf(pendingBroadcast);
            pendingBroadcast.clear();
            return batch;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Wsad wychodzący: co rozgłosić i co zapisać, wyjęte <b>jednym zajęciem blokady</b>.
     *
     * <p>Atomowość nie jest tu ozdobą, tylko warunkiem poprawności. Klient wykrywa zgubione
     * rozgłoszenia po tym, że dostał POTWIERDZENIE operacji, której u siebie nie ma
     * (patrz {@code connection.ts}). Gdyby oba bufory opróżniać osobno, operacja mogła
     * wejść między jedno a drugie: trafiłaby do wsadu zapisu, dostała potwierdzenie —
     * a rozgłoszenie czekałoby jeszcze jedno okno. Klient uznałby to za lukę i poprosił
     * o pełną resynchronizację. Mechanizm ratunkowy odpalałby się wtedy regularnie
     * przy całkowicie zdrowym systemie.
     *
     * <p>Rozgłoszenie nie ma limitu rozmiaru, a zapis ma. To celowe: limit zapisu chroni
     * czas trzymania blokady wiersza w bazie, a rozgłoszenie żadnej blokady nie trzyma.
     * Ograniczenie go oznaczałoby tylko odsunięcie w czasie tego, co użytkownik ma zobaczyć.
     *
     * @param writeLimit górna granica liczby operacji w wsadzie zapisu
     */
    public OutboundBatch drainForFlush(int writeLimit) {
        lock.lock();
        try {
            // Blokada jest reentrantna, a trzymanie jej z zewnątrz gwarantuje, że między
            // tymi dwoma wyjęciami nikt nie dołoży operacji.
            return new OutboundBatch(drainBroadcast(), drainUnwritten(writeLimit));
        } finally {
            lock.unlock();
        }
    }

    /**
     * @param broadcast operacje do rozesłania klientom tej instancji
     * @param writes    operacje do dopisania do logu operacji
     */
    public record OutboundBatch(List<PendingOperation> broadcast, List<PendingOperation> writes) {
    }

    /**
     * Czy replika ma cokolwiek do wysłania albo zapisania — sprawdzane <b>bez blokady</b>.
     *
     * <p>Pętla {@code flushAll} chodzi co 50 ms po wszystkich załadowanych dokumentach.
     * Przy jednym dokumencie zajęcie blokady było niezauważalne; przy pięciuset to dziesięć
     * tysięcy zajęć blokady na sekundę, z czego w normalnym ruchu prawie wszystkie
     * na pustych buforach.
     *
     * <p>Odczyt bez blokady jest tu <b>bezpieczny mimo wyścigu</b>, a nie dlatego, że wyścigu
     * nie ma. Możliwe pomyłki są dwie i obie są nieszkodliwe:
     * <ul>
     *   <li>fałszywie POZYTYWNA — wejdziemy pod blokadę i zastaniemy pusto; kosztuje jedno
     *       niepotrzebne zajęcie blokady,</li>
     *   <li>fałszywie NEGATYWNA — operacja dodana dokładnie w trakcie sprawdzania poczeka
     *       do następnego okna, czyli 50 ms. Nie ginie: bufory są opróżniane w całości,
     *       a nie przyrostowo.</li>
     * </ul>
     * Rozmiary są odczytywane z {@code ArrayList} bez synchronizacji — w najgorszym razie
     * zobaczymy nieaktualną wartość, a nie uszkodzoną, bo {@code size} to zwykły {@code int}.
     */
    public boolean hasPendingWork() {
        return !unwritten.isEmpty() || !pendingBroadcast.isEmpty();
    }

    /**
     * Czy jest cokolwiek do rozgłoszenia — sprawdzenie bez blokady, jw.
     *
     * <p>Osobno od {@link #hasPendingWork()}, bo pętla rozgłaszania chodzi <b>pięć razy
     * częściej</b> niż zapisująca (10 ms wobec 50 ms) i interesuje ją wyłącznie jeden
     * z dwóch buforów. Użycie szerszego warunku zajmowałoby blokadę dokumentu, który ma
     * coś do zapisania, ale nic do rozesłania — czyli w większości przebiegów.
     */
    public boolean hasPendingBroadcast() {
        return !pendingBroadcast.isEmpty();
    }

    public int unwrittenCount() {
        lock.lock();
        try {
            return unwritten.size();
        } finally {
            lock.unlock();
        }
    }

    public int pendingBroadcastCount() {
        lock.lock();
        try {
            return pendingBroadcast.size();
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

    public void recordGc(VersionVector stable) {
        this.gcWatermark = stable;
    }

    public VersionVector gcWatermark() {
        return gcWatermark;
    }

    /**
     * Czy operacja jest trwale nieosadzalna, bo jej punkt zaczepienia został usunięty przez GC.
     *
     * <p>To jest sytuacja z ADR-0005: klient był offline dłużej niż okno GC i wraca
     * z operacją wskazującą na tombstone, którego już nie ma. Informacja o pozycji tych
     * znaków została bezpowrotnie utracona — <b>nie da się tego uczciwie scalić</b>.
     *
     * <p>Warunek jest celowo wąski: origin musi być JEDNOCZEŚNIE nieobecny i poniżej granicy
     * GC. Sama nieobecność nic nie znaczy (operacja mogła wyprzedzić swoją przyczynę),
     * a sama pozycja poniżej granicy też nie (znak mógł nadal żyć, bo GC usuwa wyłącznie
     * tombstone'y). Zbyt szeroki warunek odrzucałby poprawne operacje — czyli gubiłby
     * pracę użytkownika w imię ochrony przed jej gubieniem.
     */
    public boolean isPermanentlyUnanchored(Op op) {
        if (!(op instanceof Op.Insert insert) || insert.originLeft() == null) {
            return false;
        }
        Id origin = insert.originLeft();
        return withLock(document -> !document.contains(origin))
            && origin.lamport() <= gcWatermark.get(origin.replicaId());
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
