package dev.collabedit.app.auth;

import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class ReplicaIdAllocator {
    private static final Logger log = LoggerFactory.getLogger(ReplicaIdAllocator.class);
    private static final String KEY = "collabedit:replica-id-seq";

    private final StringRedisTemplate redis;

    private final AtomicInteger fallback =
        new AtomicInteger(new java.security.SecureRandom().nextInt(1, Integer.MAX_VALUE / 2));

    public ReplicaIdAllocator(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public int next() {
        try {
            Long value = redis.opsForValue().increment(KEY);
            if (value != null) {
                return (int) (value % (Integer.MAX_VALUE / 2)) + 1;
            }
        } catch (RuntimeException e) {
            log.warn("Redis unavailable while allocating a replica identifier - using the fallback: {}",
                e.toString());
        }
        return fallback.incrementAndGet();
    }
}
