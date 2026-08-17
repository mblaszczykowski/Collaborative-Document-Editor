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

    public RevokedAccessFilter(PermissionService permissions, SessionLifecycle sessions,
                               DocumentMetrics metrics) {
        this.permissions = permissions;
        this.sessions = sessions;
        this.metrics = metrics;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        SimpMessageHeaderAccessor accessor =
            MessageHeaderAccessor.getAccessor(message, SimpMessageHeaderAccessor.class);
        if (accessor == null || accessor.getMessageType() != SimpMessageType.MESSAGE) {
            return message;
        }

        UUID documentId = DocumentAccessInterceptor.documentIdOf(accessor.getDestination());
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
                + "delivering it rather than stopping the document's broadcasts", documentId,
                userId, e);
            return message;
        }

        if (!stillPermitted) {
            metrics.recordOutboundDrop(REVOKED);
            log.info("Dropping a broadcast of document {} to user {}: their access has been "
                + "revoked", documentId, userId);
            return null;
        }
        return message;
    }
}
