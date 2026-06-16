package dev.collabedit.app;

import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The base for integration tests — <b>a real Postgres and a real Redis</b>, not stubs
 * (CLAUDE.md §3.2).
 *
 * <p>The reason is concrete, not ideological. The most important behaviours of this project's
 * persistence layer are <b>properties of the database</b> that no stub reproduces:
 * <ul>
 *   <li>write idempotence follows from the {@code (doc_id, replica_id, lamport)} primary key — a
 *       mock simply will not reject a duplicate,</li>
 *   <li>the continuity of {@code server_seq} depends on the {@code FOR UPDATE} row lock and on
 *       transaction behaviour,</li>
 *   <li>{@code JSONB}, {@code BYTEA} and ordering by {@code created_at} are behaviours of the
 *       driver and the engine, not of an interface.</li>
 * </ul>
 * Testing those things against a stub would check whether the stub agrees with itself.
 *
 * <p>The containers are <b>static</b>, so they come up once per class rather than once per test.
 * Starting Postgres takes a few seconds; per-method containers would grow the test time linearly,
 * and the only gain would be isolation that a separate document per test already provides.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Tag("integration")
public abstract class IntegrationTestBase {

    /**
     * Signing secret for every test context in this module.
     *
     * <p>{@code TokenService} has had no default since CP16 and refuses to start without one, so
     * each Spring context must supply it exactly as a real deployment does. A constant rather
     * than a random value: a token minted in one test and verified in another has to survive.
     *
     * <p>It lives here, shared, because the first version of that change left it out of the two
     * contexts built outside this class — {@code DocumentRegistryMemoryIT}, which declares its
     * own properties, and the second instance {@code ClusterChaosIT} starts by hand — and both
     * failed with "Unable to start web server", which names the symptom and not the cause. One
     * constant means there is no fourth place to forget.
     */
    static final String TEST_SECRET = "integration-test-secret-long-enough-ok";

    static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"))
            .withDatabaseName("collabedit")
            .withUsername("collabedit")
            .withPassword("collabedit");

    static final GenericContainer<?> REDIS =
        new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    static {
        POSTGRES.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));

        // A shorter batching window than the production 50 ms: the tests wait for a write
        // acknowledgement, and every 50 ms multiplied by the number of assertions is seconds in a
        // CI run.
        registry.add("collabedit.persistence.op-batch-window-ms", () -> 20);
        // A snapshot every 20 operations rather than every 500 — we want the tests to EXERCISE
        // the snapshot and GC path, not skip it because they do not generate enough operations.
        registry.add("collabedit.persistence.snapshot-every-n-ops", () -> 20);
        // Idleness recognised after 500 ms rather than 30 s. The production value would mean a
        // test spending half a minute purely waiting; shortening it does not change the behaviour
        // under test, because the threshold is a comparison rather than a timing dependency.
        registry.add("collabedit.persistence.snapshot-after-idle-ms", () -> 500);

        // Without this, every test tries to send traces to a non-existent collector and litters
        // the output with export failure warnings. Observability is not what these tests are
        // about, and its noise makes the ones that are harder to read.
        registry.add("otel.sdk.disabled", () -> "true");

        registry.add("collabedit.auth.secret", () -> TEST_SECRET);
    }
}
