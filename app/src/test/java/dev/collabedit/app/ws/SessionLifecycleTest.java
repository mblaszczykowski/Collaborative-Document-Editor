package dev.collabedit.app.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.collabedit.app.observability.DocumentMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.socket.CloseStatus;

/**
 * Session teardown, and the frame-ordering race it exists to close.
 *
 * <table>
 *   <caption>Test -> failure mode</caption>
 *   <tr><th>Test</th><th>What it catches</th></tr>
 *   <tr><td>{@code joiningAfterTheSessionEndedIsIgnored}</td>
 *       <td>The race that made this class necessary. {@code clientInboundChannel} is a
 *           multi-threaded executor with no {@code preserveReceiveOrder}, so a queued
 *           {@code /join} can be dispatched after the same session's disconnect has already been
 *           processed. Presence then regained an entry nothing would ever remove — a departed
 *           participant listed for the lifetime of the process — and the session counters were
 *           incremented a second time, so {@code ws.sessions.active} drifted upwards and never came
 *           back down.</td></tr>
 *   <tr><td>{@code endingTwiceTearsDownOnce}</td>
 *       <td>Double teardown. Spring's own documentation says {@code SessionDisconnectEvent} may be
 *           published more than once per session, and this class is reached from BOTH that event
 *           and the inbound DISCONNECT frame. A second teardown would decrement the active-session
 *           gauge below zero and announce a departure that already happened.</td></tr>
 *   <tr><td>{@code bufferOverflowIsRecognised}</td>
 *       <td>Code 4500 falling into the {@code other} bucket. The metric exists, the panel is there,
 *           the graph is flat — and it looks like proof the problem is absent, when it only means
 *           we are not measuring it. That is the worst possible failure mode for a diagnostic
 *           metric.</td></tr>
 *   <tr><td>{@code unknownCodesCollapseIntoOneBucket}</td>
 *       <td>The close code going into the tag verbatim. A client may close a connection with any
 *           code in the 4000–4999 range, so this would be externally driven cardinality — that is,
 *           a way to blow up Prometheus with one malicious client.</td></tr>
 *   <tr><td>{@code missingStatusDoesNotThrow}</td>
 *       <td>An event with no status crashing the listener. An exception while handling a Spring
 *           event brings down the whole session teardown, not just the metric.</td></tr>
 * </table>
 *
 * <p>The close-reason half of this file used to be {@code observability/SessionCloseListenerTest}.
 * It moved with the code: mapping a close code and tearing a session down were two of the three
 * places one event was handled, and they are one place now.
 */
@DisplayName("A session is torn down once, and a late join cannot revive it")
class SessionLifecycleTest {

    private static final String SESSION = "session-1";
    private static final String USER = "user-1";
    private static final UUID DOCUMENT = UUID.randomUUID();

    private final PresenceService presence = mock(PresenceService.class);
    private final DocumentMetrics metrics = new DocumentMetrics(new SimpleMeterRegistry(), null);

    private SessionLifecycle lifecycle() {
        @SuppressWarnings("unchecked")
        ObjectProvider<PresenceService> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(presence);
        return new SessionLifecycle(new RateLimiter(), provider, metrics);
    }

    // ------------------------------------------------------------------
    // The race
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a join for a session that has already ended is ignored, not recorded")
    void joiningAfterTheSessionEndedIsIgnored() {
        SessionLifecycle lifecycle = lifecycle();
        lifecycle.begin(SESSION, USER);
        lifecycle.end(SESSION);

        lifecycle.joined(SESSION, DOCUMENT, 7, USER, "Anna", "#4C5FD5");

        verify(presence, never())
            .recordJoin(SESSION, DOCUMENT, 7, USER, "Anna", "#4C5FD5");
        assertThat(lifecycle.userOf(SESSION))
            .as("""
                A join after teardown put the session back into the live map, so nothing will ever \
                remove it: the disconnect that would have has already been and gone.""")
            .isNull();
    }

    @Test
    @DisplayName("a join while the session is live is recorded and announced")
    void joiningWhileLiveIsRecorded() {
        SessionLifecycle lifecycle = lifecycle();
        lifecycle.begin(SESSION, USER);

        lifecycle.joined(SESSION, DOCUMENT, 7, USER, "Anna", "#4C5FD5");

        verify(presence).recordJoin(SESSION, DOCUMENT, 7, USER, "Anna", "#4C5FD5");
        verify(presence).announce(DOCUMENT);
    }

    @Test
    @DisplayName("ending twice tears down once — the event may fire more than once")
    void endingTwiceTearsDownOnce() {
        SessionLifecycle lifecycle = lifecycle();
        lifecycle.begin(SESSION, USER);
        when(presence.recordLeave(SESSION)).thenReturn(DOCUMENT);

        lifecycle.end(SESSION);
        lifecycle.end(SESSION);

        verify(presence, org.mockito.Mockito.times(1)).recordLeave(SESSION);
    }

    @Test
    @DisplayName("ending a session nobody ever opened does nothing at all")
    void endingAnUnknownSessionDoesNothing() {
        SessionLifecycle lifecycle = lifecycle();

        lifecycle.end("never-connected");

        verify(presence, never()).recordLeave("never-connected");
    }

    @Test
    @DisplayName("the identity is available while live and gone afterwards")
    void identityFollowsTheSession() {
        SessionLifecycle lifecycle = lifecycle();
        lifecycle.begin(SESSION, USER);

        // RevokedAccessFilter asks exactly this question for every outbound frame, and fails closed
        // when the answer is null. If the map stopped being populated at CONNECT, every broadcast
        // would be dropped rather than a few — loudly wrong instead of subtly, which is the right
        // direction, but this is the assertion that would say so.
        assertThat(lifecycle.userOf(SESSION)).isEqualTo(USER);

        lifecycle.end(SESSION);
        assertThat(lifecycle.userOf(SESSION)).isNull();
    }

    // ------------------------------------------------------------------
    // Close reasons
    // ------------------------------------------------------------------

    @Test
    @DisplayName("send buffer overflow gets its own tag value rather than falling into 'other'")
    void bufferOverflowIsRecognised() {
        assertThat(SessionLifecycle.reasonOf(CloseStatus.SESSION_NOT_RELIABLE))
            .isEqualTo(SessionLifecycle.SLOW_CLIENT);

        // The same code spelled out — if Spring ever changed the constant, we want to hear it
        // from a test rather than from a flat graph.
        assertThat(CloseStatus.SESSION_NOT_RELIABLE.getCode()).isEqualTo(4500);
    }

    @Test
    @DisplayName("recognised closes have disjoint tag values")
    void knownCodesAreDistinct() {
        assertThat(SessionLifecycle.reasonOf(CloseStatus.NORMAL)).isEqualTo("normal");
        assertThat(SessionLifecycle.reasonOf(CloseStatus.GOING_AWAY)).isEqualTo("going_away");
        assertThat(SessionLifecycle.reasonOf(CloseStatus.NO_CLOSE_FRAME)).isEqualTo("abnormal");
        assertThat(SessionLifecycle.reasonOf(CloseStatus.SERVER_ERROR)).isEqualTo("server_error");
    }

    @Test
    @DisplayName("unknown codes land in one bucket — cardinality must not depend on the client")
    void unknownCodesCollapseIntoOneBucket() {
        assertThat(SessionLifecycle.reasonOf(new CloseStatus(4001))).isEqualTo("other");
        assertThat(SessionLifecycle.reasonOf(new CloseStatus(4999))).isEqualTo("other");
        assertThat(SessionLifecycle.reasonOf(new CloseStatus(1013))).isEqualTo("other");
    }

    @Test
    @DisplayName("a missing status gives a fallback value rather than an exception")
    void missingStatusDoesNotThrow() {
        assertThat(SessionLifecycle.reasonOf(null)).isEqualTo("unknown");
    }

    // ------------------------------------------------------------------
    // Replica identifiers, and the two ways of getting their scope wrong
    // ------------------------------------------------------------------

    /**
     * A replica identifier belongs to whoever is CONNECTED under it, and to nobody once they leave.
     *
     * <p>Both halves matter and each is a different bug. Reporting an owner for an identifier
     * nobody holds refuses offline replay — the operations restored from IndexedDB after a tab is
     * closed arrive under an identifier whose session ended, which is the whole point of the
     * buffer. Reporting nobody while a session holds it lets any participant mint operations as
     * that peer, and the collision on {@code (replicaId, lamport)} then swallows the peer's own
     * work as a duplicate, silently.
     */
    @Test
    @DisplayName("a replica identifier is owned while its session is connected, and free after")
    void replicaOwnershipFollowsTheLiveSession() {
        SessionLifecycle lifecycle = lifecycle();

        assertThat(lifecycle.userOfReplica(700))
            .as("""
                An identifier nobody is connected under must come back unowned. Reporting an owner                 here is what refused an offline buffer replayed in a new tab — a new login means a                 new user id, so binding to the USER made every such replay a conflict.""")
            .isNull();

        lifecycle.begin(SESSION, USER);
        lifecycle.joined(SESSION, DOCUMENT, 700, USER, "Anna", "#4C5FD5");
        assertThat(lifecycle.userOfReplica(700)).isEqualTo(USER);

        lifecycle.end(SESSION);
        assertThat(lifecycle.userOfReplica(700))
            .as("the session is gone, so the identifier is free again — this is the offline replay "
                + "case, and refusing it would break the feature to close a narrower hole")
            .isNull();
    }

    /**
     * The counter that hands out identifiers wraps, so the same number can be issued again. A
     * teardown that removed the entry blindly would then unregister a live session.
     */
    @Test
    @DisplayName("ending a session does not release an identifier a newer session holds")
    void endingASessionDoesNotReleaseSomebodyElsesIdentifier() {
        SessionLifecycle lifecycle = lifecycle();
        lifecycle.begin(SESSION, USER);
        lifecycle.joined(SESSION, DOCUMENT, 700, USER, "Anna", "#4C5FD5");

        lifecycle.begin("session-2", "user-2");
        lifecycle.joined("session-2", DOCUMENT, 700, "user-2", "Marek", "#C1592E");
        assertThat(lifecycle.userOfReplica(700)).isEqualTo("user-2");

        lifecycle.end(SESSION);
        assertThat(lifecycle.userOfReplica(700))
            .as("the first session's teardown removed an entry the second session owns")
            .isEqualTo("user-2");
    }
}
