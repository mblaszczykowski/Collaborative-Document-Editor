package dev.collabedit.app.ws;

import com.fasterxml.jackson.databind.JsonNode;
import dev.collabedit.app.domain.DocumentRegistry;
import dev.collabedit.app.domain.DocumentService;
import dev.collabedit.app.observability.EditTracer;
import dev.collabedit.app.persist.StoredOperation;
import dev.collabedit.crdt.Op;
import dev.collabedit.crdt.OpCodec;
import dev.collabedit.crdt.VersionVector;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

/**
 * Punkt wejścia operacji od klientów.
 *
 * <p>Kontroler jest celowo cienki — cała logika jest w {@link DocumentService}. Powód
 * praktyczny: ścieżka zapisu z ADR-0004 ma pięć kroków o istotnej kolejności, a rozproszenie
 * ich między adnotowane metody kontrolera sprawiłoby, że ta kolejność przestałaby być
 * widoczna w jednym miejscu.
 */
@Controller
public class DocumentStompController {

    private static final Logger log = LoggerFactory.getLogger(DocumentStompController.class);

    private final DocumentService documents;
    private final DocumentRegistry registry;
    private final PresenceService presence;
    private final SimpMessagingTemplate messaging;
    private final EditTracer tracer;

    public DocumentStompController(DocumentService documents, DocumentRegistry registry,
                                   PresenceService presence, SimpMessagingTemplate messaging,
                                   EditTracer tracer) {
        this.documents = documents;
        this.registry = registry;
        this.presence = presence;
        this.messaging = messaging;
        this.tracer = tracer;
    }

    /**
     * Operacja od klienta.
     *
     * <p>Odrzucenie z kodem {@code RESYNC_REQUIRED} to sytuacja z ADR-0005: klient był offline
     * dłużej niż okno GC i jego operacja wskazuje na tombstone, którego już nie ma. Informacja
     * o pozycji tych znaków została bezpowrotnie utracona, więc <b>nie da się tego uczciwie
     * scalić</b>. Doklejenie tekstu na koniec dokumentu byłoby cichą korupcją treści,
     * a zignorowanie — cichą utratą pracy. Odmawiamy jawnie.
     */
    @MessageMapping("/doc/{documentId}/op")
    public void onOperation(@DestinationVariable("documentId") UUID documentId,
                            Messages.ClientOp message,
                            SimpMessageHeaderAccessor headers) {
        Op op;
        try {
            op = OpCodec.fromJson(message.op());
        } catch (RuntimeException e) {
            sendError(headers, "BAD_OPERATION", "nie da się odczytać operacji: " + e.getMessage());
            return;
        }

        String userId = SessionAttributes.userId(headers);
        // Span otwierany TUTAJ, a nie w warstwie domenowej: to jest punkt, w którym operacja
        // wchodzi do systemu i w którym jest jeszcze dostęp do nagłówków ramki z kontekstem
        // śledzenia przysłanym przez przeglądarkę.
        try (var span = tracer.startReceive(headers, documentId,
            SessionAttributes.replicaId(headers), op instanceof Op.Insert ? "insert" : "delete")) {
            span.attribute("collabedit.op.lamport", op.id().lamport());
            documents.submit(documentId, op, userId);
        } catch (DocumentService.ResyncRequiredException e) {
            log.info("Odrzucono operację {} w dokumencie {}: {}", op.id(), documentId, e.getMessage());
            sendError(headers, "RESYNC_REQUIRED", e.getMessage());
        } catch (RuntimeException e) {
            log.warn("Odrzucono operację {} w dokumencie {}: {}", op.id(), documentId, e.toString());
            sendError(headers, "OPERATION_REJECTED", e.getMessage());
        }
    }

    /**
     * Prośba o brakujące operacje po ponownym połączeniu.
     *
     * <p>Klient podaje ostatni numer, który na pewno ma. Dosłanie mu operacji, które już
     * widział, jest nieszkodliwe (idempotencja z ADR-0001), więc kursor może być ostrożny —
     * lepiej dostać kilka duplikatów niż zgubić jedną operację.
     */
    @MessageMapping("/doc/{documentId}/sync")
    public void onSyncRequest(@DestinationVariable("documentId") UUID documentId,
                              Messages.SyncRequest request,
                              SimpMessageHeaderAccessor headers) {
        List<StoredOperation> missing = documents.operationsSince(documentId, request.sinceServerSeq());

        // Zgłoszony version vector klienta służy WYŁĄCZNIE do wyliczenia stabilności
        // przyczynowej dla GC (ADR-0005). Wybór operacji do dosłania opiera się na
        // server_seq — to są dwa różne porządki i mieszanie ich jest klasycznym błędem.
        if (request.version() != null && !request.version().isEmpty()) {
            int replicaId = SessionAttributes.replicaId(headers);
            registry.get(documentId).reportProgress(
                replicaId, VersionVector.of(request.version()), System.currentTimeMillis());
        }

        sendSync(headers, documentId, missing);
    }

    /**
     * Dołączenie do dokumentu.
     *
     * <p>Osobny komunikat zamiast reakcji na {@code SUBSCRIBE}, bo klient subskrybuje kilka
     * tematów jednego dokumentu (operacje, potwierdzenia, presence) i reagowanie na
     * subskrypcję wymagałoby zgadywania, która z nich oznacza „jestem tu".
     */
    @MessageMapping("/doc/{documentId}/join")
    public void onJoin(@DestinationVariable("documentId") UUID documentId, SimpMessageHeaderAccessor headers) {
        // Załadowanie repliki jest tu KONIECZNE, nie optymalizacyjne.
        //
        // Instancja odrzuca wiadomości klastrowe dotyczące dokumentów, których nie trzyma
        // w pamięci (ADR-0004 — nie ma powodu mnożyć pamięci dla dokumentu bez klientów).
        // Bez tej linii instancja, do której klient tylko DOŁĄCZYŁ, nigdy nie ładowała
        // repliki, więc konsekwentnie ignorowała wszystkie operacje z klastra i klient
        // nie widział ani jednej cudzej zmiany.
        //
        // Błąd był niewidoczny dla wszystkich wcześniejszych testów, bo każdy z nich miał
        // wszystkich klientów na JEDNEJ instancji. Znalazł go dopiero test chaosu
        // z dwoma instancjami (ClusterChaosIT).
        registry.get(documentId);

        messaging.convertAndSendToUser(headers.getSessionId(), "/queue/session",
            new Messages.SessionInfo(
                SessionAttributes.replicaId(headers), SessionAttributes.userId(headers)),
            SessionAttributes.forUser(headers));

        presence.join(
            headers.getSessionId(),
            documentId,
            SessionAttributes.replicaId(headers),
            SessionAttributes.userId(headers),
            SessionAttributes.displayName(headers),
            SessionAttributes.color(headers));
    }

    /**
     * Okresowy raport postępu repliki.
     *
     * <p>Istnieje wyłącznie po to, żeby GC tombstone'ów mógł się w ogóle odbyć (ADR-0005).
     * Stabilność przyczynowa to element wspólny version vectorów wszystkich żywych replik —
     * bez regularnych raportów serwer zna wyłącznie stan sprzed ostatniej resynchronizacji
     * i granica GC stoi w miejscu, przez co tombstone'y rosną w nieskończoność.
     *
     * <p>Osobny kanał zamiast doklejania tego do resynchronizacji: resynchronizacja odpytuje
     * bazę o brakujące operacje, a raport postępu ma być tani i częsty.
     */
    @MessageMapping("/doc/{documentId}/progress")
    public void onProgress(@DestinationVariable("documentId") UUID documentId,
                           Messages.SyncRequest report,
                           SimpMessageHeaderAccessor headers) {
        if (report.version() == null || report.version().isEmpty()) {
            return;
        }
        registry.get(documentId).reportProgress(
            SessionAttributes.replicaId(headers),
            VersionVector.of(report.version()),
            System.currentTimeMillis());

        // UWAGA: NIE dosyłamy tu operacji na podstawie zgłoszonego kursora.
        //
        // Kusiło, żeby porównać `sinceServerSeq` klienta z czołem logu i dopchnąć różnicę —
        // ale kursor klienta rośnie od POTWIERDZEŃ, które są rozgłaszane wszystkim. Klient,
        // który zgubił rozgłoszenie operacji, ale dostał jej potwierdzenie, zgłosiłby więc
        // kursor „na bieżąco" mimo brakującej operacji. Mechanizm wyglądałby na działający
        // i nie robiłby nic.
        //
        // Wykrywanie luk jest po stronie klienta i opiera się na tym, że potwierdzenie niesie
        // TOŻSAMOŚĆ operacji: klient sprawdza, czy ma u siebie operację, której potwierdzenie
        // właśnie dostał (patrz connection.ts). Jeśli nie — sam prosi o resynchronizację.
    }

    /**
     * Ile operacji mieści się w jednej odpowiedzi na resynchronizację.
     *
     * <p>ADR-0002 ustala limit 64 KiB na wiadomość. Operacja to rzędu 60–100 bajtów, więc
     * 300 operacji daje ramkę bezpiecznie poniżej limitu nawet przy wstawianiu dłuższych
     * fragmentów tekstu.
     */
    private static final int SYNC_CHUNK = 300;

    /**
     * Odsyła brakujące operacje — <b>w porcjach</b>.
     *
     * <p>Pierwsza wersja wysyłała je w jednej wiadomości i był to realny błąd, wykryty
     * dopiero harnessem obciążeniowym (CP8): odpowiedź dla dokumentu o 100 tys. znaków
     * przekraczała limit rozmiaru ramki z ADR-0002, więc <b>klient, który najbardziej
     * potrzebował danych, nie mógł ich dostać</b>. Objawem było trwałe zatrzymanie się
     * części klientów kilka operacji poniżej stanu serwera — bez żadnego błędu w logu.
     *
     * <p>Kolejność porcji nie ma znaczenia dla poprawności (scalanie jest przemienne),
     * a porcja zgubiona po drodze zostanie wykryta tym samym mechanizmem co pierwotna luka.
     */
    private void sendSync(SimpMessageHeaderAccessor headers, UUID documentId,
                          List<StoredOperation> operations) {
        long head = documents.headServerSeq(documentId);
        if (operations.isEmpty()) {
            messaging.convertAndSendToUser(headers.getSessionId(), "/queue/sync",
                new Messages.SyncResponse(List.of(), head), SessionAttributes.forUser(headers));
            return;
        }
        for (int from = 0; from < operations.size(); from += SYNC_CHUNK) {
            List<JsonNode> chunk =
                operations.subList(from, Math.min(from + SYNC_CHUNK, operations.size())).stream()
                    .map(stored -> (JsonNode) OpCodec.toJson(stored.op()))
                    .toList();
            messaging.convertAndSendToUser(headers.getSessionId(), "/queue/sync",
                new Messages.SyncResponse(chunk, head), SessionAttributes.forUser(headers));
        }
    }

    @MessageMapping("/doc/{documentId}/presence")
    public void onPresence(@DestinationVariable("documentId") UUID documentId,
                           Messages.ClientPresence update,
                           SimpMessageHeaderAccessor headers) {
        presence.updateCursor(headers.getSessionId(), update);
    }

    private void sendError(SimpMessageHeaderAccessor headers, String code, String message) {
        messaging.convertAndSendToUser(headers.getSessionId(), "/queue/errors",
            new Messages.ErrorMessage(code, message), SessionAttributes.forUser(headers));
    }
}
