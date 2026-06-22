package dev.collabedit.app.cluster;

import static org.assertj.core.api.Assertions.assertThat;

import dev.collabedit.app.observability.DocumentMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * The cluster listener pool is bounded, and everything that follows from that.
 *
 * <p>Two properties, both of which were wrong at some point and neither of which any other test
 * touches: the pool has to STOP when the context does, and a message it has no room for has to be
 * counted rather than silently lost.
 */
class RedisConfigTest {

    private RedisConfig config(DocumentMetrics metrics) {
        return new RedisConfig(metrics);
    }

    @Test
    @DisplayName("the listener pool stops when the context does")
    void theListenerPoolIsShutDown() throws InterruptedException {
        RedisConfig config = config(new DocumentMetrics(new SimpleMeterRegistry(), null));
        var executor = (ThreadPoolTaskExecutor) config.clusterListenerExecutor();

        CountDownLatch ran = new CountDownLatch(1);
        executor.execute(ran::countDown);
        assertThat(ran.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(executor.getThreadPoolExecutor().getPoolSize())
            .as("the pool should have started a thread to run that task")
            .isPositive();

        // The pool is not a bean, so nothing calls destroy for it: RedisMessageListenerContainer
        // shuts its task executor down only when it created that executor itself, and
        // ThreadPoolTaskExecutor makes NON-DAEMON threads with no core-thread timeout. Without the
        // @PreDestroy this asserts, four of them outlived every closed context — one leaked pool
        // per Spring test context, and a programmatic SpringApplication.exit that never returns.
        config.shutdownListenerExecutor();

        // Asserted on THIS pool rather than by scanning the JVM's threads for the name prefix:
        // Spring caches test contexts and never closes them, so the integration tests' own
        // listener pools are legitimately alive alongside this one for the whole run. A global
        // scan would have measured them instead.
        assertThat(executor.getThreadPoolExecutor().isShutdown())
            .as("the listener pool outlived the shutdown")
            .isTrue();
        assertThat(executor.getThreadPoolExecutor().awaitTermination(5, TimeUnit.SECONDS))
            .as("the listener pool's threads outlived the shutdown")
            .isTrue();
    }

    @Test
    @DisplayName("a dropped cluster message is counted, however many are dropped in a row")
    void everyDropIsCounted() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RedisConfig config = config(new DocumentMetrics(registry, null));
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
            1, 1, 0, TimeUnit.SECONDS, new java.util.concurrent.LinkedBlockingQueue<>());

        // The LOG line is throttled — the rejection handler runs on the thread that received the
        // message from the driver, so one synchronous appender write per drop would reintroduce
        // the coupling the bounded pool exists to remove. The COUNTER must not be: it is the only
        // thing that says how much was lost, and the cluster design's best-effort licence depends on the
        // loss being visible.
        for (int i = 0; i < 5; i++) {
            config.recordDrop(() -> {}, pool);
        }
        pool.shutdown();

        assertThat(registry.get("cluster.messages.dropped.total").counter().count()).isEqualTo(5);
    }
}
