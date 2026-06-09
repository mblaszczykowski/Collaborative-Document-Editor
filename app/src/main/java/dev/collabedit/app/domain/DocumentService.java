package dev.collabedit.app.domain;

import dev.collabedit.app.cluster.ClusterBus;
import dev.collabedit.app.config.CollabEditProperties;
import dev.collabedit.app.persist.DocumentCatalog;
import dev.collabedit.app.persist.OperationStore;
import dev.collabedit.app.persist.PendingOperation;
import dev.collabedit.app.persist.SnapshotStore;
import dev.collabedit.app.observability.DocumentMetrics;
import dev.collabedit.app.observability.EditTracer;
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
 *                  2. rozgłoszenie do własnych klientów  (wsadowo, co 10 ms)
 *                  3. zapis do logu operacji             (wsadowo, co 50 ms)
 *                  4. potwierdzenie z numerem server_seq (po zapisie)
 *                  5. publikacja do Redisa               (po zapisie, best-effort)
 * </pre>
 *
 * <p>Kolejność nie jest dowolna:
 * <ul>
 *   <li><b>Rozgłoszenie przed zapisem</b>, bo to ono odpowiada za to, co użytkownik widzi.
 *       Czekanie na dysk dodałoby opóźnienie do najbardziej zauważalnej rzeczy w systemie.
 *       Oba wsady są wyjmowane jednym zajęciem blokady i rozgłoszenie idzie pierwsze —
 *       potwierdzenie, które wyprzedzi rozgłoszenie, klient uzna za lukę i poprosi
 *       o pełną resynchronizację (patrz {@link DocumentReplica#drainForFlush}).</li>
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
    private final DocumentCatalog catalog;
    private final ClusterBus cluster;
    private final CollabEditProperties properties;
    private final DocumentMetrics metrics;
    private final EditTracer tracer;

    /** Wywoływane przy rozgłoszeniu operacji; podpinane przez warstwę WebSocket. */
    private volatile BiConsumer<UUID, List<StoredOperation>> onBroadcast = (id, ops) -> { };
    private volatile BiConsumer<UUID, List<Op>> onFastPath = (id, ops) -> { };

    public DocumentService(DocumentRegistry registry, OperationStore operations,
                           SnapshotStore snapshots, DocumentCatalog catalog, ClusterBus cluster,
                           CollabEditProperties properties, DocumentMetrics metrics,
                           EditTracer tracer) {
        this.registry = registry;
        this.operations = operations;
        this.snapshots = snapshots;
        this.catalog = catalog;
        this.cluster = cluster;
        this.properties = properties;
        this.metrics = metrics;
        this.tracer = tracer;
    }

    @PostConstruct
    void subscribeToCluster() {
        cluster.onMessage(this::onClusterMessage);
        // Odsiew po nazwie kanału, PRZED parsowaniem ładunku. Subskrypcja jest wzorcowa,
        // więc bez tego każda instancja czytałaby JSON całego ruchu klastra.
        cluster.interestedIn(registry::isLoaded);
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

    /**
     * Przyjmuje operację, która została już scalona do repliki serwera.
     *
     * <p>Używane przy przywracaniu wersji z historii (ADR-0003 pkt 6): operacje powstają
     * na replice serwera, więc są już częścią jej stanu i trzeba je tylko zapisać
     * i rozgłosić. Ponowne scalanie byłoby nieszkodliwe, ale mylące — z kodu ma wynikać,
     * że to nie jest ta sama ścieżka co operacja od klienta.
     */
    public void submitAlreadyMerged(UUID documentId, Op op, String userId) {
        DocumentReplica replica = registry.get(documentId);
        // Kolejkuje do zapisu ORAZ do rozgłoszenia — obie ścieżki wychodzą wsadowo.
        replica.enqueueForWrite(op, userId, System.currentTimeMillis());
    }

    /** Rzucane, gdy operacji nie da się już scalić, bo GC usunął jej punkt zaczepienia. */
    public static class ResyncRequiredException extends RuntimeException {
        public ResyncRequiredException(String message) {
            super(message);
        }
    }

    public void submit(UUID documentId, Op op, String userId) {
        DocumentReplica replica = registry.get(documentId);

        // ADR-0005: odmawiamy JAWNIE, zamiast doklejać tekst w przypadkowe miejsce
        // (cicha korupcja treści) albo go ignorować (cicha utrata pracy). Obie
        // alternatywy są gorsze niż uczciwe "nie potrafię tego scalić".
        if (replica.isPermanentlyUnanchored(op)) {
            metrics.recordStaleRejection();
            throw new ResyncRequiredException(
                "operacja " + op.id() + " wskazuje na znak usunięty przez garbage collection");
        }

        // Dwa osobne pomiary, bo odpowiadają na dwa różne pytania: ile trwa sam algorytm
        // (skaluje się z rozmiarem dokumentu) i ile trwa cała obsługa po stronie serwera
        // (obejmuje też rozesłanie). Jeden zbiorczy timer nie pozwoliłby ich rozdzielić,
        // a przy diagnozie wolnej propagacji to jest pierwsze pytanie.
        var wholeCall = metrics.startTimer();
        boolean isNews;
        // Kontekst pobrany TERAZ, dopóki jesteśmy w spanie przyjęcia operacji. Wsad zapisu
        // wykona się później, na innym wątku, gdzie tego kontekstu już nie będzie.
        String traceparent = tracer.currentTraceparent();
        var merging = metrics.startTimer();
        try (var span = tracer.startStage("crdt.merge")) {
            isNews = replica.merge(op, userId, traceparent, System.currentTimeMillis());
            span.attribute("collabedit.op.news", String.valueOf(isNews));
        }
        metrics.recordMerge(merging);

        if (!isNews) {
            // Duplikat — nie ma czego zapisywać ani rozsyłać. Zdarza się przy retransmisji
            // po ponownym połączeniu, więc jest sytuacją normalną, a nie błędem.
            return;
        }
        // Operacja jest już w buforze rozgłoszeniowym (dodał ją merge) i wyjdzie
        // w najbliższym oknie — patrz broadcastAll. Tu nie ma nic do zrobienia.
        metrics.recordServerLatency(wholeCall);
    }

    // ------------------------------------------------------------------
    // Krok 2: rozgłoszenie — wsadowo (ADR-0002, ADR-0004)
    // ------------------------------------------------------------------

    /**
     * Rozsyła zebrane operacje wszystkich dokumentów.
     *
     * <p><b>Dlaczego wsadowo, skoro ADR-0004 mówił „natychmiast".</b> Rozgłoszenie jedno-po-jednym
     * znaczy jedna ramka na naciśnięcie klawisza <b>razy liczba odbiorców</b>. Liczba ramek rośnie
     * więc z kwadratem liczby klientów, a każda niesie kilkadziesiąt bajtów treści w kopercie
     * STOMP porównywalnego rozmiaru. Przy 50 klientach kolejka wysyłkowa wolniejszego z nich
     * przekracza {@code send-buffer-size-bytes} i Spring zrywa mu sesję — co jest dokładnie
     * tym objawem, który README opisywał jako nierozstrzygnięty („część klientów trwale w tyle,
     * serwer ma komplet operacji").
     *
     * <p>Kosztem jest opóźnienie do {@code broadcast-window-ms}. Przy 10 ms jest ono
     * o rząd wielkości poniżej progu zauważalności w pisaniu i o rząd wielkości poniżej
     * zmierzonych latencji propagacji (22–553 ms), więc kompromis nie jest bliski.
     */
    @Scheduled(fixedDelayString = "${collabedit.websocket.broadcast-window-ms:10}")
    public void broadcastAll() {
        for (DocumentReplica replica : registry.loaded()) {
            try {
                broadcast(replica.documentId(), replica.drainBroadcast());
            } catch (RuntimeException e) {
                log.error("Nie udało się rozgłosić operacji dokumentu {}", replica.documentId(), e);
            }
        }
    }

    private void broadcast(UUID documentId, List<PendingOperation> batch) {
        if (batch.isEmpty()) {
            return;
        }
        // Kontekst śledzenia wiążemy LINKAMI, nie zagnieżdżeniem — tak samo jak przy zapisie.
        // Wsad obejmuje operacje wielu użytkowników i wykonuje się na wątku schedulera,
        // więc zagnieżdżenie pokazywałoby każdemu z nich czas cudzych operacji.
        try (var span = tracer.startBatch("op.broadcast.local",
            batch.stream().map(PendingOperation::traceparent).toList())) {
            span.attribute("collabedit.document", documentId.toString());
            span.attribute("collabedit.batch.size", batch.size());
            onFastPath.accept(documentId, batch.stream().map(PendingOperation::op).toList());
        }
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
        long now = System.currentTimeMillis();
        for (DocumentReplica replica : registry.loaded()) {
            try {
                // Bezblokadowe sprawdzenie, czy jest co robić. Przy wielu otwartych
                // dokumentach większość przebiegów tej pętli trafia na puste bufory,
                // a samo zajęcie blokady kosztuje więcej niż odczyt dwóch rozmiarów.
                if (replica.hasPendingWork()) {
                    flush(replica);
                }
                snapshotIfQuiet(replica, now);
            } catch (RuntimeException e) {
                log.error("Nie udało się zapisać wsadu dla dokumentu {}", replica.documentId(), e);
            }
        }
    }

    /**
     * Snapshot dokumentu, który ucichł (ADR-0003 pkt 4).
     *
     * <p>Próg liczby operacji łapie dokumenty <b>intensywnie edytowane</b>. Nie łapie tego,
     * który dostał sto zmian i zamilkł — a to jest właśnie przypadek, w którym snapshot ma
     * największą wartość: dokument bez klientów zostanie za chwilę zwolniony z pamięci
     * ({@code idle-document-eviction-ms}) i przy następnym otwarciu odtwarzany z logu.
     * Bez tego ogon do odtworzenia rośnie z każdą sesją edycji.
     *
     * <p>Sprawdzane w pętli {@code flushAll}, a nie osobnym {@code @Scheduled}: ta pętla i tak
     * przechodzi po załadowanych dokumentach, więc porównanie dwóch liczb jest tu darmowe,
     * a próg działa z dokładnością do okna wsadowania zamiast do własnego interwału.
     */
    private void snapshotIfQuiet(DocumentReplica replica, long nowMillis) {
        if (replica.operationsSinceSnapshot() == 0) {
            return;
        }
        if (nowMillis - replica.lastActivityMillis() < properties.persistence().snapshotAfterIdleMs()) {
            return;
        }
        // Niezapisane operacje mają pierwszeństwo: snapshot ponad stanem, którego nie ma
        // jeszcze w logu, zapisywałby się z numerem server_seq sprzed tych operacji
        // i przy odtwarzaniu zostałyby one zastosowane drugi raz. Byłoby to nieszkodliwe
        // (idempotencja), ale snapshot przestałby odpowiadać swojemu numerowi.
        if (replica.unwrittenCount() > 0) {
            return;
        }
        log.debug("Dokument {} ucichł — snapshot {} operacji po ostatnim",
            replica.documentId(), replica.operationsSinceSnapshot());
        takeSnapshot(replica);
    }

    private void flush(DocumentReplica replica) {
        // Oba bufory wyjmowane RAZEM, jednym zajęciem blokady, i rozgłoszenie idzie PIERWSZE.
        // Powód w DocumentReplica.drainForFlush: potwierdzenie zapisu, które wyprzedzi
        // rozgłoszenie, klient interpretuje jako lukę i prosi o pełną resynchronizację.
        DocumentReplica.OutboundBatch outbound =
            replica.drainForFlush(properties.persistence().opBatchMaxSize());
        broadcast(replica.documentId(), outbound.broadcast());

        List<PendingOperation> batch = outbound.writes();
        if (batch.isEmpty()) {
            return;
        }

        var persisting = metrics.startTimer();
        List<StoredOperation> appended;
        try (var span = tracer.startBatch("op.persist",
            batch.stream().map(PendingOperation::traceparent).toList())) {
            appended = operations.append(replica.documentId(), batch);
            span.attribute("collabedit.batch.size", batch.size());
            span.attribute("collabedit.batch.written", appended.size());
        }
        metrics.recordPersist(persisting);
        if (appended.isEmpty()) {
            return;
        }

        long highest = appended.getLast().serverSeq();
        replica.advanceServerSeq(highest);

        onBroadcast.accept(replica.documentId(), appended);
        cluster.publish(replica.documentId(), appended);

        maybeSnapshot(replica);
    }

    /** Snapshot po przekroczeniu progu liczby operacji — ścieżka dokumentu aktywnego. */
    private void maybeSnapshot(DocumentReplica replica) {
        if (!replica.snapshotDue(properties.persistence().snapshotEveryNOps())) {
            return;
        }
        takeSnapshot(replica);
    }

    private void takeSnapshot(DocumentReplica replica) {
        // GC tombstone'ów dzieje się PRZY snapshotowaniu, a nie jako osobny proces (ADR-0005):
        // snapshot i tak przepisuje całą strukturę, więc kompakcja jest w tym miejscu darmowa.
        VersionVector stable = replica.stableVersion(
            System.currentTimeMillis(), properties.gc().unresponsiveReplicaTimeoutMs());

        long seq = replica.appliedServerSeq();
        // Podgląd zdejmowany pod TĄ SAMĄ blokadą co snapshot, żeby oba opisywały ten sam stan.
        // Osobne wejście pod blokadę dałoby podgląd z innej chwili niż snapshot — niegroźne,
        // ale wtedy „lista pokazuje co innego niż dokument" przestaje mieć jedno wyjaśnienie.
        var captured = replica.withLock(document -> {
            int removed = stable.isEmpty() ? 0 : document.gc(stable);
            if (removed > 0) {
                metrics.recordGc(removed);
                log.debug("GC dokumentu {}: usunięto {} znaków tombstone'ów", replica.documentId(), removed);
            }
            String preview = document.visibleLength() <= DocumentCatalog.PREVIEW_LENGTH
                ? document.text()
                : document.text().substring(0, DocumentCatalog.PREVIEW_LENGTH);
            return new Captured(document.snapshot(), preview);
        });

        snapshots.save(replica.documentId(), seq, captured.snapshot());
        // Podgląd dla listy dokumentów. Materializowany TUTAJ, bo to jedyny moment, w którym
        // treść jest już w ręku — lista nie może sobie pozwolić na ładowanie repliki
        // dla każdej pozycji (CP11).
        catalog.updatePreview(replica.documentId(), captured.preview());
        replica.snapshotTaken();
        if (!stable.isEmpty()) {
            replica.recordGc(stable);
        }
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

        applyRemote(replica, message.operations());
        replica.advanceServerSeq(message.operations().getLast().serverSeq());
    }

    /**
     * Scala operacje pochodzące z klastra albo z naprawy luki i kolejkuje je do rozgłoszenia.
     *
     * @return ile z nich było nowością dla tej repliki
     */
    private int applyRemote(DocumentReplica replica, List<StoredOperation> incoming) {
        long now = System.currentTimeMillis();
        int applied = 0;
        for (StoredOperation stored : incoming) {
            // Operacje z klastra są już w logu — scalamy je do repliki w pamięci,
            // ale NIE dopisujemy ponownie. Stąd bezpośrednie wejście w RgaDocument
            // zamiast DocumentReplica.merge, które kolejkowałoby je do zapisu.
            RgaDocument.ApplyResult result =
                replica.withLock(document -> document.apply(stored.op()));
            if (result.isNews()) {
                replica.enqueueForBroadcast(stored.op(), now);
                applied++;
            }
        }
        return applied;
    }

    private void repairFrom(DocumentReplica replica, long fromServerSeq) {
        List<StoredOperation> missing = operations.loadAfter(replica.documentId(), fromServerSeq);
        metrics.recordGapRepair(applyRemote(replica, missing));
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

    /** Stan zdjęty pod jedną blokadą: migawka do zapisu i podgląd do listy dokumentów. */
    private record Captured(dev.collabedit.crdt.DocumentSnapshot snapshot, String preview) {
    }

    /** Liczba operacji znacznikowych w replice — do diagnostyki i testów (CP13). */
    public int markCountOf(UUID documentId) {
        return registry.get(documentId).withLock(RgaDocument::markCount);
    }

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
