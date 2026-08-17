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

        config.shutdownListenerExecutor();

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

        for (int i = 0; i < 5; i++) {
            config.recordDrop(() -> {}, pool);
        }
        pool.shutdown();

        assertThat(registry.get("cluster.messages.dropped.total").counter().count()).isEqualTo(5);
    }
}
