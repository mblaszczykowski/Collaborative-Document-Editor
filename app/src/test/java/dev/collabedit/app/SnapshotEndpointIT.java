package dev.collabedit.app;

import static org.assertj.core.api.Assertions.assertThat;

import dev.collabedit.app.api.DocumentSnapshotController;
import dev.collabedit.app.auth.PermissionService;
import dev.collabedit.app.auth.TokenService;
import dev.collabedit.app.persist.DocumentCatalog;
import dev.collabedit.crdt.RgaDocument;
import dev.collabedit.crdt.SnapshotCodec;
import dev.collabedit.crdt.TreapBlockList;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

class SnapshotEndpointIT extends IntegrationTestBase {
    private static final Logger log = LoggerFactory.getLogger(SnapshotEndpointIT.class);
    private static final Duration PATIENCE = Duration.ofSeconds(15);

    @LocalServerPort
    int port;

    @Autowired
    TokenService tokens;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    PermissionService permissions;
    @Autowired
    DocumentCatalog catalog;

    private record User(String id, String token) {
    }

    private User newUser(String name) {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO users (id, display_name, color) VALUES (?, ?, ?) "
            + "ON CONFLICT (id) DO NOTHING", id, name, "#4C5FD5");
        return new User(id, tokens.issue(new TokenService.Principal(id, name, "#4C5FD5")));
    }

    private UUID privateDocument(User owner, String title) {
        UUID id = UUID.randomUUID();
        catalog.create(id, title, null);
        permissions.setOwner(id, owner.id());
        permissions.setVisibility(id, "private");
        return id;
    }

    private HttpResponse<byte[]> fetchSnapshot(UUID document, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/api/documents/" + document + "/snapshot"))
            .timeout(Duration.ofSeconds(10))
            .GET();
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    @Test
    @DisplayName("the snapshot rebuilds exactly the document the server holds")
    void snapshotRebuildsTheSameDocument() throws Exception {
        User anna = newUser("Anna");
        UUID document = privateDocument(anna, "Snapshot");

        try (TestStompClient client = new TestStompClient(port, anna.token(), document, 901)) {
            client.insert(0, "The cat sat on the mat");
            client.awaitCondition(() -> client.lastAckSeq() >= 1, PATIENCE, "text");
            client.markReturningOp(0, 3, "bold", null);
            client.awaitCondition(() -> client.lastAckSeq() >= 2, PATIENCE, "mark");
            client.delete(11, 11);
            client.awaitCondition(() -> client.lastAckSeq() >= 3, PATIENCE, "deletion");
        }

        HttpResponse<byte[]> response = fetchSnapshot(document, anna.token());
        assertThat(response.statusCode()).isEqualTo(200);

        var decoded = SnapshotCodec.decode(response.body());
        assertThat(decoded).isNotNull();
        RgaDocument rebuilt = RgaDocument.restore(0, new TreapBlockList(), decoded);

        assertThat(rebuilt.text()).isEqualTo("The cat sat");
        assertThat(rebuilt.formatting())
            .as("a snapshot without the marks would open every document unformatted, and the "
                + "first edit would merge that back to everybody")
            .isNotEmpty();
    }

    @Test
    @DisplayName("the sequence number is never ahead of the state in the snapshot")
    void theSequenceNumberNeverRunsAheadOfTheSnapshot() throws Exception {
        User anna = newUser("Anna");
        UUID document = privateDocument(anna, "Boundary");

        long lastAck;
        try (TestStompClient client = new TestStompClient(port, anna.token(), document, 902)) {
            for (int i = 0; i < 20; i++) {
                client.insert(i, "x");
            }
            client.awaitCondition(() -> client.lastAckSeq() >= 20, PATIENCE, "writes");
            lastAck = client.lastAckSeq();
        }

        HttpResponse<byte[]> response = fetchSnapshot(document, anna.token());
        long through = Long.parseLong(
            response.headers().firstValue(DocumentSnapshotController.THROUGH_SEQ_HEADER)
                .orElseThrow());

        assertThat(through)
            .as("resynchronising from a point AHEAD of the snapshot skips operations, which is "
                + "the one error here that loses text rather than costing bytes")
            .isLessThanOrEqualTo(lastAck);
    }

    @Test
    @DisplayName("somebody without access gets nothing, on this route as well")
    void aStrangerGetsNothing() throws Exception {
        User anna = newUser("Anna");
        User stranger = newUser("Stranger");
        UUID document = privateDocument(anna, "Private");

        try (TestStompClient client = new TestStompClient(port, anna.token(), document, 903)) {
            client.insert(0, "secret");
            client.awaitCondition(() -> client.lastAckSeq() >= 1, PATIENCE, "text");
        }

        assertThat(fetchSnapshot(document, stranger.token()).statusCode()).isEqualTo(404);
        assertThat(fetchSnapshot(document, null).statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("the snapshot is decisively smaller than the log it replaces")
    void snapshotIsSmallerThanTheLogItReplaces() throws Exception {
        User anna = newUser("Anna");
        UUID document = privateDocument(anna, "Measurement");

        String prose = "Another sentence of the document, typed one character at a time, the way real "
            + "writing goes in an editor several people share at once. ";
        try (TestStompClient client = new TestStompClient(port, anna.token(), document, 904)) {
            for (int i = 0; i < 200; i++) {
                client.insert(i, String.valueOf(prose.charAt(i % prose.length())));
                Thread.sleep(20);
            }
            client.awaitCondition(() -> client.lastAckSeq() >= 200, PATIENCE, "writes");
        }

        Long logBytes = jdbc.queryForObject(
            "SELECT COALESCE(SUM(LENGTH(payload::text)), 0) FROM operations WHERE doc_id = ?",
            Long.class, document);
        int snapshotBytes = fetchSnapshot(document, anna.token()).body().length;

        log.info("open cost for 200 operations: log {} B, snapshot {} B ({}x smaller)",
            logBytes, snapshotBytes,
            String.format("%.1f", logBytes / (double) snapshotBytes));

        assertThat(snapshotBytes)
            .as("if the snapshot were not decisively smaller than the log, this checkpoint would "
                + "have no reason to exist - the number belongs in the README either way")
            .isLessThan(logBytes.intValue() / 4);
    }
}
