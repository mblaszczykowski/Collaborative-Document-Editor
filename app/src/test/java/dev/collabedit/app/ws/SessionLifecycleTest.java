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
    @DisplayName("ending twice tears down once - the event may fire more than once")
    void endingTwiceTearsDownOnce() {
        SessionLifecycle lifecycle = lifecycle();
        lifecycle.begin(SESSION, USER);
        when(presence.recordLeave(SESSION)).thenReturn(departedParticipant());

        lifecycle.end(SESSION);
        lifecycle.end(SESSION);

        verify(presence, org.mockito.Mockito.times(1)).recordLeave(SESSION);
        verify(presence, org.mockito.Mockito.times(1))
            .recordDeparture(org.mockito.ArgumentMatchers.any());
    }

    private static PresenceService.Participant departedParticipant() {
        return new PresenceService.Participant(
            SESSION, DOCUMENT, 7, USER, "Anna", "#4C5FD5", null, null, null, null, false);
    }

    @Test
    @DisplayName("a departure that cannot be recorded durably still tears the session down")
    void aFailedDurableDepartureDoesNotStrandTheSession() {
        SessionLifecycle lifecycle = lifecycle();
        lifecycle.begin(SESSION, USER);
        when(presence.recordLeave(SESSION)).thenReturn(departedParticipant());
        org.mockito.Mockito.doThrow(new RuntimeException("pool exhausted"))
            .when(presence).recordDeparture(org.mockito.ArgumentMatchers.any());

        lifecycle.end(SESSION);

        assertThat(lifecycle.userOfReplica(7)).isNull();
        verify(presence).announce(DOCUMENT);
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

        assertThat(lifecycle.userOf(SESSION)).isEqualTo(USER);

        lifecycle.end(SESSION);
        assertThat(lifecycle.userOf(SESSION)).isNull();
    }

    @Test
    @DisplayName("send buffer overflow gets its own tag value rather than falling into 'other'")
    void bufferOverflowIsRecognised() {
        assertThat(SessionLifecycle.reasonOf(CloseStatus.SESSION_NOT_RELIABLE))
            .isEqualTo(SessionLifecycle.SLOW_CLIENT);

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
    @DisplayName("unknown codes land in one bucket - cardinality must not depend on the client")
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

    @Test
    @DisplayName("a replica identifier is owned while its session is connected, and free after")
    void replicaOwnershipFollowsTheLiveSession() {
        SessionLifecycle lifecycle = lifecycle();

        assertThat(lifecycle.userOfReplica(700))
            .as("""
                An identifier nobody is connected under must come back unowned. Reporting an owner                 here is what refused an offline buffer replayed in a new tab - a new login means a                 new user id, so binding to the USER made every such replay a conflict.""")
            .isNull();

        lifecycle.begin(SESSION, USER);
        lifecycle.joined(SESSION, DOCUMENT, 700, USER, "Anna", "#4C5FD5");
        assertThat(lifecycle.userOfReplica(700)).isEqualTo(USER);

        lifecycle.end(SESSION);
        assertThat(lifecycle.userOfReplica(700))
            .as("the session is gone, so the identifier is free again - this is the offline replay "
                + "case, and refusing it would break the feature to close a narrower hole")
            .isNull();
    }

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
