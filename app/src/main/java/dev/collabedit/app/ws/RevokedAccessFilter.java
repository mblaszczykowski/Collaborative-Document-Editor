package dev.collabedit.app.ws;

import dev.collabedit.app.auth.PermissionService;
import dev.collabedit.app.observability.DocumentMetrics;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

@Component
public class RevokedAccessFilter implements ChannelInterceptor {
    private static final String ACCESS_SUFFIX = "/access";

    private static final Logger log = LoggerFactory.getLogger(RevokedAccessFilter.class);

    static final String REVOKED = "revoked";
    static final String UNKNOWN_SESSION = "unknown_session";

    private final PermissionService permissions;
    private final SessionLifecycle sessions;
    private final DocumentMetrics metrics;
    private final org.springframework.beans.factory.ObjectProvider<org.springframework.messaging.simp.SimpMessagingTemplate> messaging;

    public RevokedAccessFilter(PermissionService permissions, SessionLifecycle sessions,
                               DocumentMetrics metrics,
                               org.springframework.beans.factory.ObjectProvider<org.springframework.messaging.simp.SimpMessagingTemplate> messaging) {
        this.permissions = permissions;
        this.sessions = sessions;
        this.metrics = metrics;
        this.messaging = messaging;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        SimpMessageHeaderAccessor accessor =
            MessageHeaderAccessor.getAccessor(message, SimpMessageHeaderAccessor.class);
        if (accessor == null || accessor.getMessageType() != SimpMessageType.MESSAGE) {
            return message;
        }

        String destination = accessor.getDestination();
        boolean errorQueue = destination != null && destination.contains("/queue/errors");
        if (sessions.expired(accessor.getSessionId()) && !errorQueue) {
            notifyError(accessor, "SESSION_EXPIRED", "Your session expired. Sign in again to send pending work.");
            metrics.recordOutboundDrop("session_expired");
            return null;
        }

        UUID documentId = DocumentAccessInterceptor.documentIdOf(destination);
        if (documentId == null && accessor.getHeader(SessionAttributes.DOCUMENT_ID) instanceof UUID tagged) {
            documentId = tagged;
        }
        if (documentId == null) {
            return message;
        }

        if (accessor.getDestination().endsWith(ACCESS_SUFFIX)) {
            return message;
        }

        String sessionId = accessor.getSessionId();
        String userId = sessions.userOf(sessionId);
        if (userId == null) {
            metrics.recordOutboundDrop(UNKNOWN_SESSION);
            log.debug("Dropping a frame for document {} to session {}: no identity for that "
                + "session", documentId, sessionId);
            return null;
        }

        boolean stillPermitted;
        try {
            stillPermitted = permissions.roleOf(userId, documentId).isPresent();
        } catch (RuntimeException e) {
            log.warn("Could not check access to document {} for user {} while delivering a frame; "
                + "dropping content until authorization is available", documentId,
                userId, e);
            notifyError(accessor, "ACCESS_UNAVAILABLE", "Document updates are paused while access cannot be verified. Reconnect when the server recovers.");
            metrics.recordOutboundDrop("authorization_unavailable");
            return null;
        }

        if (!stillPermitted) {
            metrics.recordOutboundDrop(REVOKED);
            log.info("Dropping a broadcast of document {} to user {}: their access has been "
                + "revoked", documentId, userId);
            return null;
        }
        return message;
    }
    private void notifyError(SimpMessageHeaderAccessor accessor, String code, String explanation) {
        if (!sessions.shouldNotify(accessor.getSessionId(), code)) { return; }
        try {
            messaging.getObject().convertAndSendToUser(accessor.getSessionId(), "/queue/errors",
                new Messages.ErrorMessage(code, explanation), SessionAttributes.forUser(accessor));
        } catch (RuntimeException e) {
            log.debug("Could not report {} to session {}", code, accessor.getSessionId());
        }
    }

}
