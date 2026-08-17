package dev.collabedit.app.ws;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import dev.collabedit.app.cluster.PresenceBus;
import dev.collabedit.app.domain.DocumentService;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

@DisplayName("A cursor update applies only to the document the frame was authorized against")
class PresenceScopeTest {
    private static final UUID JOINED = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();
    private static final String SESSION = "session-1";

    private final SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
    private final PresenceBus bus = mock(PresenceBus.class);

    private PresenceService presenceWithJoinedSession() {
        PresenceService presence =
            new PresenceService(messaging, mock(DocumentService.class), bus);
        presence.recordJoin(SESSION, JOINED, 7, "user-1", "Anna", "#4C5FD5");
        return presence;
    }

    private static Messages.ClientPresence cursorAt(long lamport) {
        return new Messages.ClientPresence(7, lamport, null, null, true);
    }

    @Test
    @DisplayName("an update naming the joined document is applied")
    void updateForTheJoinedDocumentIsApplied() {
        PresenceService presence = presenceWithJoinedSession();

        presence.updateCursor(SESSION, JOINED, cursorAt(42));

        verify(messaging).convertAndSend(
            eq("/topic/doc/" + JOINED + "/presence"), any(Object.class));
    }

    @Test
    @DisplayName("an update naming a DIFFERENT document is dropped, not redirected")
    void updateForAnotherDocumentIsDropped() {
        PresenceService presence = presenceWithJoinedSession();

        presence.updateCursor(SESSION, OTHER, cursorAt(42));

        verify(messaging, never()).convertAndSend(
            eq("/topic/doc/" + JOINED + "/presence"), any(Object.class));
        verify(messaging, never()).convertAndSend(
            eq("/topic/doc/" + OTHER + "/presence"), any(Object.class));
    }

    @Test
    @DisplayName("an update from a session that has already gone is dropped")
    void updateForAnEndedSessionIsDropped() {
        PresenceService presence = presenceWithJoinedSession();
        presence.recordLeave(SESSION);

        presence.updateCursor(SESSION, JOINED, cursorAt(42));

        verify(messaging, never()).convertAndSend(
            eq("/topic/doc/" + JOINED + "/presence"), any(Object.class));
    }
}
