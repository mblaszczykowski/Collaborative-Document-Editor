package dev.collabedit.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.collabedit.app.auth.PermissionService;
import dev.collabedit.app.auth.TokenService;
import dev.collabedit.app.domain.DocumentService;
import dev.collabedit.app.persist.DocumentCatalog;
import dev.collabedit.crdt.Id;
import dev.collabedit.crdt.Op;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = {
    "collabedit.persistence.max-sync-operations=5",
    "collabedit.persistence.max-pending-operations=50"
})
class RequestBoundsIT extends IntegrationTestBase {
    private static final Duration PATIENCE = Duration.ofSeconds(5);

    @LocalServerPort
    int port;

    @Autowired
    TokenService tokens;
    @Autowired
    PermissionService permissions;
    @Autowired
    DocumentCatalog catalog;
    @Autowired
    DocumentService documents;
    @Autowired
    JdbcTemplate jdbc;

    private record User(String id, String token) {
    }

    private User newUser(String name) {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO users (id, display_name, color) VALUES (?, ?, ?)",
            id, name, "#4C5FD5");
        return new User(id, tokens.issue(new TokenService.Principal(id, name, "#4C5FD5")));
    }

    private UUID documentOwnedBy(User owner, String title) {
        UUID id = UUID.randomUUID();
        catalog.create(id, title, null);
        permissions.setOwner(id, owner.id());
        permissions.setVisibility(id, "private");
        return id;
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + path))
            .timeout(Duration.ofSeconds(10))
            .GET();
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String token, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + path))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(10))
            .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("a suggestion larger than one frame is refused, not turned into an operation")
    void oversizedSuggestionIsRefused() throws Exception {
        User anna = newUser("Anna");
        UUID document = documentOwnedBy(anna, "Bounded suggestions");

        try (TestStompClient seed = new TestStompClient(port, anna.token(), document, 861)) {
            seed.insert(0, "seed");
            seed.awaitCondition(() -> seed.lastAckSeq() > 0, PATIENCE, "seed written");
        }

        String tooMuch = "x".repeat(9_000);
        HttpResponse<String> refused = post("/api/documents/" + document + "/suggestions",
            anna.token(), "{\"kind\":\"insert\",\"text\":\"" + tooMuch + "\"}");

        assertThat(refused.statusCode())
            .as("""
                A body of arbitrary size became a single Op.Insert. Nothing can split an operation \
                - its identity is what the merge algorithm is built on - so one larger than the \
                frame limit is permanently undeliverable to every client, silently, and stays in \
                the log being replayed on every load.""")
            .isEqualTo(400);
        assertThat(documents.textOf(document))
            .as("nothing was applied to the document either")
            .isEqualTo("seed");
    }

    @Test
    @DisplayName("a comment body past the bound is refused")
    void oversizedCommentIsRefused() throws Exception {
        User anna = newUser("Anna");
        UUID document = documentOwnedBy(anna, "Bounded comments");

        HttpResponse<String> refused = post("/api/documents/" + document + "/comments",
            anna.token(), "{\"body\":\"" + "y".repeat(5_000) + "\"}");

        assertThat(refused.statusCode()).isEqualTo(400);
    }

    @Test
    @DisplayName("a validation failure is a clean 400, not a 500 with Spring's default body")
    void aValidationFailureIsA400() throws Exception {
        User anna = newUser("Anna");

        HttpResponse<String> refused = post("/api/documents", anna.token(),
            "{\"title\":\"" + "t".repeat(1_000) + "\"}");

        assertThat(refused.statusCode())
            .as("""
                A bound exceeded has to read as the caller's mistake. A 500 says the server broke, \
                which is something a client retries - and retrying is exactly what a client does \
                with the request that caused it.""")
            .isEqualTo(400);
        assertThat(refused.body())
            .as("and it should say which field, so the client can say something true to the user")
            .contains("title");
    }

    @Test
    @DisplayName("a search phrase past the bound is refused")
    void aSearchPhrasePastTheBoundIsRefused() throws Exception {
        User anna = newUser("Anna");

        HttpResponse<String> refused = get("/api/documents?q=" + "z".repeat(1_000), anna.token());

        assertThat(refused.statusCode())
            .as("""
                An unbounded search phrase reaches websearch_to_tsquery, so a value the caller \
                chooses decides how much work the server does - which is the one rule this whole \
                test class exists to enforce.""")
            .isEqualTo(400);
    }

    @Test
    @DisplayName("a client burying the replica in causeless operations is refused explicitly")
    void aClientBuryingTheReplicaInCauselessOperationsIsRefused() {
        User anna = newUser("Anna");
        UUID document = documentOwnedBy(anna, "Bounded pending buffer");

        Id inventedOrigin = new Id(999_999, 12_345);
        for (int lamport = 1; lamport <= 50; lamport++) {
            documents.submit(document,
                new Op.Insert(new Id(4711, lamport), inventedOrigin, "x"), anna.id());
        }

        assertThatThrownBy(() -> documents.submit(document,
            new Op.Insert(new Id(4711, 51), inventedOrigin, "x"), anna.id()))
            .as("""
                The buffer of operations waiting for a cause had no bound at all, so a client \
                sending invented origins grew this instance's memory, the operation log and every \
                other client's pending map without limit - and every reload of the document then \
                logged "the log may be corrupt", which is a permanent false alarm about the one \
                condition that line exists to report.""")
            .isInstanceOf(DocumentService.ResyncRequiredException.class)
            .hasMessageContaining("waiting for a cause");
    }

    @Test
    @DisplayName("a resynchronisation too large to send is refused, not truncated")
    void aResyncTooLargeToSendIsRefusedRatherThanTruncated() throws Exception {
        User anna = newUser("Anna");
        UUID document = documentOwnedBy(anna, "Bounded resync");

        try (TestStompClient writer = new TestStompClient(port, anna.token(), document, 862)) {
            for (int i = 0; i < 12; i++) {
                writer.insert(i, "z");
            }
            writer.awaitCondition(() -> writer.lastAckSeq() >= 12, PATIENCE, "twelve written");
        }

        try (TestStompClient latecomer = new TestStompClient(port, anna.token(), document, 863)) {
            latecomer.requestSync(0);
            latecomer.awaitCondition(() -> latecomer.lastErrorCode() != null, PATIENCE,
                "the server answers something");

            assertThat(latecomer.lastErrorCode())
                .as("""
                    Twelve operations against a cap of five. The server has to say SNAPSHOT_REQUIRED \
                    rather than send a partial answer: SyncResponse.last marks the final chunk, and \
                    a client that received every chunk it was sent has no way to know it was sent \
                    fewer than it asked for. That is the failure this guards against - the client that needs the \
                    data most silently getting less than it needs.""")
                .isEqualTo("SNAPSHOT_REQUIRED");
            assertThat(latecomer.syncResponseCount())
                .as("no chunk may be sent at all, or the refusal is just a warning attached to a "
                    + "truncated answer")
                .isZero();
        }
    }
}
