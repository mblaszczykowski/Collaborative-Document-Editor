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

/**
 * The limits on what a single request may ask the server to hold, build or send.
 *
 * <h2>What these have in common</h2>
 * Each one is a place where a value the CLIENT chooses decided how much work the server did, with
 * no upper bound and no refusal — and in every case the failure was quiet rather than loud:
 *
 * <table>
 *   <caption>Test -> failure mode</caption>
 *   <tr><th>Test</th><th>What it catches</th></tr>
 *   <tr><td>{@code oversizedSuggestionIsRefused}</td>
 *       <td>An endpoint building an operation the protocol cannot carry.
 *           {@code SuggestionService.proposeInsertion} turned the request body straight into ONE
 *           {@code Op.Insert}, and nothing can split an operation afterwards — its identity is what
 *           the merge algorithm is built on. An operation larger than the 64 KiB frame limit sits
 *           in the log forever, undeliverable to every client including its author, with no error
 *           anywhere. {@code chunkByBytes}'s javadoc named this exact limit and put the mitigation
 *           on our own client, which is not a mitigation for an endpoint anybody can call.</td></tr>
 *   <tr><td>{@code oversizedCommentIsRefused}</td>
 *       <td>The same shape one package over, at the LOWEST role that can write anything: a
 *           commenter posting arbitrary bytes into the database.</td></tr>
 *   <tr><td>{@code aValidationFailureIsA400}</td>
 *       <td>The bounds above arriving as a 500 with Spring's default body. A refusal the client
 *           cannot tell from a server fault is a refusal it will retry.</td></tr>
 *   <tr><td>{@code aClientBuryingTheReplicaInCauselessOperationsIsRefused}</td>
 *       <td>An unbounded pending buffer. {@code RgaDocument} buffers an operation whose origin it
 *           has not seen, and {@code DocumentReplica.merge} deliberately WRITES and BROADCASTS
 *           buffered operations — so operations naming invented origins grew this instance's
 *           memory, the operation log and every other client's pending map without bound, and every
 *           reload logged "the log may be corrupt" for good.</td></tr>
 *   <tr><td>{@code aResyncTooLargeToSendIsRefusedRatherThanTruncated}</td>
 *       <td>An unbounded, unpaginated full-log read driven by a client-supplied cursor — on the
 *           ORDINARY recovery path, because the shipped client sends 0 whenever it detects a gap.
 *           The important half of the assertion is that the refusal is explicit: a truncated
 *           response that looked complete would be the CP8 failure again.</td></tr>
 * </table>
 *
 * <h2>Why its own context</h2>
 * The two caps are configured low here — tens rather than thousands — because the alternative is a
 * test that has to generate five thousand operations to reach a boundary whose behaviour does not
 * depend on where the boundary is. The cost is one extra Spring context; the benefit is that the
 * test finishes in seconds and says what it means.
 */
@TestPropertySource(properties = {
    "collabedit.persistence.max-sync-operations=5",
    // Low, but not AS low as the sync cap, and the difference is a finding rather than a fudge.
    // The inbound STOMP channel is a multi-threaded executor with no preserveReceiveOrder, so a
    // client sending a dozen operations in a burst can have several of them merged out of order —
    // each one buffered until its cause catches up, all within a few milliseconds. That is exactly
    // the legitimate backlog the production default of 10 000 is chosen to sit far above; a cap of
    // five sits BELOW it, and the first version of this file set it there and refused the other
    // test's perfectly ordinary writes. Fifty is still two orders of magnitude below production
    // and comfortably above one client's transient reordering.
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

    // ------------------------------------------------------------------
    // Bodies that would become one oversized operation
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a suggestion larger than one frame is refused, not turned into an operation")
    void oversizedSuggestionIsRefused() throws Exception {
        User anna = newUser("Anna");
        UUID document = documentOwnedBy(anna, "Bounded suggestions");

        try (TestStompClient seed = new TestStompClient(port, anna.token(), document, 861)) {
            seed.insert(0, "seed");
            seed.awaitCondition(() -> seed.lastAckSeq() > 0, PATIENCE, "seed written");
        }

        // 9000 characters, just past the 8192 the client's paste path caps at. The margin is small
        // on purpose: the number that matters is the one the client also uses, so the server and
        // the browser produce operations of the same shape rather than two shapes that both
        // happen to work today.
        String tooMuch = "x".repeat(9_000);
        HttpResponse<String> refused = post("/api/documents/" + document + "/suggestions",
            anna.token(), "{\"kind\":\"insert\",\"text\":\"" + tooMuch + "\"}");

        assertThat(refused.statusCode())
            .as("""
                A body of arbitrary size became a single Op.Insert. Nothing can split an operation \
                — its identity is what the merge algorithm is built on — so one larger than the \
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
                which is something a client retries — and retrying is exactly what a client does \
                with the request that caused it.""")
            .isEqualTo(400);
        assertThat(refused.body())
            .as("and it should say which field, so the client can say something true to the user")
            .contains("title");
    }

    /**
     * The first bound in this project on a request PARAMETER rather than on a body field.
     *
     * <p>It is asserted rather than assumed because the mechanism is different, and differently
     * silent. A {@code @Size} on a record component is enforced by {@code @Valid} on the parameter,
     * which this project uses everywhere and {@code aValidationFailureIsA400} above already pins.
     * A {@code @Size} on a {@code @RequestParam} is enforced by Spring's built-in method validation
     * — no {@code @Valid} anywhere, nothing at the call site to notice if it stops working — and it
     * surfaces as a different exception type ({@code HandlerMethodValidationException}, which is why
     * {@code ApiExceptionHandler} maps that one too).
     *
     * <p>A bound that reads as enforced and is not is worse than no bound, because it stops anybody
     * looking. So this test exists to say the annotation actually fires.
     */
    @Test
    @DisplayName("a search phrase past the bound is refused")
    void aSearchPhrasePastTheBoundIsRefused() throws Exception {
        User anna = newUser("Anna");

        HttpResponse<String> refused = get("/api/documents?q=" + "z".repeat(1_000), anna.token());

        assertThat(refused.statusCode())
            .as("""
                An unbounded search phrase reaches websearch_to_tsquery, so a value the caller \
                chooses decides how much work the server does — which is the one rule this whole \
                test class exists to enforce.""")
            .isEqualTo(400);
    }

    // ------------------------------------------------------------------
    // Work the server would otherwise hold or send without limit
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a client burying the replica in causeless operations is refused explicitly")
    void aClientBuryingTheReplicaInCauselessOperationsIsRefused() {
        User anna = newUser("Anna");
        UUID document = documentOwnedBy(anna, "Bounded pending buffer");

        // Every one of these names an origin that does not exist and never will, so RgaDocument
        // buffers it — and merge writes and broadcasts buffered operations on purpose, because a
        // complete operation that is merely EARLY must not be lost if the instance dies.
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
                other client's pending map without limit — and every reload of the document then \
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
                    fewer than it asked for. That is the CP8 failure — the client that needs the \
                    data most silently getting less than it needs.""")
                .isEqualTo("SNAPSHOT_REQUIRED");
            assertThat(latecomer.syncResponseCount())
                .as("no chunk may be sent at all, or the refusal is just a warning attached to a "
                    + "truncated answer")
                .isZero();
        }
    }
}
