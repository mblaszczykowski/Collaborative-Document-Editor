package dev.collabedit.app.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.collabedit.app.auth.CommentAnchorAuthority;
import dev.collabedit.app.auth.PermissionService;
import dev.collabedit.app.auth.Role;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.broker.DefaultSubscriptionRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.util.MultiValueMap;

class DocumentAccessInterceptorTest {
    private static final UUID DOCUMENT = UUID.randomUUID();
    private static final UUID OTHER_DOCUMENT = UUID.randomUUID();
    private static final String USER = "user-1";

    private final PermissionService permissions = mock(PermissionService.class);

    private final Set<UUID> ownThreads = new HashSet<>();

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private final DocumentAccessInterceptor interceptor = new DocumentAccessInterceptor(
        permissions,
        new dev.collabedit.app.observability.DocumentMetrics(meters, null),
        new RateLimiter(),
        emptyProvider(),
        (userId, documentId, threadId) ->
            USER.equals(userId) && DOCUMENT.equals(documentId) && ownThreads.contains(threadId));

    @Test
    @DisplayName("a wildcard subscription is refused AS a wildcard - this is the wildcard bypass")
    void wildcardSubscriptionsAreRefused() {
        for (String destination : new String[] {
            "/topic/**",
            "/topic/doc/**",
            "/topic/doc/*/ops",
            "/queue/**",
            "/**",
            "/user/queue/*",
        }) {
            long before = refusals("wildcard_destination");

            assertThat(subscribe(destination))
                .as("SUBSCRIBE %s was allowed - the simple broker matches this as a pattern, so the "
                    + "session now receives every document's broadcasts", destination)
                .isNull();

            assertThat(refusals("wildcard_destination"))
                .as("SUBSCRIBE %s was refused, but as an unrecognised destination rather than as a "
                    + "wildcard - so the wildcard rule is not what this test is exercising",
                    destination)
                .isEqualTo(before + 1);
        }
    }

    private long refusals(String reason) {
        var counter = meters.find("ws.frames.refused.total").tag("reason", reason).counter();
        return counter == null ? 0L : (long) counter.count();
    }

    @Test
    @DisplayName("a SEND to a BROKER destination is refused - the second bypass")
    void sendingToABrokerDestinationIsRefused() {
        when(permissions.roleOf(USER, DOCUMENT)).thenReturn(Optional.of(Role.VIEWER));

        for (String destination : new String[] {
            "/topic/doc/" + DOCUMENT + "/presence",
            "/topic/doc/" + DOCUMENT + "/ops",
            "/topic/doc/" + DOCUMENT + "/acks",
            "/topic/doc/" + DOCUMENT + "/access",
            "/topic/doc/" + DOCUMENT + "/sync",
            "/queue/doc/" + DOCUMENT + "/sync",
            "/user/queue/sync",
        }) {
            assertThat(send(destination, "{}"))
                .as("SEND %s was allowed - /topic and /queue are BROKER prefixes, so the frame is "
                    + "fanned out to every subscriber with the client's payload in it", destination)
                .isNull();
        }
    }

    @Test
    @DisplayName("subscribing to those same destinations still works - only SEND is refused")
    void subscribingToBrokerDestinationsStillWorks() {
        when(permissions.roleOf(USER, DOCUMENT)).thenReturn(Optional.of(Role.VIEWER));

        assertThat(subscribe("/topic/doc/" + DOCUMENT + "/presence")).isNotNull();
        assertThat(subscribe("/topic/doc/" + DOCUMENT + "/ops")).isNotNull();
    }

    @Test
    @DisplayName("an unrecognised destination with no document identifier is refused, not waved through")
    void unknownDestinationsAreRefused() {
        assertThat(subscribe("/topic/doc/not-a-uuid/ops")).isNull();
        assertThat(subscribe("/topic/anything")).isNull();
        assertThat(subscribe("/queue/sync")).isNull();
        assertThat(subscribe("/user/queue/somethingelse")).isNull();
        assertThat(send("/app/anything", "{}")).isNull();
    }

    @Test
    @DisplayName("Spring's broker really does match subscription destinations as Ant patterns")
    void springsBrokerReallyDoesMatchSubscriptionsAsPatterns() {
        DefaultSubscriptionRegistry registry = new DefaultSubscriptionRegistry();
        registry.registerSubscription(brokerSubscribe("intruder", "sub-0", "/topic/**"));

        MultiValueMap<String, String> reached =
            registry.findSubscriptions(brokerMessage("/topic/doc/" + DOCUMENT + "/ops"));

        assertThat(reached.keySet())
            .as("the simple broker no longer pattern-matches subscriptions; the interceptor's "
                + "wildcard refusal is now defence in depth rather than the only thing standing "
                + "between a client-supplied destination and every document on the instance")
            .contains("intruder");
    }

    @Test
    @DisplayName("the three private user queues are allowed")
    void privateUserQueuesAreAllowed() {
        for (String destination : DocumentAccessInterceptor.PRIVATE_USER_QUEUES) {
            assertThat(subscribe(destination))
                .as("SUBSCRIBE %s was refused - the client cannot receive its own session, sync "
                    + "or error frames", destination)
                .isNotNull();
        }
    }

    @Test
    @DisplayName("the private-queue allow-list is exactly what the server sends to")
    void theAllowListIsPinned() {
        assertThat(DocumentAccessInterceptor.PRIVATE_USER_QUEUES)
            .containsExactlyInAnyOrder("/user/queue/session", "/user/queue/sync", "/user/queue/errors");
    }

    @Test
    @DisplayName("a document topic is allowed for somebody with a role and refused for everybody else")
    void documentTopicsStillFollowTheRole() {
        when(permissions.roleOf(USER, DOCUMENT)).thenReturn(Optional.of(Role.VIEWER));
        when(permissions.roleOf(USER, OTHER_DOCUMENT)).thenReturn(Optional.empty());

        assertThat(subscribe("/topic/doc/" + DOCUMENT + "/ops")).isNotNull();
        assertThat(subscribe("/topic/doc/" + DOCUMENT + "/acks")).isNotNull();
        assertThat(subscribe("/topic/doc/" + OTHER_DOCUMENT + "/ops")).isNull();
    }

    @Test
    @DisplayName("sending to a document still follows the role")
    void sendingStillFollowsTheRole() {
        when(permissions.roleOf(USER, DOCUMENT)).thenReturn(Optional.of(Role.VIEWER));

        assertThat(send("/app/doc/" + DOCUMENT + "/op", "{\"op\":{\"t\":\"i\"}}"))
            .as("a viewer sent a text operation")
            .isNull();
        assertThat(send("/app/doc/" + DOCUMENT + "/join", "{}"))
            .as("a viewer could not join a document they are allowed to read")
            .isNotNull();
    }

    @Test
    @DisplayName("a frame with no destination is refused, not a NullPointerException")
    void framesWithNoDestinationAreRefused() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setSessionId("session-1");
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(SessionAttributes.USER_ID, USER);
        accessor.setSessionAttributes(attributes);
        Message<byte[]> message =
            MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        assertThat(interceptor.preSend(message, null)).isNull();
    }

    @Test
    @DisplayName("frames that are neither SUBSCRIBE nor SEND pass through untouched")
    void otherFrameTypesArePassedThrough() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.UNSUBSCRIBE);
        accessor.setSessionId("session-1");
        Message<byte[]> message =
            MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        assertThat(interceptor.preSend(message, null)).isSameAs(message);
    }

    private Message<?> subscribe(String destination) {
        return frame(StompCommand.SUBSCRIBE, destination, "");
    }

    private Message<?> send(String destination, String body) {
        return frame(StompCommand.SEND, destination, body);
    }

    private Message<?> frame(StompCommand command, String destination, String body) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setSessionId("session-1");
        accessor.setDestination(destination);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(SessionAttributes.USER_ID, USER);
        accessor.setSessionAttributes(attributes);
        Message<byte[]> message = MessageBuilder.createMessage(
            body.getBytes(java.nio.charset.StandardCharsets.UTF_8), accessor.getMessageHeaders());
        return interceptor.preSend(message, null);
    }

    private static Message<?> brokerSubscribe(String sessionId, String subscriptionId, String destination) {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create(SimpMessageType.SUBSCRIBE);
        accessor.setSessionId(sessionId);
        accessor.setSubscriptionId(subscriptionId);
        accessor.setDestination(destination);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private static Message<?> brokerMessage(String destination) {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        accessor.setDestination(destination);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<SimpMessagingTemplate> emptyProvider() {
        ObjectProvider<SimpMessagingTemplate> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(mock(SimpMessagingTemplate.class));
        when(provider.getIfAvailable()).thenReturn(mock(SimpMessagingTemplate.class));
        return provider;
    }
}
