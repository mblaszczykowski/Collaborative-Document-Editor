package dev.collabedit.app.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Which thread each periodic job runs on — and why there is more than one pool.
 *
 * <h2>The accident this replaces</h2>
 * Until this class existed, every {@code @Scheduled} method in the application ran on the
 * <b>STOMP heartbeat scheduler</b>, and nothing said so. The chain was invisible:
 * {@link dev.collabedit.app.ws.WebSocketConfig} declares a {@code TaskScheduler} bean with
 * {@code poolSize(1)} because the in-memory broker needs one; Spring Boot's
 * {@code TaskSchedulingConfigurations$TaskSchedulerConfiguration} is annotated
 * {@code @ConditionalOnMissingBean({TaskScheduler.class, ScheduledExecutorService.class})},
 * so it backed off; and {@code ScheduledAnnotationBeanPostProcessor} then bound the only
 * {@code TaskScheduler} in the context.
 *
 * <p>The result was one thread carrying, strictly in sequence: the 10 ms broadcast loop, the
 * 50 ms flush with its database round trips, the anti-entropy sweep with <i>one query per
 * loaded document</i> (up to {@code max-loaded-documents}, i.e. 200), document snapshots of
 * the entire block structure, and heartbeat emission. Nothing failed — the batching design
 * simply did not have the property its own documentation claims for it.
 *
 * <h2>Two pools, and the boundary between them is a correctness boundary</h2>
 * <b>{@link #flowScheduler()} is single-threaded on purpose, and that is now a stated
 * invariant rather than a side effect of bean resolution.</b> Broadcast and flush must not
 * overlap: {@link dev.collabedit.app.domain.DocumentReplica#drainForFlush} takes both buffers
 * under one lock precisely so that an acknowledgement can never overtake the broadcast of the
 * same operation — the client treats that as a gap and asks for a full resynchronisation.
 * Draining atomically is only half of the guarantee; the other half is that no second thread
 * is sending frames for the same document at the same time. One thread gives that for free.
 *
 * <p>{@link #maintenanceScheduler()} carries everything whose latency nobody sees: anti-entropy,
 * eviction, snapshots. These are the jobs that made the old arrangement expensive, and they are
 * exactly the ones with no ordering relationship to the write path. Two threads, because
 * anti-entropy on one document must not wait for a snapshot of another.
 *
 * <p>The heartbeat scheduler stays where it is, in {@code WebSocketConfig}, and is no longer
 * shared with anything: every {@code @Scheduled} method in this application names its pool
 * explicitly through {@code @Scheduled(scheduler = ...)}.
 *
 * <p><b>A new periodic job that forgets to name a pool does NOT fail to start</b>, and this
 * paragraph used to say it did. Spring resolves the attribute when it is present and otherwise
 * binds whichever {@code TaskScheduler} the context offers — which is exactly the silent
 * resolution that produced the arrangement described above, so claiming it now errors is claiming
 * the original bug cannot recur. What actually enforces the rule is {@code SchedulingWiringTest},
 * at BUILD time: it scans every {@code @Scheduled} method in the application and fails if one
 * names no pool or names an unknown one. {@code SchedulingWiringTest} states this correctly and
 * this file contradicted it.
 *
 * <p>That is the same shape of guarantee {@link dev.collabedit.app.auth.DocumentAccessGuard} gives
 * for authorization, with one difference worth naming: the guard also refuses at RUNTIME, so
 * forgetting is caught twice. Here there is one net, and it is the build.
 *
 * <h2>What is still on the flow thread, and is not accounted for above</h2>
 * This class argues carefully for pool size 1 and for moving snapshots off this pool, and then says
 * nothing about the fact that <b>the flow thread also does blocking I/O</b>. It should, because that
 * is the same shape of invisible chain the class congratulates itself on removing.
 *
 * <p>{@code DocumentService.flush} runs here, and on this one thread it performs
 * {@code SELECT next_server_seq … FOR UPDATE} — a row lock contended <i>across instances</i>, inside
 * a transaction that waits up to {@code spring.datasource.hikari.connection-timeout} for a pool slot
 * — and then a synchronous {@code redis.convertAndSend} bounded by {@code spring.data.redis.timeout}.
 * With {@code fixedDelay}, a pass that takes 500 ms delays the next {@code broadcastAll} by 500 ms
 * for <b>every</b> document on the instance, not only the one that blocked. Redis unreachable is the
 * clearest case: the cluster design calls publishing best-effort, and best-effort on the
 * latency-critical thread is not best-effort.
 *
 * <p><b>Left as it is, deliberately, and the reason is that the fix is not free.</b> The obvious one
 * — hand the write batch to another executor — has to preserve per-document ordering, because the
 * whole guarantee above is that an acknowledgement cannot overtake the broadcast of its own
 * operation. That means a per-document lane rather than a pool, which is a real mechanism to build
 * and to get wrong, against a cost nothing has yet measured: the load harness ran one document
 * (application.yml says so), so the case this describes has never been observed, only reasoned
 * about. Measuring it at {@code max-loaded-documents} is the next step, and the paragraph is here so
 * that the next person finds the question rather than the silence.
 */
@Configuration
public class SchedulingConfig {

    /** Bean name for the write-path pool; referenced from {@code @Scheduled(scheduler = ...)}. */
    public static final String FLOW = "flowScheduler";

    /** Bean name for the background pool. */
    public static final String MAINTENANCE = "maintenanceScheduler";

    /**
     * The write path: broadcast every 10 ms and flush every 50 ms.
     *
     * <p><b>Pool size 1 is required, not a default.</b> See the class javadoc: raising it
     * permits a broadcast and a flush of the same document to run concurrently, and the
     * ordering between a broadcast and its own acknowledgement is what stops clients from
     * requesting a full resynchronisation during entirely healthy operation.
     */
    @Bean(FLOW)
    public TaskScheduler flowScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("collabedit-flow-");
        // The flow pool must never queue work at shutdown behind a long maintenance job.
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(5);
        scheduler.initialize();
        return scheduler;
    }

    /**
     * Background work: anti-entropy, eviction, snapshots.
     *
     * <p>Two threads rather than one so that a snapshot of a large document — which copies the
     * whole block structure under that document's lock — does not delay the anti-entropy sweep
     * of every other document behind it. More than two buys nothing: all three jobs are rare,
     * and each one already iterates every loaded replica by itself.
     */
    @Bean(MAINTENANCE)
    public TaskScheduler maintenanceScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("collabedit-maint-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(10);
        scheduler.initialize();
        return scheduler;
    }
}
