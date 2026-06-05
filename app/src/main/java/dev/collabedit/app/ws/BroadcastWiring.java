package dev.collabedit.app.ws;

import dev.collabedit.app.domain.DocumentService;
import dev.collabedit.crdt.OpCodec;
import jakarta.annotation.PostConstruct;
import java.util.List;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * Spina warstwę domenową z transportem.
 *
 * <p>Istnieje po to, żeby {@link DocumentService} — gdzie zapisana jest kolejność kroków
 * z ADR-0004 — nie zależał od {@code SimpMessagingTemplate}. Dzięki temu ścieżkę zapisu
 * da się przetestować bez podnoszenia całego stosu WebSocketa, a zmiana transportu
 * (gdyby ADR-0002 kiedyś został zrewidowany) dotyka jednego pliku zamiast serca logiki.
 */
@Component
public class BroadcastWiring {

    private final DocumentService documents;
    private final SimpMessagingTemplate messaging;

    public BroadcastWiring(DocumentService documents, SimpMessagingTemplate messaging) {
        this.documents = documents;
        this.messaging = messaging;
    }

    @PostConstruct
    void wire() {
        // Krok 2 z ADR-0004: rozgłoszenie natychmiast po scaleniu, PRZED zapisem.
        // To ono odpowiada za to, co użytkownik widzi.
        documents.onFastPath((documentId, ops) -> {
            List<Messages.OpBroadcast> payload = ops.stream()
                .map(op -> new Messages.OpBroadcast(OpCodec.toJson(op), null))
                .toList();
            messaging.convertAndSend("/topic/doc/" + documentId + "/ops", payload);
        });

        // Krok 4: potwierdzenie PO zapisie. Dopiero ono zwalnia operację z bufora offline
        // klienta i przesuwa jego kursor resynchronizacji.
        documents.onAcknowledged((documentId, stored) -> {
            List<Messages.OpAck> payload = stored.stream()
                .map(entry -> new Messages.OpAck(
                    entry.op().id().replicaId(),
                    entry.op().id().lamport(),
                    entry.serverSeq()))
                .toList();
            messaging.convertAndSend("/topic/doc/" + documentId + "/acks", payload);
        });
    }
}
