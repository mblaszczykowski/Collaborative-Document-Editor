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

/**
 * A presence update lands in the document the frame named, or nowhere.
 *
 * <h2>The gap this pins</h2>
 * {@code DocumentStompController.onPresence} declared {@code @DestinationVariable documentId} and
 * never read it. {@code DocumentAccessInterceptor} authorizes the frame against the document in the
 * DESTINATION; {@code updateCursor} then resolved the target from the session's <b>joined</b>
 * document instead. The frame that was checked and the document that was written were two different
 * things.
 *
 * <p>What that allowed, concretely: hold any role on document B — your own document will do — join
 * document A, get revoked from A, then send to {@code /app/doc/B/presence}. The interceptor resolves
 * B, finds your editor role there, and passes the frame; the update is published into A. Nothing
 * removes a participant on revocation either, so the session's record still says A.
 *
 * <p>No document content escapes — {@code RevokedAccessFilter} holds the outbound side — but a
 * removed user keeps a live cursor, name, colour and typing indicator inside a document they were
 * thrown out of, re-announced across the cluster every second. {@code PermissionIT}'s revocation
 * test asserts on {@code text()} and on the access-change count, never on presence, which is why
 * this needed its own test rather than an extra line there.
 *
 * <p>A unit test: the rule is a comparison between two identifiers, and standing up a broker and a
 * database to watch it would make the check slower without making it stronger.
 */
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

        // The ordinary path has to keep working: a rule that refuses everything would pass the
        // test below and break the feature.
        verify(messaging).convertAndSend(
            eq("/topic/doc/" + JOINED + "/presence"), any(Object.class));
    }

    @Test
    @DisplayName("an update naming a DIFFERENT document is dropped, not redirected")
    void updateForAnotherDocumentIsDropped() {
        PresenceService presence = presenceWithJoinedSession();

        presence.updateCursor(SESSION, OTHER, cursorAt(42));

        // Neither document. Not the one named — this session is not in it — and above all not the
        // joined one, which is what the old code did with it.
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

        // The other half of the same method: computeIfPresent rather than get-then-put, so a
        // presence frame dispatched after teardown cannot re-create an entry nothing will remove.
        // The inbound channel has no preserveReceiveOrder, so the two really can arrive that way.
        verify(messaging, never()).convertAndSend(
            eq("/topic/doc/" + JOINED + "/presence"), any(Object.class));
    }
}
