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

@DisplayName("Every scheduled job declares which pool it belongs to")
class SchedulingWiringTest {
    private static final String BASE_PACKAGE = "dev.collabedit.app";

    private static final Set<String> KNOWN_POOLS =
        Set.of(SchedulingConfig.FLOW, SchedulingConfig.MAINTENANCE);

    private static final String HEARTBEAT_POOL = "stompHeartbeatScheduler";

    @Test
    @DisplayName("no scheduled method leaves its pool to bean resolution")
    void everyScheduledMethodNamesItsPool() {
        List<Method> jobs = scheduledMethods();

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
                whichever single TaskScheduler bean it finds - which in this application is the \
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

    @Test
    @DisplayName("the flow pool is single-threaded, because ordering depends on it")
    void flowPoolIsSingleThreaded() {
        var scheduler = (ThreadPoolTaskScheduler) new SchedulingConfig().flowScheduler();
        try {
            assertThat(scheduler.getScheduledThreadPoolExecutor().getCorePoolSize())
                .as("""
                    The flow pool is no longer single-threaded. Broadcast and flush of the same \
                    document may now overlap, and an acknowledgement can overtake the broadcast \
                    of its own operation - see SchedulingConfig.""")
                .isEqualTo(1);
        } finally {
            scheduler.shutdown();
        }
    }

    private static List<Method> scheduledMethods() {
        ClassPathScanningCandidateComponentProvider scanner =
            new ClassPathScanningCandidateComponentProvider(false);
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
