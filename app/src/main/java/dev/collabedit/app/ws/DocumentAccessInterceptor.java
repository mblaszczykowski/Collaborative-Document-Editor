package dev.collabedit.app.ws;

import com.fasterxml.jackson.databind.JsonNode;
import dev.collabedit.app.auth.CommentAnchorAuthority;
import dev.collabedit.app.auth.PermissionService;
import dev.collabedit.app.auth.Role;
import dev.collabedit.app.observability.DocumentMetrics;
import dev.collabedit.crdt.MarkTypes;
import dev.collabedit.crdt.OpCodec;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

@Component
public class DocumentAccessInterceptor implements ChannelInterceptor {
    private static final Logger log = LoggerFactory.getLogger(DocumentAccessInterceptor.class);

    static final Set<String> PRIVATE_USER_QUEUES = Set.of(
        "/user/queue/session",
        "/user/queue/sync",
        "/user/queue/errors");

    static final String APPLICATION_PREFIX = "/app/";

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

    private final RateLimiter rateLimiter;

    private final ObjectProvider<SimpMessagingTemplate> messaging;

    private final CommentAnchorAuthority anchors;

    public DocumentAccessInterceptor(PermissionService permissions, DocumentMetrics metrics,
                                     RateLimiter rateLimiter,
                                     ObjectProvider<SimpMessagingTemplate> messaging,
                                     CommentAnchorAuthority anchors) {
        this.permissions = permissions;
        this.metrics = metrics;
        this.rateLimiter = rateLimiter;
        this.messaging = messaging;
        this.anchors = anchors;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor =
            MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }

        StompCommand command = accessor.getCommand();
        if (command != StompCommand.SUBSCRIBE && command != StompCommand.SEND) {
            return message;
        }

        String destination = accessor.getDestination();
        String userId = SessionAttributes.userId(accessor);

        if (destination == null) {
            return deny(command, "<none>", userId, Refusal.UNKNOWN_DESTINATION);
        }

        if (destination.indexOf('*') >= 0 || destination.indexOf('?') >= 0) {
            return deny(command, destination, userId, Refusal.WILDCARD_DESTINATION);
        }

        if (command == StompCommand.SEND && !destination.startsWith(APPLICATION_PREFIX)) {
            return deny(command, destination, userId, Refusal.UNKNOWN_DESTINATION);
        }

        if (command == StompCommand.SEND && "/app/session".equals(destination)) {
            if (userId == null) { return null; }
            RateLimiter.Decision pace = rateLimiter.allowOperation(accessor.getSessionId(), System.currentTimeMillis());
            if (!pace.allowed()) {
                notifyRateLimited(accessor, destination, pace.retryAfterMillis());
                return null;
            }
            return message;
        }
        UUID documentId = documentIdOf(destination);
        if (documentId == null) {
            return PRIVATE_USER_QUEUES.contains(destination)
                ? message
: deny(command, destination, userId, Refusal.UNKNOWN_DESTINATION);
        }
        Role role;
        try {
            role = permissions.roleOf(userId, documentId).orElse(null);
        } catch (RuntimeException e) {
            notifyError(accessor, "ACCESS_UNAVAILABLE", "Access could not be verified. Retry after the server recovers.");
            return null;
        }

        if (role == null) {
            notifyError(accessor, "ACCESS_DENIED", "Your access to this document has ended. Pending work was not accepted.");
            return deny(command, destination, userId, Refusal.NO_ACCESS);
        }
        if (command == StompCommand.SEND
            && !allowedToSend(role, destination, message, userId, documentId)) {
            notifyError(accessor, "ACCESS_DENIED", "Your current role does not permit this change. Pending work was not accepted.");
            return deny(command, destination, userId, Refusal.ROLE);
        }
        RateLimiter.Decision pace = command == StompCommand.SEND
            ? withinRateLimit(accessor.getSessionId(), destination)
            : new RateLimiter.Decision(true, 0);
        if (!pace.allowed()) {
            notifyRateLimited(accessor, destination, pace.retryAfterMillis());
            return deny(command, destination, userId, Refusal.RATE);
        }
        return message;
    }

    private RateLimiter.Decision withinRateLimit(String sessionId, String destination) {
        long now = System.currentTimeMillis();
        return destination.endsWith("/presence")
            ? rateLimiter.allowPresence(sessionId, now)
            : rateLimiter.allowOperation(sessionId, now);
    }

    private boolean allowedToSend(Role role, String destination, Message<?> message,
                                  String userId, UUID documentId) {
        if (destination.endsWith("/op")) {
            if (role.canWriteText()) {
                return true;
            }
            if (!role.canComment()) {
                return false;
            }
            UUID thread = commentThreadOf(message);
            return thread != null && anchors.mayAnchorThread(userId, documentId, thread);
        }
        if (destination.endsWith("/presence")
            || destination.endsWith("/sync")
            || destination.endsWith("/progress")
            || destination.endsWith("/join")) {
            return true;
        }
        return false;
    }

    private static UUID commentThreadOf(Message<?> message) {
        Object payload = message.getPayload();
        if (!(payload instanceof byte[] bytes)) {
            return null;
        }
        try {
            JsonNode root = OpCodec.mapper().readTree(bytes);
            JsonNode op = root.path("op");
            if (!"m".equals(op.path("t").asText())
                || !MarkTypes.COMMENT.equals(op.path("k").asText())) {
                return null;
            }
            JsonNode value = op.path("v");
            return value.isTextual() ? UUID.fromString(value.asText()) : null;
        } catch (IOException | IllegalArgumentException e) {
            return null;
        }
    }

    void notifyExpired(StompHeaderAccessor accessor) {
        Map<String, Object> attributes = accessor.getSessionAttributes();
        if (attributes != null && Boolean.TRUE.equals(attributes.get(SessionAttributes.EXPIRED_NOTIFIED))) {
            return;
        }
        notifyError(accessor, "SESSION_EXPIRED", "Your session expired. Sign in again to send your pending work.");
        if (attributes != null) { attributes.put(SessionAttributes.EXPIRED_NOTIFIED, true); }
    }

    private void notifyError(StompHeaderAccessor accessor, String code, String explanation) {
        try {
            messaging.getObject().convertAndSendToUser(accessor.getSessionId(), "/queue/errors",
                new Messages.ErrorMessage(code, explanation), SessionAttributes.forUser(accessor));
        } catch (RuntimeException e) {
            log.debug("Could not deliver {} to session {}", code, accessor.getSessionId());
        }
    }

    private void notifyRateLimited(StompHeaderAccessor accessor, String destination,
                                   long retryAfterMillis) {
        try {
            messaging.getObject().convertAndSendToUser(
                accessor.getSessionId(), "/queue/errors",
                new Messages.ErrorMessage("RATE_LIMITED",
                    "too many operations per second - slow down and retry", retryAfterMillis),
                SessionAttributes.forUser(accessor));
        } catch (RuntimeException e) {
            log.warn("Failed to notify about the rate limit: {}", e.toString());
        }
    }

    private Message<?> deny(StompCommand command, String destination, String userId,
                            Refusal reason) {
        metrics.recordFrameRefused(reason.name().toLowerCase(Locale.ROOT));
        if (reason == Refusal.RATE) {
            log.debug("Refused: {} {} for user {} - {}",
                command, destination, userId, reason.description);
        } else {
            log.warn("Refused: {} {} for user {} - {}",
                command, destination, userId, reason.description);
        }
        return null;
    }

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
