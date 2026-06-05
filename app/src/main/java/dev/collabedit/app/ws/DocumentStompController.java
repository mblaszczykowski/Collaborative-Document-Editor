package dev.collabedit.app.ws;

import dev.collabedit.app.domain.DocumentRegistry;
import dev.collabedit.app.domain.DocumentService;
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

    public DocumentStompController(DocumentService documents, DocumentRegistry registry,
                                   PresenceService presence, SimpMessagingTemplate messaging) {
        this.documents = documents;
        this.registry = registry;
        this.presence = presence;
        this.messaging = messaging;
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
        try {
            documents.submit(documentId, op, userId);
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
        List<com.fasterxml.jackson.databind.JsonNode> encoded =
            missing.stream().map(stored -> (com.fasterxml.jackson.databind.JsonNode)
                OpCodec.toJson(stored.op())).toList();

        // Zgłoszony version vector klienta służy WYŁĄCZNIE do wyliczenia stabilności
        // przyczynowej dla GC (ADR-0005). Wybór operacji do dosłania opiera się na
        // server_seq — to są dwa różne porządki i mieszanie ich jest klasycznym błędem.
        if (request.version() != null && !request.version().isEmpty()) {
            int replicaId = SessionAttributes.replicaId(headers);
            registry.get(documentId).reportProgress(
                replicaId, VersionVector.of(request.version()), System.currentTimeMillis());
        }

        messaging.convertAndSendToUser(headers.getSessionId(), "/queue/sync",
            new Messages.SyncResponse(encoded, documents.headServerSeq(documentId)),
            SessionAttributes.forUser(headers));
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
        presence.join(
            headers.getSessionId(),
            documentId,
            SessionAttributes.replicaId(headers),
            SessionAttributes.userId(headers),
            SessionAttributes.displayName(headers),
            SessionAttributes.color(headers));
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
