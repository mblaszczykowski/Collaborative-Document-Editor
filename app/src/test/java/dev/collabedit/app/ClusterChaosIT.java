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
        secondInstance = new SpringApplicationBuilder(CollabEditApplication.class)
            .run(
                "--server.port=0",
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--spring.data.redis.host=" + REDIS.getHost(),
                "--spring.data.redis.port=" + REDIS.getMappedPort(6379),
                "--spring.flyway.enabled=true",
                "--collabedit.persistence.op-batch-window-ms=20",
                "--collabedit.cluster.anti-entropy-interval-ms=1000",
                "--otel.sdk.disabled=true",
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
    @DisplayName("stopping Redis mid-edit loses no changes - anti-entropy repairs them")
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

            long acknowledgedBeforeOutage = onFirst.lastAckSeq();

            REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
            try {
                onFirst.insert(onFirst.text().length(), "during the outage. ");

                onFirst.awaitCondition(() -> onFirst.lastAckSeq() > acknowledgedBeforeOutage,
                    PATIENCE, "the write succeeds without Redis");

                assertThat(documents.textOf(document))
                    .as("editing stopped working when the FAST PATH went down - meaning it was "
                        + "not fast but critical")
                    .contains("during the outage");
            } finally {
                REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
            }

            onSecond.awaitCondition(() -> onSecond.text().contains("during the outage"),
                PATIENCE, "anti-entropy repairs the divergence after the Redis outage");

            assertThat(onSecond.text())
                .as("the instances did not converge after the fast path failed")
                .isEqualTo(onFirst.text());
        }
    }
}
