package dev.collabedit.app.ws;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class RateLimiter {
    private static final class Bucket {
        private final double capacity;
        private final double refillPerMs;
        private double tokens;
        private long lastRefillMillis;

        Bucket(double capacity, double perSecond, long nowMillis) {
            this.capacity = capacity;
            this.refillPerMs = perSecond / 1000.0;
            this.tokens = capacity;
            this.lastRefillMillis = nowMillis;
        }

        boolean tryConsume(long nowMillis) {
            tokens = Math.min(capacity, tokens + (nowMillis - lastRefillMillis) * refillPerMs);
            lastRefillMillis = nowMillis;
            if (tokens < 1.0) {
                return false;
            }
            tokens -= 1.0;
            return true;
        }

        long millisUntilToken() {
            if (tokens >= 1.0) {
                return 0;
            }
            return (long) Math.ceil((1.0 - tokens) / refillPerMs);
        }
    }

    private static final double OPS_PER_SECOND = 60;
    private static final double OPS_BURST = 200;

    private static final double PRESENCE_PER_SECOND = 20;
    private static final double PRESENCE_BURST = 40;

    private final Map<String, Bucket> operations = new ConcurrentHashMap<>();
    private final Map<String, Bucket> presence = new ConcurrentHashMap<>();

    public record Decision(boolean allowed, long retryAfterMillis) {
    }

    public Decision allowOperation(String sessionId, long nowMillis) {
        return decide(operations.computeIfAbsent(
            sessionId, id -> new Bucket(OPS_BURST, OPS_PER_SECOND, nowMillis)), nowMillis);
    }

    public Decision allowPresence(String sessionId, long nowMillis) {
        return decide(presence.computeIfAbsent(
            sessionId, id -> new Bucket(PRESENCE_BURST, PRESENCE_PER_SECOND, nowMillis)), nowMillis);
    }

    private static Decision decide(Bucket bucket, long nowMillis) {
        return bucket.tryConsume(nowMillis)
            ? new Decision(true, 0)
            : new Decision(false, bucket.millisUntilToken());
    }

    public void forget(String sessionId) {
        operations.remove(sessionId);
        presence.remove(sessionId);
    }

    public int trackedBuckets() {
        return operations.size() + presence.size();
    }
}
