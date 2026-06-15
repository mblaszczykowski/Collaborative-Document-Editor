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

/**
 * Revoking access takes effect on every instance, not only the one that processed it.
 *
 * <h2>What this closes</h2>
 * {@link PermissionService} caches a resolved role for five seconds, so that authorizing a STOMP
 * frame is not a database query per keystroke. Invalidation on change was <b>local to the
 * instance</b>, and the code said so in a comment that named the fix and left it undone. On a
 * cluster that meant a revoked user kept writing for up to the cache TTL everywhere except the
 * instance that handled the revocation.
 *
 * <h2>Why this one is not "eventually consistent is fine"</h2>
 * Nearly everything else in this project is built on the opposite premise — ADR-0001 picks a CRDT
 * so replicas can disagree and converge, and ADR-0004 makes the cluster bus deliberately
 * unreliable on the same reasoning. That reasoning does not extend to access. A text operation
 * arriving late is still the same document; a revocation arriving late is a window in which the
 * system keeps doing what it was told to stop doing.
 */
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

    /** A document anybody logged in may edit, per rule 4 in {@link PermissionService}. */
    private UUID newLinkVisibleDocument() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO documents (id, title, visibility) VALUES (?, ?, 'link')",
            id, "invalidation-test");
        return id;
    }

    /**
     * Proves the cache exists, so that the test below is not vacuous.
     *
     * <p>This is the one that makes the pair worth having. If {@link PermissionService} did not
     * cache at all, the invalidation test would pass for the wrong reason and keep passing after
     * somebody removed the broadcast. Here the underlying row is changed <b>behind the service's
     * back</b> — straight through JDBC, so no local invalidation runs — and the stale answer is
     * asserted rather than tolerated.
     */
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
                caching was removed — in both cases this pair of tests needs rewriting rather \
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

        // Published as a DIFFERENT instance. Using the real channel and a real Redis, because the
        // filtering of one's own messages is part of what is being checked: sending this with
        // this instance's own id would correctly be ignored.
        redis.convertAndSend(
            PermissionInvalidationBus.CHANNEL, UUID.randomUUID() + " " + document);

        awaitRole(user, document);
    }

    /**
     * The instance that made the change must not process its own broadcast twice, and must still
     * have applied it locally.
     */
    @Test
    @DisplayName("a message from this instance is ignored, because it was already applied")
    void ownMessagesAreIgnored() throws Exception {
        String user = newUser();
        UUID document = newLinkVisibleDocument();

        assertThat(permissions.roleOf(user, document)).contains(Role.EDITOR);
        jdbc.update("UPDATE documents SET visibility = 'private' WHERE id = ?", document);

        redis.convertAndSend(
            PermissionInvalidationBus.CHANNEL, invalidations.instanceId() + " " + document);

        // Nothing should happen: the sender applies its own invalidation directly, so acting on
        // the echo would be redundant work on every permission change in the cluster.
        Thread.sleep(300);
        assertThat(permissions.roleOf(user, document))
            .as("this instance acted on its own echo, which means every change is applied twice")
            .contains(Role.EDITOR);
    }

    /**
     * The sending half.
     *
     * <p>Added after checking the other tests by mutation: commenting out the {@code publish} call
     * left all of them green, because they all exercise the <b>receiving</b> path within a single
     * instance. A pair of tests where one direction is untested is the shape of test suite that
     * reports a feature working when half of it is gone.
     *
     * <p>Subscribing a probe to the real channel rather than mocking the bus, for the reason
     * {@code IntegrationTestBase} gives for using a real Redis at all: what is being checked is
     * that a message actually leaves this process.
     */
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
            // Subscribing on a running container is asynchronous; without this the probe can miss
            // a message published immediately afterwards and the test fails for a reason that has
            // nothing to do with the code.
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
            cluster this is a revoked user who keeps writing until the cache expires — see \
            PermissionInvalidationBus for why the five-second bound is not an answer here.""");
    }
}
