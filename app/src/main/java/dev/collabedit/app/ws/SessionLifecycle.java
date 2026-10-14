package dev.collabedit.app.ws;

import dev.collabedit.app.observability.DocumentMetrics;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

@Component
public class SessionLifecycle {
    private static final Logger log = LoggerFactory.getLogger(SessionLifecycle.class);

    private final Map<String, String> userBySession = new ConcurrentHashMap<>();

    private final Map<String, Map<String, Long>> noticesBySession = new ConcurrentHashMap<>();

    boolean shouldNotify(String sessionId, String code) {
        if (sessionId == null || !userBySession.containsKey(sessionId)) { return false; }
        Map<String, Long> notices = noticesBySession.computeIfAbsent(sessionId, id -> new ConcurrentHashMap<>());
        boolean[] send = new boolean[1];
        long now = System.currentTimeMillis();
        notices.compute(code, (key, previous) -> {
            if (previous == null || now - previous >= 30_000) { send[0] = true; return now; }
            return previous;
        });
        return send[0];
    }

    private final Map<String, Long> expiresBySession = new ConcurrentHashMap<>();

    public boolean expired(String sessionId) {
        Long expiry = sessionId == null ? null : expiresBySession.get(sessionId);
        return expiry != null && expiry <= java.time.Instant.now().getEpochSecond();
    }

    void begin(String sessionId, String userId, long expiresAt) {
        begin(sessionId, userId);
        if (sessionId != null) { expiresBySession.put(sessionId, expiresAt); }
    }

    private final Map<Integer, String> userByReplica = new ConcurrentHashMap<>();

    private final RateLimiter rateLimiter;

    private final ObjectProvider<PresenceService> presence;
    private final DocumentMetrics metrics;

    public SessionLifecycle(RateLimiter rateLimiter, ObjectProvider<PresenceService> presence,
                            DocumentMetrics metrics) {
        this.rateLimiter = rateLimiter;
        this.presence = presence;
        this.metrics = metrics;
    }

    void begin(String sessionId, String userId) {
        if (sessionId == null) {
            log.warn("A CONNECT frame arrived with no session id - the session cannot be tracked");
            return;
        }
        userBySession.put(sessionId, userId);
        metrics.sessionOpened();
    }

    public String userOf(String sessionId) {
        return sessionId == null ? null : userBySession.get(sessionId);
    }

    public String userOfReplica(int replicaId) {
        return userByReplica.get(replicaId);
    }

    private final Map<String, Integer> replicaBySession = new ConcurrentHashMap<>();

    void joined(String sessionId, UUID documentId, int replicaId, String userId,
                String displayName, String color) {
        UUID[] leftBehind = new UUID[1];
        boolean live = userBySession.computeIfPresent(sessionId, (id, user) -> {
            leftBehind[0] = presence.getObject()
                .recordJoin(sessionId, documentId, replicaId, userId, displayName, color);
            replicaBySession.put(sessionId, replicaId);
            userByReplica.put(replicaId, userId);
            return user;
        }) != null;

        if (!live) {
            log.debug("Ignoring a join for session {}, which has already ended", sessionId);
            return;
        }
        if (leftBehind[0] != null) {
            presence.getObject().announce(leftBehind[0]);
        }
        presence.getObject().announce(documentId);
    }

    public void end(String sessionId) {
        if (sessionId == null) {
            return;
        }
        boolean[] ended = new boolean[1];
        PresenceService.Participant[] gone = new PresenceService.Participant[1];
        userBySession.computeIfPresent(sessionId, (id, user) -> {
            ended[0] = true;
            rateLimiter.forget(sessionId);
            expiresBySession.remove(sessionId);
            noticesBySession.remove(sessionId);
            Integer replicaId = replicaBySession.remove(sessionId);
            if (replicaId != null) {
                userByReplica.remove(replicaId, user);
            }
            gone[0] = presence.getObject().recordLeave(sessionId);
            return null;
        });

        if (!ended[0]) {
            return;
        }
        metrics.sessionClosed();
        if (gone[0] != null) {
            try {
                presence.getObject().recordDeparture(gone[0]);
            } catch (RuntimeException e) {
                log.warn("Session {} ended but its departure could not be recorded durably; "
                    + "replica {} will stop constraining GC early", sessionId, gone[0].replicaId(), e);
            }
            presence.getObject().announce(gone[0].documentId());
        }
    }

    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        String reason = reasonOf(event.getCloseStatus());
        metrics.recordSessionClose(reason);

        if (SLOW_CLIENT.equals(reason)) {
            log.warn("Session {} torn down: the client could not keep up with delivery and "
                    + "overflowed the send buffer (send-buffer-size-bytes, the transport decision). It will come "
                    + "back through reconnect + resync, but until then it RECEIVES NO broadcasts.",
                event.getSessionId());
        }
        end(event.getSessionId());
    }

    static final String SLOW_CLIENT = "slow_client_buffer_overflow";

    static String reasonOf(CloseStatus status) {
        if (status == null) {
            return "unknown";
        }
        return switch (status.getCode()) {
            case 1000 -> "normal";
            case 1001 -> "going_away";
            case 1006 -> "abnormal";
            case 1011 -> "server_error";
            case 4500 -> SLOW_CLIENT;
            default -> "other";
        };
    }
}
