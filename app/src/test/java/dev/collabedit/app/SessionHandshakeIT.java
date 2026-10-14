package dev.collabedit.app;

import static org.assertj.core.api.Assertions.assertThat;

import dev.collabedit.app.auth.PermissionService;
import dev.collabedit.app.auth.TokenService;
import dev.collabedit.app.persist.DocumentCatalog;
import java.time.Duration;
import java.util.HashSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

class SessionHandshakeIT extends IntegrationTestBase {
    @LocalServerPort int port;
    @Autowired TokenService tokens;
    @Autowired JdbcTemplate jdbc;
    @Autowired PermissionService permissions;
    @Autowired DocumentCatalog catalog;

    @Test
    void immediateSubscribeThenSessionRequestAlwaysReceivesIdentityBeforeEditing() throws Exception {
        String user = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO users(id, display_name, color) VALUES (?, 'handshake', '#123456')", user);
        String token = tokens.issue(new TokenService.Principal(user, "handshake", "#123456"));
        var assigned = new HashSet<Integer>();
        for (int attempt = 0; attempt < 20; attempt++) {
            UUID document = UUID.randomUUID();
            catalog.create(document, "Ordered handshake", null);
            permissions.setOwner(document, user);
            // The constructor sends the session request immediately after SUBSCRIBE, with no
            // sleep, receipt wait, or retry that could conceal inbound subscription reordering.
            try (var client = new TestStompClient(port, token, document, 1)) {
                var inserted = client.insertReturningOp(0, "ready");
                assertThat(assigned.add(inserted.id().replicaId())).isTrue();
                client.awaitCondition(() -> client.lastAckSeq() >= 1,
                    Duration.ofSeconds(10), "first edit after identity handshake");
            }
        }
        assertThat(assigned).hasSize(20);
    }
}
