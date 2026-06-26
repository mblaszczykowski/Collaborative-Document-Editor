package dev.collabedit.app;

import static org.assertj.core.api.Assertions.assertThat;

import dev.collabedit.app.auth.TokenService;
import dev.collabedit.app.domain.DocumentService;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The cluster chaos test (CP8) — verification of the strongest claim in the cluster design.
 *
 * <p>The claim is: <b>a lost Redis message is not a correctness problem, only a repair-latency
 * one.</b> That is a nice sentence, and until it is checked it remains a nice sentence.
 *
 * <p>The test starts <b>two real backend instances</b> in one process, sharing the same Postgres
 * and the same Redis. The clients are connected to different instances, so the only route of
 * propagation between them is the cluster path. Then Redis is <b>stopped mid-edit</b>.
 *
 * <p>Two instances in one process rather than two containers: containers would require building an
 * image on every code change, turning a test of a dozen or so seconds into one of minutes. Sharing
 * a JVM does not change what is being checked, because the instances communicate only through Redis
 * and Postgres anyway.
 */
class ClusterChaosIT extends IntegrationTestBase {

    private static final Duration PATIENCE = Duration.ofSeconds(30);

    @LocalServerPort
    int firstInstancePort;

    @Autowired
    TokenService tokens;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    DocumentService documents;

    private static ConfigurableApplicationContext secondInstance;
    private static int secondInstancePort;

    private static synchronized void startSecondInstance() {
        if (secondInstance != null) {
            return;
        }
        // Startup ARGUMENTS, not `.properties()`. That is not cosmetic: `.properties()` lands in
        // a source with lower precedence than `application.yml`, so `server.port=0` would be
        // silently overridden by `server.port` from application.yml from the configuration — and the second
        // instance would try to come up on a port already in use.
        secondInstance = new SpringApplicationBuilder(CollabEditApplication.class)
            .run(
                "--server.port=0",
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--spring.data.redis.host=" + REDIS.getHost(),
                "--spring.data.redis.port=" + REDIS.getMappedPort(6379),
                // The first instance already ran the migrations. The second only checks the
                // schema matches — exactly how a second instance behaves in production.
                "--spring.flyway.enabled=true",
                "--collabedit.persistence.op-batch-window-ms=20",
                // The anti-entropy sweep every second instead of every 30 s. This is the only
                // thing sped up for the test: the mechanism is the same, we just do not wait half
                // a minute for it to fire.
                "--collabedit.cluster.anti-entropy-interval-ms=1000",
                "--otel.sdk.disabled=true",
                // Not optional since CP16: TokenService has no default secret and a context
                // that cannot build it fails as "Unable to start web server", which points
                // nowhere near the cause.
                "--collabedit.auth.secret=" + TEST_SECRET);
        secondInstancePort = secondInstance.getEnvironment()
            .getProperty("local.server.port", Integer.class, 0);
    }

    @AfterAll
    static void stopSecondInstance() {
        if (secondInstance != null) {
            secondInstance.close();
            secondInstance = null;
        }
    }

    private UUID newDocument(String title) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO documents (id, title, visibility) VALUES (?, ?, 'link')", id, title);
        return id;
    }

    private String tokenFor(String name) {
        return tokens.issue(new TokenService.Principal(UUID.randomUUID().toString(), name, "#4C5FD5"));
    }

    @Test
    @DisplayName("clients connected to DIFFERENT instances see each other's changes")
    void editsPropagateAcrossInstances() throws Exception {
        startSecondInstance();
        UUID document = newDocument("Cluster");

        try (TestStompClient onFirst = new TestStompClient(
                firstInstancePort, tokenFor("Anna"), document, 401);
             TestStompClient onSecond = new TestStompClient(
                 secondInstancePort, tokenFor("Marek"), document, 402)) {

            onFirst.insert(0, "from the first instance");
            onSecond.awaitCondition(() -> onSecond.text().contains("first"),
                PATIENCE, "the second instance receives the change");

            onSecond.insert(onSecond.text().length(), " and the second");
            onFirst.awaitCondition(() -> onFirst.text().contains("second"),
                PATIENCE, "the first instance receives the change");

            assertThat(onFirst.text()).isEqualTo(onSecond.text());
        }
    }

    @Test
    @DisplayName("stopping Redis mid-edit loses no changes — anti-entropy repairs them")
    void redisOutageIsRepairedByAntiEntropy() throws Exception {
        startSecondInstance();
        UUID document = newDocument("Redis outage");

        try (TestStompClient onFirst = new TestStompClient(
                firstInstancePort, tokenFor("Anna"), document, 411);
             TestStompClient onSecond = new TestStompClient(
                 secondInstancePort, tokenFor("Marek"), document, 412)) {

            onFirst.insert(0, "before the outage. ");
            onSecond.awaitCondition(() -> onSecond.text().contains("before the outage"),
                PATIENCE, "propagation before the outage");

            // A reference point from BEFORE the outage. Without it the condition "lastAckSeq > 0"
            // would already be satisfied by the previous operation and the test would pass without
            // waiting for anything — that is, it would check that it can not wait.
            long acknowledgedBeforeOutage = onFirst.lastAckSeq();

            // THE OUTAGE: Redis disappears mid-work. The fast path ceases to exist.
            REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
            try {
                // Editing MUST keep working — Redis is not on the critical write path.
                onFirst.insert(onFirst.text().length(), "during the outage. ");

                // Writing to the operation log, that is, to the SOURCE OF TRUTH, does not depend
                // on Redis.
                onFirst.awaitCondition(() -> onFirst.lastAckSeq() > acknowledgedBeforeOutage,
                    PATIENCE, "the write succeeds without Redis");

                assertThat(documents.textOf(document))
                    .as("editing stopped working when the FAST PATH went down — meaning it was "
                        + "not fast but critical")
                    .contains("during the outage");
            } finally {
                REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
            }

            // Once Redis is back, the second instance will not receive the lost messages — those
            // are gone for good. The ANTI-ENTROPY SWEEP is what has to repair it, pulling the
            // missing operations from the log. That is the whole thesis of this
            // architecture.
            onSecond.awaitCondition(() -> onSecond.text().contains("during the outage"),
                PATIENCE, "anti-entropy repairs the divergence after the Redis outage");

            assertThat(onSecond.text())
                .as("the instances did not converge after the fast path failed")
                .isEqualTo(onFirst.text());
        }
    }
}
