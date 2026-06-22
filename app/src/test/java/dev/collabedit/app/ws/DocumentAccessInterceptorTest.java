package dev.collabedit.app.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.collabedit.app.auth.PermissionService;
import dev.collabedit.app.auth.Role;
import java.util.HashMap;
import java.util.Map;
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

/**
 * The frame-level half of CP12, and the regression test for the hole that made it decorative.
 *
 * <h2>What was wrong</h2>
 * {@link DocumentAccessInterceptor} authorises a frame by parsing a document identifier out of the
 * destination. When it could not find one it used to return the frame <b>unchanged</b>, on the
 * stated reasoning that a destination without a document must be one of the user's own queues.
 *
 * <p>Spring's simple broker matches subscription destinations as <b>Ant patterns</b>, so
 * {@code SUBSCRIBE /topic/**} parsed to no document, was waved through, and then matched every
 * document's broadcast. {@link #springsBrokerReallyDoesMatchSubscriptionsAsPatterns()} pins that
 * property rather than asserting it in a comment — if a future Spring stopped doing it, the reader
 * should learn that this guard became belt-and-braces rather than assume it always was.
 *
 * <p>Unit tests on purpose: the behaviour is a decision about a destination string and a role, and
 * running Postgres and a broker to observe it would make the check slower without making it
 * stronger. {@code PermissionIT} covers the same refusal over a real socket.
 */
class DocumentAccessInterceptorTest {

    private static final UUID DOCUMENT = UUID.randomUUID();
    private static final UUID OTHER_DOCUMENT = UUID.randomUUID();
    private static final String USER = "user-1";

    private final PermissionService permissions = mock(PermissionService.class);
    private final DocumentAccessInterceptor interceptor = new DocumentAccessInterceptor(
        permissions,
        new dev.collabedit.app.observability.DocumentMetrics(
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), null),
        new RateLimiter(),
        emptyProvider());

    // ------------------------------------------------------------------
    // The hole
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a wildcard subscription is refused — this is the CP12 bypass")
    void wildcardSubscriptionsAreRefused() {
        for (String destination : new String[] {
            "/topic/**",
            "/topic/doc/**",
            "/topic/doc/*/ops",
            "/queue/**",
            "/**",
            "/user/queue/*",
        }) {
            assertThat(subscribe(destination))
                .as("SUBSCRIBE %s was allowed — the simple broker matches this as a pattern, so the "
                    + "session now receives every document's broadcasts", destination)
                .isNull();
        }
    }

    @Test
    @DisplayName("a SEND to a BROKER destination is refused — the second CP12 bypass")
    void sendingToABrokerDestinationIsRefused() {
        // The role is deliberately the LOWEST one that exists. This hole needed no privilege at
        // all: allowedToSend decides on the destination's suffix and never looked at its head, so
        // "/topic/doc/{id}/presence" matched the "/presence" rule, which returns true for every
        // role — and Spring's SimpleBrokerMessageHandler then fanned the client's own payload out
        // to every subscriber of that topic.
        when(permissions.roleOf(USER, DOCUMENT)).thenReturn(Optional.of(Role.VIEWER));

        for (String destination : new String[] {
            // The one that actually got through. What it buys is the participant list of every
            // client in the document: impersonated names, colours and cursors, an empty room, or
            // complete:false — the flag whose whole purpose is to tell the user the list is
            // unreliable.
            "/topic/doc/" + DOCUMENT + "/presence",
            // These were refused only by the accident of their suffixes. "/ops" does not end with
            // "/op", and no rule matches "/acks" or "/access" — luck, and one renamed channel away
            // from being an operation-injection hole.
            "/topic/doc/" + DOCUMENT + "/ops",
            "/topic/doc/" + DOCUMENT + "/acks",
            "/topic/doc/" + DOCUMENT + "/access",
            "/topic/doc/" + DOCUMENT + "/sync",
            // The user queues are outbound too: a SEND here would be the server's own reply
            // channel with a client's payload in it.
            "/queue/doc/" + DOCUMENT + "/sync",
            "/user/queue/sync",
        }) {
            assertThat(send(destination, "{}"))
                .as("SEND %s was allowed — /topic and /queue are BROKER prefixes, so the frame is "
                    + "fanned out to every subscriber with the client's payload in it", destination)
                .isNull();
        }
    }

    @Test
    @DisplayName("subscribing to those same destinations still works — only SEND is refused")
    void subscribingToBrokerDestinationsStillWorks() {
        // The prefix rule must not be applied to SUBSCRIBE: /topic is exactly where a client is
        // supposed to subscribe. Refusing both would have been a fix that closed the hole by
        // turning the editor off.
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

    // ------------------------------------------------------------------
    // What must still work
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the three private user queues are allowed")
    void privateUserQueuesAreAllowed() {
        for (String destination : DocumentAccessInterceptor.PRIVATE_USER_QUEUES) {
            assertThat(subscribe(destination))
                .as("SUBSCRIBE %s was refused — the client cannot receive its own session, sync "
                    + "or error frames", destination)
                .isNotNull();
        }
    }

    @Test
    @DisplayName("the private-queue allow-list is exactly what the server sends to")
    void theAllowListIsPinned() {
        // Pinned by name for the same reason as EXPECTED_PUBLIC in the architecture test: widening
        // it is a decision, and a decision should be visible in a diff. These three are every
        // destination `convertAndSendToUser` is called with across the application.
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
        // The allow-list is a Set.of(...), and that throws on contains(null). A malformed frame
        // with no destination header would therefore have blown up INSIDE the interceptor —
        // turning a refusal into an exception on the inbound channel.
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

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

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

    /** The interceptor only reaches for the template to report a rate-limit refusal. */
    @SuppressWarnings("unchecked")
    private static ObjectProvider<SimpMessagingTemplate> emptyProvider() {
        ObjectProvider<SimpMessagingTemplate> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(mock(SimpMessagingTemplate.class));
        when(provider.getIfAvailable()).thenReturn(mock(SimpMessagingTemplate.class));
        return provider;
    }
}
