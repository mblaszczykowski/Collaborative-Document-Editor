package dev.collabedit.app.ws;

import dev.collabedit.app.domain.DocumentRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

/**
 * Kto jest w dokumencie, gdzie ma kursor i czy właśnie pisze.
 *
 * <p>Presence jest stanem <b>ulotnym</b> i celowo nie trafia do bazy: po restarcie instancji
 * klienci połączą się ponownie i zgłoszą swoją obecność sami. Zapisywanie go byłoby
 * przechowywaniem informacji, która dezaktualizuje się szybciej, niż zdąży zostać odczytana.
 *
 * <p>Pozycje kursorów są <b>identyfikatorami znaków CRDT</b>, nie offsetami (ADR-0006).
 * Gdyby kursor Bartka był „kolumną 42", wstawienie znaku przez Anię przed tą pozycją
 * przesunęłoby tekst, ale nie kursor — i Bartek pokazywałby cudzy znak. Identyfikator
 * przesuwa się razem z tekstem, bo jest przyczepiony do znaku, a nie do miejsca.
 *
 * <p>Ograniczenie wynikające z ADR-0004: ta mapa jest <b>lokalna dla instancji</b>. Klient
 * podłączony do innej instancji nie pojawi się na liście obecnych. Świadome uproszczenie —
 * pełne presence w klastrze wymagałoby osobnego kanału i wygasania wpisów, a lista obecnych
 * jest ozdobą, nie mechanizmem poprawności. Trafia do „co zrobiłbym inaczej w produkcji".
 */
@Service
public class PresenceService {

    /** sessionId → obecność. */
    private final Map<String, Participant> bySession = new ConcurrentHashMap<>();

    private final SimpMessagingTemplate messaging;
    private final DocumentRegistry registry;
    private final dev.collabedit.app.observability.DocumentMetrics metrics;

    public PresenceService(SimpMessagingTemplate messaging, DocumentRegistry registry,
                           dev.collabedit.app.observability.DocumentMetrics metrics) {
        this.messaging = messaging;
        this.registry = registry;
        this.metrics = metrics;
    }

    public record Participant(
        String sessionId,
        UUID documentId,
        int replicaId,
        String userId,
        String displayName,
        String color,
        Integer cursorReplicaId, Long cursorLamport,
        Integer anchorReplicaId, Long anchorLamport,
        boolean typing
    ) {
    }

    public void join(String sessionId, UUID documentId, int replicaId,
                     String userId, String displayName, String color) {
        if (bySession.put(sessionId, new Participant(sessionId, documentId, replicaId,
            userId, displayName, color, null, null, null, null, false)) == null) {
            metrics.sessionOpened();
        }
        broadcast(documentId);
    }

    public void updateCursor(String sessionId, Messages.ClientPresence update) {
        Participant current = bySession.get(sessionId);
        if (current == null) {
            return;
        }
        bySession.put(sessionId, new Participant(
            current.sessionId(), current.documentId(), current.replicaId(),
            current.userId(), current.displayName(), current.color(),
            update.cursorReplicaId(), update.cursorLamport(),
            update.anchorReplicaId(), update.anchorLamport(),
            update.typing()));
        broadcast(current.documentId());
    }

    public void leave(String sessionId) {
        Participant gone = bySession.remove(sessionId);
        if (gone == null) {
            return;
        }
        metrics.sessionClosed();
        // Replika, która się rozłączyła, przestaje blokować wyliczenie stabilności
        // przyczynowej dla GC (ADR-0005). Bez tego jedna zamknięta karta przeglądarki
        // wstrzymywałaby kompakcję tombstone'ów do końca życia procesu.
        if (registry.isLoaded(gone.documentId())) {
            registry.get(gone.documentId()).forgetReplica(gone.replicaId());
        }
        broadcast(gone.documentId());
    }

    public List<Participant> participants(UUID documentId) {
        List<Participant> result = new ArrayList<>();
        for (Participant participant : bySession.values()) {
            if (participant.documentId().equals(documentId)) {
                result.add(participant);
            }
        }
        return result;
    }

    private void broadcast(UUID documentId) {
        List<Messages.PresenceBroadcast> payload = participants(documentId).stream()
            .map(p -> new Messages.PresenceBroadcast(
                p.replicaId(), p.userId(), p.displayName(), p.color(),
                p.cursorReplicaId(), p.cursorLamport(),
                p.anchorReplicaId(), p.anchorLamport(),
                p.typing(), true))
            .toList();
        messaging.convertAndSend("/topic/doc/" + documentId + "/presence", payload);
    }
}
