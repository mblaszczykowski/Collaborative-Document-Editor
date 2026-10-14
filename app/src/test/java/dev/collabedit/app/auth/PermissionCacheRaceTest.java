package dev.collabedit.app.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import dev.collabedit.app.cluster.PermissionInvalidationBus;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class PermissionCacheRaceTest {
    @Test
    void invalidationCannotBeUndoneByAnEarlierDatabaseRead() throws Exception {
        CountDownLatch readStarted = new CountDownLatch(1);
        CountDownLatch releaseRead = new CountDownLatch(1);
        AtomicInteger reads = new AtomicInteger();
        JdbcTemplate jdbc = new JdbcTemplate() {
            @Override public List<Map<String, Object>> queryForList(String sql, Object... args) {
                if (reads.incrementAndGet() == 1) {
                    readStarted.countDown();
                    try {
                        if (!releaseRead.await(5, TimeUnit.SECONDS)) {
                            throw new AssertionError("Timed out waiting for invalidation");
                        }
                    } catch (InterruptedException e) {
                        throw new AssertionError(e);
                    }
                    return List.of(Map.of("document_role", "editor"));
                }
                return List.of(Map.of("visibility", "private"));
            }
        };
        PermissionService permissions = new PermissionService(jdbc, mock(PermissionInvalidationBus.class));
        UUID document = UUID.randomUUID();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var inFlight = executor.submit(() -> permissions.roleOf("user", document));
            try {
                assertThat(readStarted.await(5, TimeUnit.SECONDS)).isTrue();
                permissions.invalidateLocally(document);
            } finally {
                releaseRead.countDown();
            }
            assertThat(inFlight.get(5, TimeUnit.SECONDS)).isEmpty();
            assertThat(permissions.roleOf("user", document)).isEmpty();
            assertThat(reads.get()).isEqualTo(2);
        }
    }
}
