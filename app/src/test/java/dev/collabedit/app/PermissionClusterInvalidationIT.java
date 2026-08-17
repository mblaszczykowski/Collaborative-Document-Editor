package dev.collabedit.app;

import static org.assertj.core.api.Assertions.assertThat;

import dev.collabedit.app.auth.PermissionService;
import dev.collabedit.app.auth.Role;
import dev.collabedit.app.cluster.PermissionInvalidationBus;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

class PermissionClusterInvalidationIT extends IntegrationTestBase {
    private static final Duration PATIENCE = Duration.ofSeconds(5);

    @Autowired
    PermissionService permissions;
    @Autowired
    PermissionInvalidationBus invalidations;
    @Autowired
    StringRedisTemplate redis;
    @Autowired
    org.springframework.data.redis.listener.RedisMessageListenerContainer listeners;
    @Autowired
    JdbcTemplate jdbc;

    private String newUser() {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO users (id, display_name, color) VALUES (?, ?, ?)",
            id, "Tester", "#123456");
        return id;
    }

    private UUID newLinkVisibleDocument() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO documents (id, title, visibility) VALUES (?, ?, 'link')",
            id, "invalidation-test");
        return id;
    }

    @Test
    @DisplayName("without an invalidation the cached role is served, stale")
    void staleRoleIsServedUntilInvalidated() {
        String user = newUser();
        UUID document = newLinkVisibleDocument();

        assertThat(permissions.roleOf(user, document)).contains(Role.EDITOR);

        jdbc.update("UPDATE documents SET visibility = 'private' WHERE id = ?", document);

        assertThat(permissions.roleOf(user, document))
            .as("""
                The role was re-resolved without an invalidation, so there is no cache to \
                invalidate and the test below proves nothing. Either the TTL changed or the \
                caching was removed - in both cases this pair of tests needs rewriting rather \
                than deleting.""")
            .contains(Role.EDITOR);
    }

    @Test
    @DisplayName("an invalidation from another instance drops this instance's cached role")
    void invalidationFromAnotherInstanceIsApplied() throws Exception {
        String user = newUser();
        UUID document = newLinkVisibleDocument();

        assertThat(permissions.roleOf(user, document)).contains(Role.EDITOR);
        jdbc.update("UPDATE documents SET visibility = 'private' WHERE id = ?", document);

        redis.convertAndSend(
            PermissionInvalidationBus.CHANNEL, UUID.randomUUID() + " " + document);

        awaitRole(user, document);
    }

    @Test
    @DisplayName("a message from this instance is ignored, because it was already applied")
    void ownMessagesAreIgnored() throws Exception {
        String user = newUser();
        UUID document = newLinkVisibleDocument();

        assertThat(permissions.roleOf(user, document)).contains(Role.EDITOR);
        jdbc.update("UPDATE documents SET visibility = 'private' WHERE id = ?", document);

        redis.convertAndSend(
            PermissionInvalidationBus.CHANNEL, invalidations.instanceId() + " " + document);

        Thread.sleep(300);
        assertThat(permissions.roleOf(user, document))
            .as("this instance acted on its own echo, which means every change is applied twice")
            .contains(Role.EDITOR);
    }

    @Test
    @DisplayName("changing access publishes an invalidation to the cluster")
    void changingAccessBroadcastsToTheCluster() throws Exception {
        UUID document = newLinkVisibleDocument();
        String user = newUser();

        java.util.concurrent.BlockingQueue<String> seen =
            new java.util.concurrent.LinkedBlockingQueue<>();
        org.springframework.data.redis.connection.MessageListener probe =
            (message, pattern) -> seen.add(
                new String(message.getBody(), java.nio.charset.StandardCharsets.UTF_8));

        listeners.addMessageListener(probe,
            new org.springframework.data.redis.listener.ChannelTopic(
                PermissionInvalidationBus.CHANNEL));
        try {
            Thread.sleep(250);

            permissions.grant(document, user, Role.VIEWER);

            String message = seen.poll(5, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(message)
                .as("""
                    Granting access published nothing, so the other instances keep serving the \
                    old role until their cache expires. That is the limitation this class exists \
                    to close.""")
                .isNotNull()
                .endsWith(document.toString());
        } finally {
            listeners.removeMessageListener(probe);
        }
    }

    private void awaitRole(String user, UUID document) throws InterruptedException {
        long deadline = System.currentTimeMillis() + PATIENCE.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (permissions.roleOf(user, document).isEmpty()) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("""
            The cached role survived an invalidation broadcast from another instance. On a \
            cluster this is a revoked user who keeps writing until the cache expires - see \
            PermissionInvalidationBus for why the five-second bound is not an answer here.""");
    }
}
