package dev.collabedit.app.ws;

import java.util.Map;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;

/**
 * Dane przypięte do sesji WebSocket przy nawiązaniu połączenia.
 *
 * <p>Uwierzytelnienie odbywa się <b>raz</b>, przy ramce {@code CONNECT}, a nie przy każdej
 * wiadomości. To jest istotna różnica wobec zwykłego HTTP: połączenie WebSocket żyje
 * godzinami, więc token sprawdzany przy każdej ramce byłby narzutem na najgorętszej ścieżce
 * w systemie. Ceną jest to, że <b>token unieważniony w trakcie sesji nie zrywa połączenia</b> —
 * świadome uproszczenie, opisane w README w sekcji „co zrobiłbym inaczej w produkcji".
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
     * Nagłówki potrzebne, żeby {@code convertAndSendToUser} trafiło do konkretnej sesji.
     *
     * <p>Bez ustawienia {@code sessionId} wiadomość poszłaby do <i>wszystkich</i> sesji
     * tego użytkownika — a użytkownik z dwiema otwartymi kartami dostałby odpowiedź
     * na resynchronizację również w tej karcie, która o nic nie prosiła.
     */
    public static MessageHeaders forUser(SimpMessageHeaderAccessor source) {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        accessor.setSessionId(source.getSessionId());
        accessor.setLeaveMutable(true);
        return accessor.getMessageHeaders();
    }
}
