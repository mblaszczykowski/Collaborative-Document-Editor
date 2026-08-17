package dev.collabedit.app.cluster;

import dev.collabedit.app.observability.DocumentMetrics;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Configuration
public class RedisConfig {
    private static final Logger log = LoggerFactory.getLogger(RedisConfig.class);

    private static final long DROP_LOG_INTERVAL_MILLIS = 10_000;

    private final DocumentMetrics metrics;

    private ThreadPoolTaskExecutor listenerExecutor;

    private final AtomicLong lastDropLogMillis = new AtomicLong();

    public RedisConfig(DocumentMetrics metrics) {
        this.metrics = metrics;
    }

    @Bean
    public RedisMessageListenerContainer clusterListenerContainer(
        RedisConnectionFactory connectionFactory, ClusterBus bus,
        PermissionInvalidationBus permissions, PresenceBus presence) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.setSubscriptionExecutor(new SimpleAsyncTaskExecutor("collabedit-cluster-sub-"));
        container.setTaskExecutor(clusterListenerExecutor());
        container.addMessageListener(bus, new PatternTopic(ClusterBus.CHANNEL_PATTERN));

        container.addMessageListener(permissions, new ChannelTopic(PermissionInvalidationBus.CHANNEL));
        container.addMessageListener(presence, new ChannelTopic(PresenceBus.CHANNEL));
        return container;
    }

    TaskExecutor clusterListenerExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(1000);
        executor.setThreadNamePrefix("collabedit-cluster-");
        executor.setRejectedExecutionHandler(this::recordDrop);
        executor.initialize();
        listenerExecutor = executor;
        return executor;
    }

    void recordDrop(Runnable task, ThreadPoolExecutor pool) {
        metrics.recordClusterMessageDropped();
        long now = System.currentTimeMillis();
        long last = lastDropLogMillis.get();
        if (now - last >= DROP_LOG_INTERVAL_MILLIS && lastDropLogMillis.compareAndSet(last, now)) {
            log.warn("Cluster listener pool saturated ({} queued); dropping messages. "
                + "Anti-entropy will repair operations; presence restates itself. "
                + "The exact count is cluster.messages.dropped.total.", pool.getQueue().size());
        }
    }

    @PreDestroy
    void shutdownListenerExecutor() {
        if (listenerExecutor != null) {
            listenerExecutor.shutdown();
        }
    }
}
