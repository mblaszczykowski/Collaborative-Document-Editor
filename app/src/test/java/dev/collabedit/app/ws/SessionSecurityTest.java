package dev.collabedit.app.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import dev.collabedit.app.auth.PermissionService;
import dev.collabedit.app.auth.ReplicaIdAllocator;
import dev.collabedit.app.auth.TokenService;
import dev.collabedit.app.observability.DocumentMetrics;
import java.time.Instant;
import java.util.HashMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

class SessionSecurityTest {
    private final PermissionService permissions = mock(PermissionService.class);
    private final DocumentMetrics metrics = mock(DocumentMetrics.class);
    private final SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
    private final SessionLifecycle sessions = new SessionLifecycle(new RateLimiter(), null, metrics);
    private final ReplicaIdAllocator ids = mock(ReplicaIdAllocator.class);
    private final TokenService tokens = new TokenService("synthetic-test-signing-secret-at-least-32");
    private final DocumentAccessInterceptor access;

    SessionSecurityTest() {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.registerSingleton("messaging", messaging);
        access = new DocumentAccessInterceptor(permissions, metrics, new RateLimiter(),
            factory.getBeanProvider(SimpMessagingTemplate.class), (u, d, t) -> false);
    }

    private org.springframework.beans.factory.ObjectProvider<SimpMessagingTemplate> messagingProvider() {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.registerSingleton("messaging", messaging);
        return factory.getBeanProvider(SimpMessagingTemplate.class);
    }

    private static Message<byte[]> message(StompHeaderAccessor headers) {
        headers.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
    }

    @Test
    void connectedSessionCannotSendOrSubscribeAfterExpiry() {
        when(ids.nextFor("user")).thenReturn(1);
        var state = new StompConnectInterceptor(tokens, ids, sessions, access, null).sessionState();
        var attributes = new HashMap<String, Object>();
        var connect = StompHeaderAccessor.create(StompCommand.CONNECT);
        connect.setSessionId("session");
        connect.setSessionAttributes(attributes);
        connect.addNativeHeader("token", tokens.issue(new TokenService.Principal("user", "User", "#000")));
        assertThat(state.preSend(message(connect), null)).isNotNull();
        assertThat(attributes.get(SessionAttributes.EXPIRES_AT)).isInstanceOf(Long.class);
        attributes.put(SessionAttributes.EXPIRES_AT, Instant.now().getEpochSecond() - 1);
        for (var command : new StompCommand[] {StompCommand.SEND, StompCommand.SUBSCRIBE}) {
            var headers = StompHeaderAccessor.create(command);
            headers.setSessionId("session");
            headers.setSessionAttributes(attributes);
            headers.setDestination("/app/doc/" + UUID.randomUUID() + "/op");
            assertThat(state.preSend(message(headers), null)).isNull();
        }
        verifyNoInteractions(permissions);
        verify(messaging).convertAndSendToUser(eq("session"), eq("/queue/errors"),
            argThat(value -> value instanceof Messages.ErrorMessage error && error.code().equals("SESSION_EXPIRED")), any(org.springframework.messaging.MessageHeaders.class));
    }

    @Test
    void expiredSessionDoesNotReceiveDocumentBroadcasts() {
        sessions.begin("session", "user", Instant.now().getEpochSecond() - 1);
        var headers = StompHeaderAccessor.create(StompCommand.MESSAGE);
        headers.setSessionId("session");
        headers.setDestination("/topic/doc/" + UUID.randomUUID() + "/ops");
        assertThat(new RevokedAccessFilter(permissions, sessions, metrics, messagingProvider()).preSend(message(headers), null)).isNull();
        verifyNoInteractions(permissions);
    }

    @Test
    void permissionDatabaseFailureDropsConfidentialContentButAllowsAccessNotification() {
        UUID document = UUID.randomUUID();
        sessions.begin("session", "user", Instant.now().getEpochSecond() + 60);
        when(permissions.roleOf("user", document)).thenThrow(new IllegalStateException("database unavailable"));
        var filter = new RevokedAccessFilter(permissions, sessions, metrics, messagingProvider());
        var headers = StompHeaderAccessor.create(StompCommand.MESSAGE);
        headers.setSessionId("session");
        headers.setDestination("/topic/doc/" + document + "/ops");
        assertThat(filter.preSend(message(headers), null)).isNull();
        var notification = StompHeaderAccessor.create(StompCommand.MESSAGE);
        notification.setSessionId("session");
        notification.setDestination("/topic/doc/" + document + "/access");
        assertThat(filter.preSend(message(notification), null)).isNotNull();
    }
    @Test
    void privateSyncRepliesAreAlsoCheckedForExpiryAndRevocation() {
        UUID document = UUID.randomUUID();
        sessions.begin("session", "user", Instant.now().getEpochSecond() + 60);
        var headers = StompHeaderAccessor.create(StompCommand.MESSAGE);
        headers.setSessionId("session");
        headers.setDestination("/queue/sync-usersession");
        headers.setHeader(SessionAttributes.DOCUMENT_ID, document);
        when(permissions.roleOf("user", document)).thenReturn(java.util.Optional.empty());
        var filter = new RevokedAccessFilter(permissions, sessions, metrics, messagingProvider());
        assertThat(filter.preSend(message(headers), null)).isNull();
        sessions.begin("session", "user", Instant.now().getEpochSecond() - 1);
        var untagged = StompHeaderAccessor.create(StompCommand.MESSAGE);
        untagged.setSessionId("session");
        untagged.setDestination("/queue/sync-usersession");
        assertThat(filter.preSend(message(untagged), null)).isNull();
        var error = StompHeaderAccessor.create(StompCommand.MESSAGE);
        error.setSessionId("session");
        error.setDestination("/queue/errors-usersession");
        assertThat(filter.preSend(message(error), null)).isNotNull();
    }

}
