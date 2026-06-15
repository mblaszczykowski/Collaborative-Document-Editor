package dev.collabedit.app.ws;

import com.fasterxml.jackson.databind.JsonNode;
import dev.collabedit.app.auth.PermissionService;
import dev.collabedit.app.auth.Role;
import dev.collabedit.crdt.MarkTypes;
import dev.collabedit.crdt.OpCodec;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

/**
 * Authorization of <b>every</b> STOMP frame entering the system (CP12).
 *
 * <h2>Why this is the most important file of that checkpoint</h2>
 * Up to CP12 there was no authorization at all: {@link StompConnectInterceptor} checked the token
 * at {@code CONNECT} and that was the end of access control. With one shared document that was a
 * coherent simplification — the model read "any logged-in user may do anything", and it behaved
 * that way.
 *
 * <p>Since CP11 there are many documents, and the document identifier is part of the destination
 * supplied by the client. Without this interceptor any logged-in user could subscribe to
 * {@code /topic/doc/{somebody-elses-id}/ops} and read their document live, or send their own
 * operation to {@code /app/doc/{somebody-elses-id}/op}. Authentication without authorization
 * protects nothing but anonymity.
 *
 * <h2>Why this cannot be only a document check</h2>
 * The {@link Role#COMMENTER} role may read and comment but not change the text. Comments (CP14)
 * travel the same operation channel as editing, so the distinction between "may write" and "may
 * comment" <b>does not exist at the destination level</b> — it has to descend to the operation
 * type. The role-to-allowed-types mapping is here, in one place, precisely so that adding an
 * operation type in CP13 and CP14 is an entry in a table rather than a hunt for conditions
 * scattered across the code.
 */
@Component
public class DocumentAccessInterceptor implements ChannelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(DocumentAccessInterceptor.class);

    private final PermissionService permissions;
    private final RateLimiter rateLimiter = new RateLimiter();

    /**
     * Injected lazily for the same reason as {@code PresenceService} in
     * {@link StompConnectInterceptor}: this interceptor is part of the WebSocket infrastructure
     * that {@code SimpMessagingTemplate} itself comes from.
     */
    private final ObjectProvider<SimpMessagingTemplate> messaging;

    public DocumentAccessInterceptor(PermissionService permissions,
                                     ObjectProvider<SimpMessagingTemplate> messaging) {
        this.permissions = permissions;
        this.messaging = messaging;
    }

    /** Diagnostics and tests only — access to the token bucket counters. */
    public RateLimiter rateLimiter() {
        return rateLimiter;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor =
            MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }

        StompCommand command = accessor.getCommand();
        if (command == StompCommand.DISCONNECT) {
            // Without this the bucket maps grow with every session since process start.
            rateLimiter.forget(accessor.getSessionId());
            return message;
        }
        if (command != StompCommand.SUBSCRIBE && command != StompCommand.SEND) {
            return message;
        }

        String destination = accessor.getDestination();
        UUID documentId = documentIdOf(destination);
        if (documentId == null) {
            // Destinations unrelated to a document — the user's own queues (/user/queue/**). They
            // are private by definition: the broker delivers them only to the session that
            // subscribed.
            return message;
        }

        String userId = SessionAttributes.userId(accessor);
        Role role = permissions.roleOf(userId, documentId).orElse(null);

        if (role == null) {
            return deny(command, destination, userId, "no access at all");
        }
        if (command == StompCommand.SEND && !allowedToSend(role, destination, message)) {
            return deny(command, destination, userId, "role " + role.wire() + " does not permit this operation");
        }
        RateLimiter.Decision pace = command == StompCommand.SEND
            ? withinRateLimit(accessor.getSessionId(), destination)
            : new RateLimiter.Decision(true, 0);
        if (!pace.allowed()) {
            // A refusal for pace has to be AUDIBLE, unlike a refusal for permission. The reason is
            // asymmetric and worth writing down:
            //
            //   no permission  → the client must not learn the document exists,
            //   pace exceeded  → the client MUST learn, because its operation is waiting in the
            //                    buffer and without a signal nobody will ever retry it.
            //
            // A silent refusal for pace is DATA LOSS — found by the CP15 harness: seeding a large
            // document exhausted the burst, 115 operations were lost, and the document simply came
            // out shorter, with no trace in the interface.
            notifyRateLimited(accessor, destination, pace.retryAfterMillis());
            return deny(command, destination, userId, "send rate exceeded");
        }
        return message;
    }

    /**
     * Whether the session is within its rate limit.
     *
     * <p>Checked AFTER authorization, not before: a client without permission should be refused for
     * lack of access rather than for pace — otherwise the log message would state the wrong reason
     * when diagnosing.
     */
    private RateLimiter.Decision withinRateLimit(String sessionId, String destination) {
        long now = System.currentTimeMillis();
        return destination.endsWith("/presence")
            ? rateLimiter.allowPresence(sessionId, now)
            : rateLimiter.allowOperation(sessionId, now);
    }

    /**
     * Whether the role permits sending to this destination.
     *
     * <p>For reading (SUBSCRIBE) any role at all is enough — the lowest of them, {@code viewer},
     * reads by definition. Sending is decided per destination.
     */
    private static boolean allowedToSend(Role role, String destination, Message<?> message) {
        if (destination.endsWith("/op")) {
            // An editor may do anything — and we check that FIRST, so as not to pay for parsing
            // the operation body on the path most of the traffic takes.
            if (role.canWriteText()) {
                return true;
            }
            // A commenter may send ONLY a comment mark. This is the point where authorization has
            // to descend to the OPERATION TYPE: comments travel the same channel as editing, so
            // they cannot be distinguished at the destination level (ADR-0009). Without this the
            // "commenter" role would mean "editor".
            return role.canComment() && isCommentMark(message);
        }
        if (destination.endsWith("/presence")
            || destination.endsWith("/sync")
            || destination.endsWith("/progress")
            || destination.endsWith("/join")) {
            // Presence, resynchronisation and progress reports are a READER's actions. An
            // observer's cursor should be visible to everyone else — otherwise "somebody is here"
            // works only for people with write permission, which is misleading.
            return true;
        }
        // An unknown destination under /app/doc/** — refused. The default answer when extending
        // the protocol should be "not allowed", so a new channel has to be permitted deliberately
        // rather than discovered to have been permitted all along.
        return false;
    }

    /**
     * Whether the frame's payload is the application of a mark of type {@code comment}.
     *
     * <p>Parsing the operation body inside the interceptor is a cost, so it happens ONLY for the
     * commenter role — an editor never comes through here. We check exactly two fields, without
     * full deserialisation: the operation type and the mark type.
     *
     * <p>Removing a comment mark is deliberately NOT permitted here: removing somebody else's
     * anchor removes their conversation from the document, and that is an editor's privilege. A
     * commenter resolves a thread instead, which is reversible and leaves a trace.
     */
    private static boolean isCommentMark(Message<?> message) {
        Object payload = message.getPayload();
        if (!(payload instanceof byte[] bytes)) {
            return false;
        }
        try {
            JsonNode root = OpCodec.mapper().readTree(bytes);
            JsonNode op = root.path("op");
            return "m".equals(op.path("t").asText())
                && MarkTypes.COMMENT.equals(op.path("k").asText());
        } catch (IOException e) {
            // An unreadable payload is not a comment mark. A refusal rather than an exception: the
            // interceptor must not bring down the channel over one bad frame.
            return false;
        }
    }

    /**
     * Tells the client its operation was refused because of the rate limit.
     *
     * <p>The client holds unacknowledged operations in a buffer until the WRITE is confirmed
     * (ADR-0003), so it has something to retry — all it lacks is a signal that retrying is worth it.
     *
     * <p><b>Since CP20 the signal also carries the WAIT.</b> The token bucket knows it exactly
     * ({@code (1 - tokens) / refillPerMs}), and the client used to guess it — a hardcoded one
     * second. Sending back a number the server has anyway replaces a guess with a measurement.
     *
     * <p>What that did NOT do is improve the p99, and the README records the measurement: the
     * server-supplied wait alone made the tail worse, because the client then resent its whole
     * buffer every 17 ms. Exponential backoff on the client fixes that; the tail itself is bounded
     * by the refill rate, not by the retry delay.
     */
    private void notifyRateLimited(StompHeaderAccessor accessor, String destination,
                                   long retryAfterMillis) {
        try {
            messaging.getObject().convertAndSendToUser(
                accessor.getSessionId(), "/queue/errors",
                new Messages.ErrorMessage("RATE_LIMITED",
                    "too many operations per second — slow down and retry", retryAfterMillis),
                SessionAttributes.forUser(accessor));
        } catch (RuntimeException e) {
            log.warn("Failed to notify about the rate limit: {}", e.toString());
        }
    }

    private Message<?> deny(StompCommand command, String destination, String userId, String reason) {
        log.warn("Refused: {} {} for user {} — {}", command, destination, userId, reason);
        // Returning null stops the message on the channel. The client receives neither a
        // subscription confirmation nor any broadcasts, so from its point of view the document does
        // not exist. We deliberately send NO details: "this document exists but you have no access
        // to it" is itself a leak.
        return null;
    }

    /**
     * Extracts the document identifier from a destination {@code /topic/doc/{uuid}/…} or
     * {@code /app/doc/{uuid}/…}.
     *
     * @return {@code null} when the destination does not concern a document
     */
    // Public because EndpointAuthorizationArchitectureTest asks this parser directly whether
    // it can resolve a document from every registered @MessageMapping destination. Asserting
    // against a copy of the pattern would prove the copy correct, not the parser.
    public static UUID documentIdOf(String destination) {
        if (destination == null) {
            return null;
        }
        int marker = destination.indexOf("/doc/");
        if (marker < 0) {
            return null;
        }
        int start = marker + "/doc/".length();
        int end = destination.indexOf('/', start);
        String candidate = end < 0 ? destination.substring(start) : destination.substring(start, end);
        try {
            return UUID.fromString(candidate);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
