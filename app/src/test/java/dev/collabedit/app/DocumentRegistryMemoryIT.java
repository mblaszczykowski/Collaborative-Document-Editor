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

/**
 * The upper bound on how many replicas are held in memory (CP11).
 *
 * <table>
 *   <caption>Test -> failure mode</caption>
 *   <tr><th>Test</th><th>What it catches</th></tr>
 *   <tr><td>{@code limitIsEnforcedOnLoad}</td>
 *       <td>No CAPACITY bound. An idle threshold alone bounds nothing when documents are opened
 *           faster than they expire — memory grows to OutOfMemory, and every replica taken
 *           individually looks necessary.</td></tr>
 *   <tr><td>{@code replicaWithUnwrittenOperationsIsNeverEvicted}</td>
 *       <td><b>Data loss.</b> Releasing a replica with a non-empty buffer discards operations
 *           accepted from a client and not yet written. The client has already had its merge
 *           acknowledged, so it does not retransmit — the change disappears without a trace.</td></tr>
 *   <tr><td>{@code leastRecentlyUsedGoesFirst}</td>
 *       <td>Eviction in arbitrary order. Correctness is intact, but the document being edited
 *           right now can be thrown out ahead of one nobody has touched — so the cost of reloading
 *           is paid exactly where it hurts most.</td></tr>
 * </table>
 */
@SpringBootTest
class DocumentRegistryMemoryIT {

    private static final int LIMIT = 3;

    /**
     * Its own property set rather than inheriting from {@code IntegrationTestBase}.
     *
     * <p>The reason is concrete and cost one false alarm: {@code @DynamicPropertySource} has
     * <b>higher precedence</b> than {@code @TestPropertySource}, so overriding the batching window
     * in a subclass was silently ignored. The unwritten-operation buffer drained every 20 ms, and
     * the test "a replica with work in flight must not be evicted" measured a race with the
     * scheduler instead of the rule it checks — it failed even though the rule works.
     *
     * <p>The containers are shared with the base class (they are static), so a separate context
     * costs seconds rather than another Postgres.
     */
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
        // The batching window is effectively disabled — see the comment above.
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
            .as("the registry grows without bound — an idle threshold is not a memory bound")
            .hasSizeLessThanOrEqualTo(LIMIT);
    }

    @Test
    @DisplayName("a replica with unwritten operations is never evicted, even over the limit")
    void replicaWithUnwrittenOperationsIsNeverEvicted() {
        UUID busy = newDocument("busy");
        var replica = registry.get(busy);

        // An operation accepted but not yet written — exactly the state in which evicting the
        // replica means silently losing the user's work.
        RgaDocument browser = new RgaDocument(4242);
        replica.merge(browser.insert(0, "work in flight"), "tester", null, System.currentTimeMillis());
        assertThat(replica.unwrittenCount()).isPositive();

        for (int i = 0; i < LIMIT * 4; i++) {
            registry.get(newDocument("pressure-" + i));
        }

        assertThat(registry.isLoaded(busy))
            .as("a replica with unwritten operations was evicted — that is data loss")
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
            // A gap so the activity timestamps are distinguishable — without it, ordering by
            // lastActivityMillis tests the clock's resolution rather than the selection logic.
            Thread.sleep(5);
        }

        // Touching the OLDEST refreshes its activity, so the second one should be the victim.
        UUID refreshed = documents.getFirst();
        registry.get(refreshed).merge(
            new RgaDocument(777).insert(0, "x"), "tester", null, System.currentTimeMillis());
        registry.get(refreshed).drainUnwritten(100);
        Thread.sleep(5);

        registry.get(newDocument("new"));

        assertThat(registry.isLoaded(refreshed))
            .as("the most recently used document was evicted — the LRU ordering does not work")
            .isTrue();

        // And the one that SHOULD have gone, did. Without this the test asserts only that a
        // particular document survived, which an arbitrary eviction policy satisfies two times out
        // of three at this limit — so it would go on passing if the ordering were dropped entirely.
        assertThat(registry.isLoaded(documents.get(1)))
            .as("the victim was not the least recently used document: every other document here "
                + "has been touched more recently, so a policy that evicts by activity has exactly "
                + "one candidate, and any other choice means it is not choosing by activity")
            .isFalse();
    }
}
