package dev.collabedit.app.ws;

import java.util.Map;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;

public final class SessionAttributes {
    public static final String USER_ID = "userId";
    public static final String DISPLAY_NAME = "displayName";
    public static final String COLOR = "color";
    public static final String EXPIRES_AT = "expiresAtEpochSecond";
    public static final String EXPIRED_NOTIFIED = "expiredNotified";

    public static boolean expired(SimpMessageHeaderAccessor headers) {
        Map<String, Object> attributes = headers.getSessionAttributes();
        Object expiry = attributes == null ? null : attributes.get(EXPIRES_AT);
        return expiry instanceof Long value && value <= java.time.Instant.now().getEpochSecond();
    }

    public static final String REPLICA_ID = "replicaId";

    private SessionAttributes() {
    }

    public static String userId(SimpMessageHeaderAccessor headers) {
        return string(headers, USER_ID);
    }

    public static String displayName(SimpMessageHeaderAccessor headers) {
        return string(headers, DISPLAY_NAME);
    }

    public static String color(SimpMessageHeaderAccessor headers) {
        return string(headers, COLOR);
    }

    public static int replicaId(SimpMessageHeaderAccessor headers) {
        Map<String, Object> attributes = headers.getSessionAttributes();
        Object value = attributes == null ? null : attributes.get(REPLICA_ID);
        return value instanceof Integer id ? id : 0;
    }

    private static String string(SimpMessageHeaderAccessor headers, String key) {
        Map<String, Object> attributes = headers.getSessionAttributes();
        Object value = attributes == null ? null : attributes.get(key);
        return value == null ? null : value.toString();
    }

    public static final String DOCUMENT_ID = "collabedit.documentId";

    public static MessageHeaders forDocument(SimpMessageHeaderAccessor source, java.util.UUID documentId) {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        accessor.setSessionId(source.getSessionId());
        accessor.setHeader(DOCUMENT_ID, documentId);
        accessor.setLeaveMutable(true);
        return accessor.getMessageHeaders();
    }

    public static MessageHeaders forUser(SimpMessageHeaderAccessor source) {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        accessor.setSessionId(source.getSessionId());
        accessor.setLeaveMutable(true);
        return accessor.getMessageHeaders();
    }
}
