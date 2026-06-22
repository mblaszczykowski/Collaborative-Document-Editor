package dev.collabedit.app.ws;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Rate limiting of inbound frames, separately for each session (CP12).
 *
 * <h2>What this protects against — and what it does not</h2>
 * A client sending operations in a loop loads the in-memory replica, the operation log and the
 * broadcast to every other participant. One such client can therefore ruin a document for
 * everyone, and it need not be malicious — a loop in code someone wrote on their own client is
 * enough.
 *
 * <p><b>This is not protection against a distributed attack.</b> The limit is per session, and
 * sessions can be multiplied. It protects against one broken or malicious browser tab, which is the
 * realistic case; defending against an attack from many sources is the job of the layer in front of
 * the application, not of the application.
 *
 * <h2>Why two separate buckets</h2>
 * Operations and presence have completely different profiles. An operation is produced at most as
 * fast as a person types (a dozen or so per second at peak), whereas presence — with an arrow key
 * held down — runs to several dozen. One shared limit would have to be high enough to fit presence,
 * and would thereby stop limiting operations, that is, the path that actually costs something.
 *
 * <h2>A token bucket, not a window</h2>
 * A fixed window ("N per second") lets 2N frames through at the boundary between two windows and
 * rejects traffic that did not fit, even when the previous second was empty. A token bucket allows
 * short bursts (pasting text, coming back from a disconnect with a buffer of operations) and limits
 * only the AVERAGE rate — which is exactly what we want to limit.
 *
 * <h2>Why this is a bean now</h2>
 * It used to be a {@code new RateLimiter()} inside {@link DocumentAccessInterceptor}, which is the
 * only place that CONSUMES tokens — and it was also the place that FORGOT a session's buckets, on
 * the inbound {@code DISCONNECT} frame, because that was the only place holding a reference. That
 * put one step of session teardown inside a class about authorization, where nothing showed its
 * order relative to the other two steps. {@link SessionLifecycle} owns that order now, and it needs
 * to reach the same instance. Unit tests still construct one directly; nothing about the mechanism
 * requires a container.
 */
@Component
public class RateLimiter {

    /**
     * A token bucket refilled continuously.
     *
     * <p>It is not synchronised. One session's frames arrive over an inbound channel that may have
     * several threads, so a race on two simultaneous frames of the same session is theoretically
     * possible. The effect would be one frame let through over the limit — which is immaterial to a
     * mechanism that limits the average rate. Synchronisation would cost more here than it adds.
     */
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

        /**
         * How long until this bucket has a token again.
         *
         * <p>The bucket already knows this exactly — it is what {@code refillPerMs} means — and
         * before CP20 it kept the answer to itself while the client guessed. The guess was a
         * hardcoded one second, which is what the README's p99 of 1299 ms is made of.
         *
         * <p>Assumes {@code tryConsume} has just refilled at this instant, which is the only
         * place it is called from.
         */
        long millisUntilToken() {
            if (tokens >= 1.0) {
                return 0;
            }
            return (long) Math.ceil((1.0 - tokens) / refillPerMs);
        }
    }

    /**
     * Operations: 60 per second, bursting to 200.
     *
     * <p>60/s is clearly above human typing speed (record holders reach ~15 characters per second,
     * and each character is one operation), so it will not touch anyone who is writing. A burst of
     * 200 accommodates pasting a paragraph and retransmitting the buffer after coming back from
     * offline — two situations in which a legitimate client sends a lot at once.
     */
    private static final double OPS_PER_SECOND = 60;
    private static final double OPS_BURST = 200;

    /**
     * Presence: 20 per second, bursting to 40.
     *
     * <p>The client throttles itself to one report per 100 ms (see {@code Editor.tsx}), that is,
     * 10/s. A limit twice that leaves headroom for jitter and does not punish a client for frames
     * arriving unevenly — but does stop a client that has no such throttle because it is not our
     * browser.
     */
    private static final double PRESENCE_PER_SECOND = 20;
    private static final double PRESENCE_BURST = 40;

    private final Map<String, Bucket> operations = new ConcurrentHashMap<>();
    private final Map<String, Bucket> presence = new ConcurrentHashMap<>();

    /**
     * Whether the frame is allowed, and if not, how long until one would be.
     *
     * <p>The wait is part of the answer rather than a separate query, because asking twice
     * would refill the bucket in between and produce a number that was never true.
     *
     * @param retryAfterMillis 0 when {@code allowed}
     */
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

    /** Cleanup on disconnect — without it the maps grow with the number of sessions since process start. */
    public void forget(String sessionId) {
        operations.remove(sessionId);
        presence.remove(sessionId);
    }

    /**
     * The number of live buckets — <b>not</b> the number of sessions.
     *
     * <p>One session has up to two buckets (operations and presence), so the two numbers are not
     * equal. The name says buckets because buckets are what this is about: it measures the memory
     * this mechanism occupies, not a count of users. The previous name ({@code trackedSessions})
     * returned two for a single session and was simply untrue.
     */
    public int trackedBuckets() {
        return operations.size() + presence.size();
    }
}
