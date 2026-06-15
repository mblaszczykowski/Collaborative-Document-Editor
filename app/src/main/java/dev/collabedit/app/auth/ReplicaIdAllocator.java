package dev.collabedit.app.auth;

import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Allocating globally unique replica identifiers.
 *
 * <p>Uniqueness is a correctness requirement, not a convenience: two replicas with the same number
 * would generate colliding character identifiers, and the algorithm from ADR-0001 would treat them
 * as the same character. The effect would be silently losing one author's text — a bug undetectable
 * after the fact, because it leaves no trace in the structure.
 *
 * <p>Hence one source of numbers for the whole cluster: a counter in Redis. This is the only place
 * where Redis plays an <b>authoritative</b> role rather than being a best-effort path (ADR-0004) —
 * and that is why its unavailability is treated differently here than when publishing operations.
 */
@Service
public class ReplicaIdAllocator {

    private static final Logger log = LoggerFactory.getLogger(ReplicaIdAllocator.class);
    private static final String KEY = "collabedit:replica-id-seq";

    private final StringRedisTemplate redis;

    /**
     * A fallback for when Redis is unavailable.
     *
     * <p>The starting point is random from a very large range, so a collision with numbers from
     * Redis or from another instance is extremely unlikely — but <b>not impossible</b>. This is a
     * deliberately weaker guarantee, accepted so that a Redis outage means degradation rather than
     * refusal of service. Written down explicitly, because the difference between "unique" and
     * "almost certainly unique" matters here.
     */
    private final AtomicInteger fallback =
        new AtomicInteger(new java.security.SecureRandom().nextInt(1, Integer.MAX_VALUE / 2));

    public ReplicaIdAllocator(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public int next() {
        try {
            Long value = redis.opsForValue().increment(KEY);
            if (value != null) {
                // Replica identifiers are positive; backend instances use negative ones
                // (DocumentRegistry), so the two spaces cannot intersect.
                return (int) (value % (Integer.MAX_VALUE / 2)) + 1;
            }
        } catch (RuntimeException e) {
            log.warn("Redis unavailable while allocating a replica identifier — using the fallback: {}",
                e.toString());
        }
        return fallback.incrementAndGet();
    }
}
