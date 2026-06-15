package dev.collabedit.app.ws;

import java.util.Map;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;

/**
 * Data attached to a WebSocket session when the connection is established.
 *
 * <p>Authentication happens <b>once</b>, at the {@code CONNECT} frame, rather than on every
 * message. That is a meaningful difference from ordinary HTTP: a WebSocket connection lives for
 * hours, so checking the token on every frame would be overhead on the hottest path in the system.
 * The price is that <b>a token revoked mid-session does not tear the connection down</b> — a
 * deliberate simplification, described in the README's "what I would do differently in production".
 */
public final class SessionAttributes {

    public static final String USER_ID = "userId";
    public static final String DISPLAY_NAME = "displayName";
    public static final String COLOR = "color";
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

    /**
     * The headers needed for {@code convertAndSendToUser} to reach one specific session.
     *
     * <p>Without setting {@code sessionId} the message would go to <i>every</i> session belonging
     * to that user — and a user with two tabs open would receive a resynchronisation response in
     * the tab that asked for nothing.
     */
    public static MessageHeaders forUser(SimpMessageHeaderAccessor source) {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        accessor.setSessionId(source.getSessionId());
        accessor.setLeaveMutable(true);
        return accessor.getMessageHeaders();
    }
}
