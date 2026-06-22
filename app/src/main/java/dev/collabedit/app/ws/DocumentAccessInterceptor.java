package dev.collabedit.app.ws;

import com.fasterxml.jackson.databind.JsonNode;
import dev.collabedit.app.auth.PermissionService;
import dev.collabedit.app.observability.DocumentMetrics;
import dev.collabedit.app.auth.Role;
import dev.collabedit.crdt.MarkTypes;
import dev.collabedit.crdt.OpCodec;
import java.io.IOException;
import java.util.Locale;
import java.util.Set;
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

    /**
     * The only destinations that legitimately carry no document identifier.
     *
     * <p>Spelled out rather than matched by prefix, because a prefix rule is what let
     * {@code /queue/**} through: Spring rewrites a {@code /user/queue/x} subscription into a
     * session-suffixed {@code /queue/x-user<sessionId>} at the broker, so <b>{@code /queue/**} is a
     * pattern that matches other people's private queues</b>. An exact set cannot drift into that.
     *
     * <p>Kept in step with {@code convertAndSendToUser} — the three destinations the server
     * actually publishes to — by {@code DocumentAccessInterceptorTest}.
     */
    static final Set<String> PRIVATE_USER_QUEUES = Set.of(
        "/user/queue/session",
        "/user/queue/sync",
        "/user/queue/errors");

    /**
     * The prefix a client may PUBLISH to, and the only one.
     *
     * <p>The same string {@code WebSocketConfig.setApplicationDestinationPrefixes} registers.
     * Everything else the broker knows — {@code /topic}, {@code /queue}, {@code /user} — is an
     * OUTBOUND prefix: the server sends there and clients subscribe. A SEND to one of those goes
     * straight into the broker's fan-out with the client's own payload in it, which is what the
     * check in {@code preSend} exists to stop.
     */
    static final String APPLICATION_PREFIX = "/app/";

    /**
     * Why a frame was refused — a CLOSED set, because it becomes a metric tag.
     *
     * <p>An open set of reasons taken from the frame would let a client mint metric series at
     * will, which is the cardinality hazard {@code SessionCloseListener} already guards against by
     * collapsing unknown close codes.
     */
    enum Refusal {
        WILDCARD_DESTINATION("wildcard destination"),
        UNKNOWN_DESTINATION("destination carries no document identifier"),
        NO_ACCESS("no access at all"),
        ROLE("the role does not permit this operation"),
        RATE("send rate exceeded");

        private final String description;

        Refusal(String description) {
            this.description = description;
        }
    }

    private final PermissionService permissions;
    private final DocumentMetrics metrics;

    /**
     * Shared with {@link SessionLifecycle}, which is what forgets a session's buckets.
     *
     * <p>This class used to own the instance outright and clean it up on the inbound
     * {@code DISCONNECT} frame — one step of session teardown living inside the authorization
     * class, with nothing showing its order relative to the other two steps that also fired on
     * that event.
     */
    private final RateLimiter rateLimiter;

    /**
     * Injected lazily for the same reason as {@code PresenceService} in
     * {@link StompConnectInterceptor}: this interceptor is part of the WebSocket infrastructure
     * that {@code SimpMessagingTemplate} itself comes from.
     */
    private final ObjectProvider<SimpMessagingTemplate> messaging;

    public DocumentAccessInterceptor(PermissionService permissions, DocumentMetrics metrics,
                                     RateLimiter rateLimiter,
                                     ObjectProvider<SimpMessagingTemplate> messaging) {
        this.permissions = permissions;
        this.metrics = metrics;
        this.rateLimiter = rateLimiter;
        this.messaging = messaging;
    }

    // rateLimiter used to sit here as a public accessor "for diagnostics and tests", with no
    // caller in either. RateLimiterTest builds its own instance, which is the right way to test a
    // token bucket.

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor =
            MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }

        StompCommand command = accessor.getCommand();
        // DISCONNECT is no longer handled here. Forgetting the session's buckets is one step of
        // session teardown, and all three of them now happen in SessionLifecycle, in an order a
        // reader can see. This class decides whether a frame may act, and nothing else.
        if (command != StompCommand.SUBSCRIBE && command != StompCommand.SEND) {
            return message;
        }

        String destination = accessor.getDestination();
        String userId = SessionAttributes.userId(accessor);

        // No destination at all — a malformed frame. Refused here rather than left to fall through:
        // the allow-list below is a Set.of(...), which throws on contains(null), so a missing header
        // would have become a NullPointerException inside the interceptor rather than a refusal.
        if (destination == null) {
            return deny(command, "<none>", userId, Refusal.UNKNOWN_DESTINATION);
        }

        // A destination carrying a wildcard is refused before anything else, whether or not a
        // document identifier can be parsed out of it. Nothing in this protocol subscribes to a
        // pattern — every destination the client uses is spelled out in `connection.ts` — so the
        // only thing a wildcard can express here is an attempt to widen the subscription past what
        // the check below is able to reason about.
        if (destination.indexOf('*') >= 0 || destination.indexOf('?') >= 0) {
            return deny(command, destination, userId, Refusal.WILDCARD_DESTINATION);
        }

        // A SEND may only ever go to the APPLICATION prefix. Nothing below this line checks that,
        // and that was a hole with the same shape as the CP12 wildcard bypass.
        //
        // `allowedToSend` decides on the destination's SUFFIX — "/op", "/presence", "/sync" — and
        // never looks at its head. The rules were written with `/app/doc/{id}/…` in mind, but
        // `documentIdOf` parses `/topic/doc/{uuid}/…` just as happily (its javadoc says so, by
        // design, because the same parser authorises SUBSCRIBE), and `/topic` is a BROKER prefix:
        // WebSocketConfig calls enableSimpleBroker("/topic", "/queue"). So a SEND to
        // /topic/doc/{id}/presence matched the "/presence" rule, which returns true for every role,
        // and Spring's SimpleBrokerMessageHandler fanned the client's own payload out to every
        // subscriber of that topic.
        //
        // What that buys an attacker is the participant list of every client in the document:
        // impersonated names, colours and cursors, or an empty room, or complete:false — the flag
        // whose whole purpose is to tell the user the list may be wrong. Any role at all is enough,
        // including viewer.
        //
        // /topic/doc/{id}/ops escaped only because "/ops" does not end with "/op". That is luck,
        // not a rule, and one renamed channel away from being an operation-injection hole.
        //
        // Refused before documentIdOf rather than inside allowedToSend, because the question is not
        // about the document: a client has no business publishing to a broker destination at all.
        // Every destination the client publishes to is spelled out in `connection.ts`, and all of
        // them are under /app/.
        if (command == StompCommand.SEND && !destination.startsWith(APPLICATION_PREFIX)) {
            return deny(command, destination, userId, Refusal.UNKNOWN_DESTINATION);
        }

        UUID documentId = documentIdOf(destination);
        if (documentId == null) {
            // Destinations unrelated to a document. This used to let the frame THROUGH, on the
            // reasoning that anything without a document identifier must be one of the user's own
            // queues, which the broker delivers only to the session that subscribed.
            //
            // The premise was false, and it cost the whole of CP12. `enableSimpleBroker` installs
            // Spring's DefaultSubscriptionRegistry, which matches subscription destinations as ANT
            // PATTERNS. `documentIdOf("/topic/**")` returns null, so the frame was waved through and
            // then matched every document's broadcast — SUBSCRIBE /topic/** delivered the operations,
            // acknowledgements and presence of every document open on this instance, and
            // SUBSCRIBE /queue/** delivered other sessions' sync responses. Login is passwordless by
            // design, so the whole path needed no account and nothing was logged, because
            // deny was never reached.
            //
            // The rule is now the same one allowedToSend already applies: an unrecognised
            // destination is refused. A new private channel has to be permitted deliberately rather
            // than discovered to have been permitted all along.
            return PRIVATE_USER_QUEUES.contains(destination)
                ? message
: deny(command, destination, userId, Refusal.UNKNOWN_DESTINATION);
        }
        Role role = permissions.roleOf(userId, documentId).orElse(null);

        if (role == null) {
            return deny(command, destination, userId, Refusal.NO_ACCESS);
        }
        if (command == StompCommand.SEND && !allowedToSend(role, destination, message)) {
            return deny(command, destination, userId, Refusal.ROLE);
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
            return deny(command, destination, userId, Refusal.RATE);
        }
        return message;
    }

    /**
     * Whether the session is within its rate limit.
     *
     * <p>Checked AFTER authorization, not before: a client without permission should be refused for
     * lack of access rather than for pace — otherwise the log message would state the wrong reason
     * when diagnosing.
     *
     * <p>SEND only, deliberately. SUBSCRIBE is not paced because there is nothing left for it to
     * protect: a wildcard destination is refused outright, an unrecognised one is refused, and a
     * document destination costs one entry in the broker's per-session registry, reclaimed on
     * disconnect. A third token bucket would be machinery guarding a bound that already exists.
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
     *
     * <p><b>Every destination reaching this method is already known to start with {@code /app/}</b>,
     * and that precondition is enforced in {@link #preSend} rather than here. It matters because
     * every rule below matches on a SUFFIX: without the prefix check, {@code /topic/doc/{id}/
     * presence} matched the presence rule and the client's payload went into the broker's fan-out.
     * Adding the head of the destination to each rule would work too and would have to be
     * remembered for every future one; a single check at the door cannot be forgotten per rule.
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
            // they cannot be distinguished at the destination level. Without this the
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
     *, so it has something to retry — all it lacks is a signal that retrying is worth it.
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

    /**
     * Stops the frame on the channel, counts it, and logs it at a level matched to what it means.
     *
     * <p>WARN for an authorization refusal — rare, and something a reader of the log should see.
     * DEBUG for a rate refusal, which is a NORMAL back-pressure signal on a busy document: the
     * CP15 harness produced 1614 of them in one run, and 1614 WARN lines from a hot inbound path
     * bury everything else. The count is what carries that information now, and it is a number
     * rather than a line.
     */
    private Message<?> deny(StompCommand command, String destination, String userId,
                            Refusal reason) {
        metrics.recordFrameRefused(reason.name().toLowerCase(Locale.ROOT));
        if (reason == Refusal.RATE) {
            log.debug("Refused: {} {} for user {} — {}",
                command, destination, userId, reason.description);
        } else {
            log.warn("Refused: {} {} for user {} — {}",
                command, destination, userId, reason.description);
        }
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
