package dev.collabedit.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.collabedit.app.auth.InviteService;
import dev.collabedit.app.auth.PermissionService;
import dev.collabedit.app.auth.ReplicaIdAllocator;
import dev.collabedit.app.auth.Role;
import dev.collabedit.app.auth.TokenService;
import dev.collabedit.app.comments.CommentService;
import dev.collabedit.app.persist.DocumentCatalog;
import dev.collabedit.crdt.Id;
import dev.collabedit.crdt.Op;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class SecurityRegressionIT extends IntegrationTestBase {
    @Autowired CommentService comments;
    @Autowired PermissionService permissions;
    @Autowired InviteService invites;
    @Autowired DocumentCatalog catalog;
    @Autowired JdbcTemplate jdbc;
    @Autowired TokenService tokens;
    @Autowired ReplicaIdAllocator replicaIds;
    @Autowired PlatformTransactionManager transactions;
    @LocalServerPort int port;

    private String user() {
        String user = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO users (id, display_name, color) VALUES (?, 'Security test', '#123456')", user);
        return user;
    }

    private UUID document(String owner) {
        UUID document = UUID.randomUUID();
        catalog.create(document, "Private", null);
        permissions.setOwner(document, owner);
        return document;
    }

    private String token(String user) {
        return tokens.issue(new TokenService.Principal(user, "Security test", "#123456"));
    }

    @Test
    void retryKeyCannotDiscloseAnotherUsersPrivateCommentThroughHttp() throws Exception {
        String a = user(), b = user();
        UUID docA = document(a), docB = document(b);
        String key = UUID.randomUUID().toString();
        UUID secret = comments.openThread(docA, a, "private quote", "private body", key);
        HttpClient client = HttpClient.newHttpClient();
        var denied = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/documents/" + docA))
            .header("Authorization", "Bearer " + token(b)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(denied.statusCode()).isEqualTo(404);
        var response = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/documents/" + docB + "/comments"))
            .header("Authorization", "Bearer " + token(b)).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"quotedText\":\"\",\"body\":\"own body\",\"clientId\":\"" + key + "\"}"))
            .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains(docB.toString(), "own body")
            .doesNotContain(docA.toString(), secret.toString(), "private body", "private quote");
    }

    @Test
    void retryScopesAndPayloadConflictsProtectThreadsAndReplies() {
        String a = user(), b = user();
        UUID doc = document(a);
        String key = UUID.randomUUID().toString();
        UUID first = comments.openThread(doc, a, "quote", "body", key);
        assertThat(comments.openThread(doc, a, "quote", "body", key)).isEqualTo(first);
        assertThat(comments.openThread(doc, b, "quote", "body", key)).isNotEqualTo(first);
        assertThatThrownBy(() -> comments.openThread(doc, a, "changed quote", "body", key))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> comments.openThread(doc, a, "quote", "changed body", key))
            .isInstanceOf(IllegalStateException.class);
        UUID second = comments.openThread(doc, a, "", "other");
        UUID reply = comments.addMessage(first, a, "reply", key);
        assertThat(comments.addMessage(first, a, "reply", key)).isEqualTo(reply);
        assertThat(comments.addMessage(second, a, "reply", key)).isNotEqualTo(reply);
        assertThat(comments.addMessage(first, b, "reply", key)).isNotEqualTo(reply);
        assertThatThrownBy(() -> comments.addMessage(first, a, "changed", key))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void revocationCommittedWhileRedemptionWaitsPreventsMembershipGrant() throws Exception {
        String owner = user(), visitor = user();
        UUID doc = document(owner);
        var issued = invites.create(doc, Role.EDITOR, owner, null);
        CountDownLatch started = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var tx = new TransactionTemplate(transactions);
            var pending = tx.execute(status -> {
                jdbc.queryForList("SELECT id FROM documents WHERE id = ? FOR UPDATE", doc);
                var future = executor.submit(() -> {
                    started.countDown();
                    return invites.redeem(issued.token(), visitor);
                });
                try { assertThat(started.await(5, TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException e) { throw new AssertionError(e); }
                assertThat(invites.revoke(doc, issued.invite().id())).isTrue();
                return future;
            });
            assertThat(pending.get(5, TimeUnit.SECONDS)).isEmpty();
        }
        assertThat(permissions.roleOf(visitor, doc)).isEmpty();
    }

    @Test
    void concurrentInvitesCannotDowngradeAHigherExistingRole() throws Exception {
        String owner = user(), visitor = user();
        UUID doc = document(owner);
        var viewer = invites.create(doc, Role.VIEWER, owner, null);
        var editor = invites.create(doc, Role.EDITOR, owner, null);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var low = executor.submit(() -> { start.await(); return invites.redeem(viewer.token(), visitor); });
            var high = executor.submit(() -> { start.await(); return invites.redeem(editor.token(), visitor); });
            start.countDown();
            assertThat(low.get(5, TimeUnit.SECONDS)).isPresent();
            assertThat(high.get(5, TimeUnit.SECONDS)).contains(Role.EDITOR);
        }
        assertThat(permissions.roleOf(visitor, doc)).contains(Role.EDITOR);
    }

    @Test
    void replicaOwnershipSurvivesAllocatorRecreationAndRejectsOtherAccounts() {
        String a = user(), b = user();
        int id = replicaIds.nextFor(a);
        var restarted = new ReplicaIdAllocator(jdbc);
        assertThat(restarted.owns(a, id)).isTrue();
        assertThat(restarted.owns(b, id)).isFalse();
        assertThat(restarted.owns(a, -id)).isFalse();
        assertThat(restarted.owns(a, Integer.MAX_VALUE)).isFalse();
        assertThat(restarted.nextFor(a)).isGreaterThan(id);
    }
    @Test
    void websocketRejectsForgedReplicaIdsAndAcceptsOwnedOfflineReplay() throws Exception {
        String owner = user(), other = user();
        UUID doc = document(owner);
        int foreign = replicaIds.nextFor(other);
        int ownOld = replicaIds.nextFor(owner);
        for (int forged : new int[] {foreign, -1, Integer.MAX_VALUE}) {
            try (var client = new TestStompClient(port, token(owner), doc, 1)) {
                client.sendRawOperation(new Op.Insert(new Id(forged, 1), null, "forged"));
                client.awaitCondition(() -> "REPLICA_ID_CONFLICT".equals(client.lastErrorCode()),
                    Duration.ofSeconds(5), "forged replica refusal");
            }
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM operations WHERE doc_id = ?", Long.class, doc)).isZero();
        try (var client = new TestStompClient(port, token(owner), doc, 1)) {
            client.sendRawOperation(new Op.Insert(new Id(ownOld, 1), null, "owned replay"));
            client.awaitCondition(() -> client.lastAckSeq() > 0, Duration.ofSeconds(5), "owned offline replay acknowledged");
            assertThat(client.text()).isEqualTo("owned replay");
        }
    }

    @Test
    void invitationCreatesLastingMembershipEvenWhileDocumentIsLinkVisible() {
        String owner = user(), visitor = user();
        UUID doc = document(owner);
        permissions.setVisibility(doc, "link");
        var invite = invites.create(doc, Role.VIEWER, owner, null);
        assertThat(invites.redeem(invite.token(), visitor)).contains(Role.VIEWER);
        permissions.setVisibility(doc, "private");
        assertThat(permissions.roleOf(visitor, doc)).contains(Role.VIEWER);
    }

}
