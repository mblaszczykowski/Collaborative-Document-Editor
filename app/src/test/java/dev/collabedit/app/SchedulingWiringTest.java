package dev.collabedit.app;

import static org.assertj.core.api.Assertions.assertThat;

import dev.collabedit.app.config.SchedulingConfig;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

/**
 * Every periodic job names the pool it runs on.
 *
 * <h2>The bug this pins</h2>
 * All four {@code @Scheduled} methods in this application used to run on the <b>STOMP heartbeat
 * scheduler</b>, which has a pool of one thread. Nobody chose that and nothing recorded it; it
 * fell out of three separate decisions that are individually reasonable:
 *
 * <ol>
 *   <li>{@code WebSocketConfig} declares a {@code TaskScheduler} bean, because the in-memory
 *       broker silently stops sending heartbeats without one;</li>
 *   <li>Spring Boot's {@code TaskSchedulingConfigurations$TaskSchedulerConfiguration} carries
 *       {@code @ConditionalOnMissingBean({TaskScheduler.class, ScheduledExecutorService.class})},
 *       so declaring that bean makes the auto-configured pool disappear;</li>
 *   <li>{@code ScheduledAnnotationBeanPostProcessor} then resolves the unique
 *       {@code TaskScheduler} in the context — the heartbeat one.</li>
 * </ol>
 *
 * <p>The consequence was not a failure but a missing property: the 10 ms broadcast, the 50 ms
 * flush with its database round trips, document snapshots and an anti-entropy sweep issuing one
 * query per loaded document all ran strictly in sequence, on the same thread as heartbeat
 * emission. {@code DocumentReplica} documents the two buffers as having "different rhythms".
 * On one thread they cannot.
 *
 * <h2>What is asserted, and what deliberately is not</h2>
 * This checks the <b>declaration</b>, not the runtime thread. A test that asserted "broadcast
 * ran on a thread named collabedit-flow-" would need a full application context and would still
 * only prove it for the moment it looked. The declaration is what regresses: someone adds a
 * fifth {@code @Scheduled} method, omits the attribute, and it silently lands on whichever pool
 * bean resolution happens to pick. That is exactly how this started.
 *
 * <p>Verified by mutation, per house convention: removing {@code scheduler = ...} from any one
 * of the four methods in {@code DocumentService} turns
 * {@link #everyScheduledMethodNamesItsPool()} red.
 */
@DisplayName("Every scheduled job declares which pool it belongs to")
class SchedulingWiringTest {

    private static final String BASE_PACKAGE = "dev.collabedit.app";

    /**
     * The pools a periodic job may name.
     *
     * <p>Pinned rather than "any non-empty string" for the same reason
     * {@code EndpointAuthorizationArchitectureTest} pins its public endpoints: adding a third
     * pool should read as a decision in the diff, not slip in as a typo that happens to resolve
     * to a bean nobody meant.
     */
    private static final Set<String> KNOWN_POOLS =
        Set.of(SchedulingConfig.FLOW, SchedulingConfig.MAINTENANCE);

    /**
     * The heartbeat scheduler's bean name.
     *
     * <p>Named here as a value rather than imported, because the point of the assertion is that
     * no periodic job refers to it — importing it would be the only reference in this file and
     * would read as if one were expected.
     */
    private static final String HEARTBEAT_POOL = "stompHeartbeatScheduler";

    @Test
    @DisplayName("no scheduled method leaves its pool to bean resolution")
    void everyScheduledMethodNamesItsPool() {
        List<Method> jobs = scheduledMethods();

        // Guard against the test passing because the scan found nothing — the same failure mode
        // as the *Measurement class that was green by never running at all.
        assertThat(jobs)
            .as("no @Scheduled methods found; the scan is broken, not the code")
            .isNotEmpty();

        List<String> unnamed = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        for (Method job : jobs) {
            for (Scheduled scheduled : job.getAnnotationsByType(Scheduled.class)) {
                String pool = scheduled.scheduler();
                if (pool.isBlank()) {
                    unnamed.add(describe(job));
                } else if (!KNOWN_POOLS.contains(pool)) {
                    unknown.add(describe(job) + " -> " + pool);
                }
            }
        }

        assertThat(unnamed)
            .as("""
                Scheduled methods with no scheduler named. Without it, Spring binds the job to \
                whichever single TaskScheduler bean it finds — which in this application is the \
                one-thread STOMP heartbeat pool. Name a pool from SchedulingConfig.""")
            .isEmpty();

        assertThat(unknown)
            .as("Scheduled methods naming a pool that is not declared in SchedulingConfig")
            .isEmpty();
    }

    @Test
    @DisplayName("no scheduled job shares the heartbeat pool")
    void heartbeatPoolCarriesHeartbeatsOnly() {
        List<String> onHeartbeatPool = scheduledMethods().stream()
            .flatMap(job -> List.of(job.getAnnotationsByType(Scheduled.class)).stream()
                .filter(scheduled -> HEARTBEAT_POOL.equals(scheduled.scheduler()))
                .map(scheduled -> describe(job)))
            .toList();

        assertThat(onHeartbeatPool)
            .as("""
                A periodic job was put back on the STOMP heartbeat pool. That pool has one \
                thread and exists so the broker can emit heartbeats on time; anything sharing it \
                delays them, and a delayed heartbeat is a dropped session followed by a full \
                resynchronisation.""")
            .isEmpty();
    }

    /**
     * The flow pool must stay single-threaded.
     *
     * <p>This is the one place where a pool size is a correctness property rather than a tuning
     * choice. Broadcast and flush both drain the same per-document buffers; draining them under
     * one lock stops an acknowledgement from being <i>built</i> before its broadcast, but only
     * running them on one thread stops the two frames from being <i>sent</i> out of order. A
     * client that sees an ack for an operation it does not have treats it as a gap and asks for
     * a full resynchronisation — so raising this number turns a recovery mechanism into
     * routine background traffic.
     */
    @Test
    @DisplayName("the flow pool is single-threaded, because ordering depends on it")
    void flowPoolIsSingleThreaded() {
        var scheduler = (ThreadPoolTaskScheduler) new SchedulingConfig().flowScheduler();
        try {
            assertThat(scheduler.getScheduledThreadPoolExecutor().getCorePoolSize())
                .as("""
                    The flow pool is no longer single-threaded. Broadcast and flush of the same \
                    document may now overlap, and an acknowledgement can overtake the broadcast \
                    of its own operation — see SchedulingConfig.""")
                .isEqualTo(1);
        } finally {
            scheduler.shutdown();
        }
    }

    // ------------------------------------------------------------------

    private static List<Method> scheduledMethods() {
        ClassPathScanningCandidateComponentProvider scanner =
            new ClassPathScanningCandidateComponentProvider(false);
        // @Component finds @Service and @Repository too, since both are meta-annotated with it.
        scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class));

        List<Method> found = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents(BASE_PACKAGE)) {
            Class<?> type;
            try {
                type = Class.forName(definition.getBeanClassName());
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("scanned class is not loadable: " + definition, e);
            }
            for (Method method : type.getDeclaredMethods()) {
                if (!method.isSynthetic() && method.getAnnotationsByType(Scheduled.class).length > 0) {
                    found.add(method);
                }
            }
        }
        return found;
    }

    private static String describe(Method method) {
        return method.getDeclaringClass().getSimpleName() + "#" + method.getName();
    }
}
