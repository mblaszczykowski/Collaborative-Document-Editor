package dev.collabedit.app;

import static org.assertj.core.api.Assertions.assertThat;

import dev.collabedit.app.domain.DocumentRegistry;
import dev.collabedit.crdt.RgaDocument;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
class DocumentRegistryMemoryIT {
    private static final int LIMIT = 3;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", IntegrationTestBase.POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", IntegrationTestBase.POSTGRES::getUsername);
        registry.add("spring.datasource.password", IntegrationTestBase.POSTGRES::getPassword);
        registry.add("spring.data.redis.host", IntegrationTestBase.REDIS::getHost);
        registry.add("spring.data.redis.port", () -> IntegrationTestBase.REDIS.getMappedPort(6379));
        registry.add("otel.sdk.disabled", () -> "true");
        registry.add("collabedit.auth.secret", () -> IntegrationTestBase.TEST_SECRET);

        registry.add("collabedit.cluster.max-loaded-documents", () -> LIMIT);
        registry.add("collabedit.persistence.op-batch-window-ms", () -> 600_000);
    }

    @Autowired
    DocumentRegistry registry;
    @Autowired
    JdbcTemplate jdbc;

    private UUID newDocument(String title) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO documents (id, title, visibility) VALUES (?, ?, 'link')", id, title);
        return id;
    }

    @Test
    @DisplayName("the number of in-memory replicas does not exceed the limit")
    void limitIsEnforcedOnLoad() {
        for (int i = 0; i < LIMIT * 4; i++) {
            registry.get(newDocument("memory-" + i));
        }

        assertThat(registry.loaded())
            .as("the registry grows without bound - an idle threshold is not a memory bound")
            .hasSizeLessThanOrEqualTo(LIMIT);
    }

    @Test
    @DisplayName("a replica with unwritten operations is never evicted, even over the limit")
    void replicaWithUnwrittenOperationsIsNeverEvicted() {
        UUID busy = newDocument("busy");
        var replica = registry.get(busy);

        RgaDocument browser = new RgaDocument(4242);
        replica.merge(browser.insert(0, "work in flight"), "tester", null, System.currentTimeMillis());
        assertThat(replica.unwrittenCount()).isPositive();

        for (int i = 0; i < LIMIT * 4; i++) {
            registry.get(newDocument("pressure-" + i));
        }

        assertThat(registry.isLoaded(busy))
            .as("a replica with unwritten operations was evicted - that is data loss")
            .isTrue();
    }

    @Test
    @DisplayName("the least recently used are evicted, not arbitrary ones")
    void leastRecentlyUsedGoesFirst() throws Exception {
        List<UUID> documents = new ArrayList<>();
        for (int i = 0; i < LIMIT; i++) {
            UUID id = newDocument("order-" + i);
            documents.add(id);
            registry.get(id);
            Thread.sleep(5);
        }

        UUID refreshed = documents.getFirst();
        registry.get(refreshed).merge(
            new RgaDocument(777).insert(0, "x"), "tester", null, System.currentTimeMillis());
        registry.get(refreshed).drainUnwritten(100);
        Thread.sleep(5);

        registry.get(newDocument("new"));

        assertThat(registry.isLoaded(refreshed))
            .as("the most recently used document was evicted - the LRU ordering does not work")
            .isTrue();

        assertThat(registry.isLoaded(documents.get(1)))
            .as("the victim was not the least recently used document: every other document here "
                + "has been touched more recently, so a policy that evicts by activity has exactly "
                + "one candidate, and any other choice means it is not choosing by activity")
            .isFalse();
    }
}
