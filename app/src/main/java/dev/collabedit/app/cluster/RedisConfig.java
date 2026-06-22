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

/**
 * Listening for operations from the other instances.
 *
 * <p>The subscription is a pattern subscription (one pattern for all documents) rather than one
 * channel per document. The reason: subscribing and unsubscribing on every document open and close
 * would generate control traffic proportional to user activity, whereas filtering out messages
 * about documents this instance does not hold in memory is cheap.
 *
 * <p><b>But only because the filtering happens on the channel name.</b> Up to CP11 the
 * justification read "the filtering is trivial", and it was true only by half: the check itself
 * was, but it was preceded by parsing the ENTIRE JSON payload, because the document identifier was
 * read from the message body. With one document that did not matter. With many it would mean every
 * instance parsing the whole cluster's traffic in order to discard the result most of the time.
 *
 * <p>The document identifier is in the channel name ({@code collabedit:doc:{uuid}:ops}), so the
 * filtering can happen before a single byte of the body is read — and that is what
 * {@link ClusterBus} does. Only now is that justification true in full.
 */
@Configuration
public class RedisConfig {

    private static final Logger log = LoggerFactory.getLogger(RedisConfig.class);

    /**
     * At most one line per this many milliseconds, however many messages are dropped.
     *
     * <p>The rejection handler runs on the CALLER — which here is the thread that received the
     * message from the driver, the very thread this whole arrangement exists to keep moving. One
     * synchronous appender write per dropped message, at the drop rate (which by construction IS
     * the saturation rate), would reintroduce the coupling in smaller form. The counter carries
     * the exact number; the log only has to say it is happening, and {@code deny} in
     * {@code DocumentAccessInterceptor} learned the same lesson from 1614 WARN lines in one run.
     */
    private static final long DROP_LOG_INTERVAL_MILLIS = 10_000;

    private final DocumentMetrics metrics;

    /** Kept so it can be shut down — see {@link #shutdownListenerExecutor}. */
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
        // TWO executors, and the split matters. `subscriptionExecutor` runs the BLOCKING
        // subscription loop — one long-lived task that never returns while the container is up —
        // while `taskExecutor` runs the listener invocations. They default to the same executor,
        // so bounding one would bound the other: the subscription would permanently occupy a
        // thread of the dispatch pool, leaving the bound smaller than it reads, and a pool sized
        // for dispatch work would be sized wrong for a task that never finishes.
        container.setSubscriptionExecutor(new SimpleAsyncTaskExecutor("collabedit-cluster-sub-"));
        container.setTaskExecutor(clusterListenerExecutor());
        container.addMessageListener(bus, new PatternTopic(ClusterBus.CHANNEL_PATTERN));

        // A plain channel, not a pattern: there is one of these for the whole cluster, and unlike
        // operations it must NOT be filtered by which documents this instance holds in memory —
        // an instance can hold a cached role for a document it has no replica of. See
        // PermissionInvalidationBus for why this is a separate channel rather than a message type
        // on the existing one.
        container.addMessageListener(permissions, new ChannelTopic(PermissionInvalidationBus.CHANNEL));
        // Presence (CP26) — soft state with an expiry, on its own channel. See PresenceBus for
        // why it is not folded into either of the two above.
        container.addMessageListener(presence, new ChannelTopic(PresenceBus.CHANNEL));
        return container;
    }

    /**
     * The pool that runs cluster listener INVOCATIONS — bounded, and that is the whole point.
     *
     * <h2>What the default does</h2>
     * Without this, {@code RedisMessageListenerContainer.afterPropertiesSet} builds a
     * {@code SimpleAsyncTaskExecutor}, which starts <b>a new platform thread per submitted task</b>
     * with no upper limit — and it submits one per listener per message.
     *
     * <p>That is not a theoretical load here. {@code PresenceService} announces once per document
     * per second per instance, and the presence channel carries no interest filter, so each
     * instance receives {@code documents × instances} announcements a second. At the configured
     * {@code max-loaded-documents: 200} and three instances that is around 600 new threads a
     * second on the presence channel alone. Worse, the work those threads do is not cheap:
     * {@code DocumentService.onClusterMessage} can reach {@code repairFrom}, which is a blocking
     * Postgres query against a pool of ten connections.
     *
     * <h2>The sizes, and why they are these sizes</h2>
     * Four core threads and eight at most, matching the shape of the work rather than the machine:
     * a cluster message is a JSON parse plus a merge, occasionally plus one query. More threads
     * than the database pool can serve only queue up inside Hikari instead of here, where the
     * queue is visible and bounded.
     *
     * <p>The queue is bounded too, at 1000. On overflow the task is <b>discarded</b>, not run on
     * the caller — and the difference matters more than it looks.
     * {@code RedisMessageListenerContainer.dispatchMessage} submits from the thread that received
     * the message from the driver, so {@code CallerRunsPolicy} would block delivery of EVERY
     * channel on that connection while the rejected task ran: operations, presence, and
     * {@code collabedit:permissions:invalidate} — the one channel {@code PermissionService} singles
     * out as a place where lateness is a correctness problem rather than a convergence one.
     * Bounding the pool and then blocking its feeder would have reintroduced the coupling this
     * whole method exists to remove.
     *
     * <p>Discarding is safe by design rather than by luck, and it is worth stating per channel
     * rather than in general:
     *
     * <ul>
     * <li><b>operations</b> — the cluster design makes this bus explicitly best-effort, with the operation
     * log and the anti-entropy sweep as the backstop;</li>
     * <li><b>presence</b> — soft state, and announcements are full lists rather than deltas, so
     * the next one a second later restates the whole truth;</li>
     * <li><b>permission invalidation</b> — degrades to exactly the behaviour every other
     * instance already has, bounded by {@code PermissionService.CACHE_TTL} (five seconds).
     * That is a real cost, and it is still the cheaper one: {@code CallerRunsPolicy} would
     * have delayed this channel too, along with every other, for however long the rejected
     * task took — and cluster listener work can reach a blocking Postgres query.</li>
     * </ul>
     *
     * <p>What discarding must not be is invisible, so it is counted — see the handler below.
     */
    // Package-private rather than private: RedisConfigTest builds the pool directly, because the
    // thing worth checking is that it STOPS, and that is a property of this method plus the
    // @PreDestroy below rather than of anything the container exposes.
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

    /** Counts a dropped cluster message, and says so in the log at most every ten seconds. */
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

    /**
     * Stops the listener pool when the context does.
     *
     * <p>Needed because the pool is NOT a bean: {@code RedisMessageListenerContainer.destroy}
     * shuts its task executor down only when it created that executor itself
     * ({@code manageExecutor}), and {@code ThreadPoolTaskExecutor} makes non-daemon threads with
     * no core-thread timeout. Without this, four {@code collabedit-cluster-*} threads outlive
     * every closed context — one leaked pool per Spring test context, and a programmatic
     * {@code SpringApplication.exit} that never returns. Production shutdown happens to be safe
     * only because the JVM's own hook halts it.
     *
     * <p>A {@code @Bean} would have got the same callback for free and is the usual answer. Not
     * here: Spring Boot's {@code applicationTaskExecutor} is conditional on there being no other
     * {@code Executor} bean, so publishing this one would silently take away the executor MVC's
     * async support and {@code @Async} use — a large, invisible side effect for a lifecycle hook.
     */
    @PreDestroy
    void shutdownListenerExecutor() {
        if (listenerExecutor != null) {
            listenerExecutor.shutdown();
        }
    }
}
