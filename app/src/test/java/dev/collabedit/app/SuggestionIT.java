package dev.collabedit.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.collabedit.app.auth.PermissionService;
import dev.collabedit.app.auth.Role;
import dev.collabedit.app.auth.TokenService;
import dev.collabedit.app.domain.DocumentService;
import dev.collabedit.app.persist.DocumentCatalog;
import dev.collabedit.app.suggestions.SuggestionService;
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

/**
 * Suggestion mode (CP23) — the first thing the {@code commenter} role can DO.
 *
 * <p>Since CP12 a commenter has been someone who may read and talk but not change the text. In
 * practice that meant a person who could only ever write <i>about</i> a document. A suggestion is
 * a contribution <b>to</b> it that still does not carry the permission to change it — which is
 * what the role was for all along.
 *
 * <table>
 *   <caption>Test → failure mode</caption>
 *   <tr><th>Test</th><th>What it catches</th></tr>
 *   <tr><td>{@code commenterMayProposeAndMayNotDecide}</td>
 *       <td>The two capabilities collapsing into one. If proposing and accepting need the same
 *           permission, suggestion mode is either an editor's toy or a hole.</td></tr>
 *   <tr><td>{@code acceptingAnInsertionKeepsTheText}, {@code rejectingAnInsertionRemovesIt}</td>
 *       <td>The four outcomes crossed over. Accept-insert and reject-delete both mean "the text
 *           stays"; the other two mean "the text goes". Swapping a pair silently deletes work.
 *   </td></tr>
 *   <tr><td>{@code aProposalMovesWithTheTextAroundIt}</td>
 *       <td>Positions stored as offsets. Somebody typing before the proposal would leave it
 *           pointing at different words — attributed to a person who never wrote them.</td></tr>
 *   <tr><td>{@code decidingTwiceChangesNothingTheSecondTime}</td>
 *       <td>Two editors pressing accept at the same moment, from two instances.</td></tr>
 *   <tr><td>{@code aProposalWhoseTextIsGoneResolvesWithoutPretending}</td>
 *       <td>An accept that has nothing to apply reporting success as if it had applied
 *           something.</td></tr>
 * </table>
 */
class SuggestionIT extends IntegrationTestBase {

    private static final Duration PATIENCE = Duration.ofSeconds(10);
    private static final ObjectMapper JSON = new ObjectMapper();

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
    @Autowired
    DocumentService documents;
    @Autowired
    SuggestionService suggestions;

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

    private HttpResponse<String> send(String method, String path, String token, String body)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + path))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(10))
            .method(method, body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return HttpClient.newHttpClient().send(builder.build(),
            HttpResponse.BodyHandlers.ofString());
    }

    /** Seeds a document with text and returns the operation that produced it. */
    private Op seed(UUID document, User author, int replicaId, String text) throws Exception {
        try (TestStompClient client =
                 new TestStompClient(port, author.token(), document, replicaId)) {
            Op op = client.insertReturningOp(0, text);
            client.awaitCondition(() -> client.lastAckSeq() > 0, PATIENCE, "seed written");
            return op;
        }
    }

    /** The identifier of the character at {@code index} of a run inserted in one operation. */
    private static String wireId(Op insertion, int index) {
        return "{\"replicaId\":" + insertion.id().replicaId()
            + ",\"lamport\":" + (insertion.id().lamport() + index) + "}";
    }

    private String proposeInsertion(UUID document, User author, Op seedOp, int afterIndex,
                                    String text) throws Exception {
        HttpResponse<String> response = send("POST", "/api/documents/" + document + "/suggestions",
            author.token(),
            "{\"kind\":\"insert\",\"text\":\"" + text + "\",\"after\":"
                + wireId(seedOp, afterIndex) + "}");
        assertThat(response.statusCode()).isEqualTo(200);
        return JSON.readTree(response.body()).get("id").asText();
    }

    private String proposeDeletion(UUID document, User author, Op seedOp, int from, int to)
            throws Exception {
        HttpResponse<String> response = send("POST", "/api/documents/" + document + "/suggestions",
            author.token(),
            "{\"kind\":\"delete\",\"from\":" + wireId(seedOp, from) + ",\"to\":"
                + wireId(seedOp, to) + "}");
        assertThat(response.statusCode()).isEqualTo(200);
        return JSON.readTree(response.body()).get("id").asText();
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("a commenter may propose a change and may not decide on one")
    void commenterMayProposeAndMayNotDecide() throws Exception {
        User anna = newUser("Anna");
        User critic = newUser("Critic");
        User watcher = newUser("Watcher");
        UUID document = privateDocument(anna, "Suggestions");
        permissions.grant(document, critic.id(), Role.COMMENTER);
        permissions.grant(document, watcher.id(), Role.VIEWER);

        Op seedOp = seed(document, anna, 811, "The cat sat");

        String id = proposeInsertion(document, critic, seedOp, 10, " on the mat");

        assertThat(send("POST",
            "/api/documents/" + document + "/suggestions/" + id + "/accept",
            critic.token(), null).statusCode())
            .as("deciding is an edit; a commenter who could accept their own suggestion would "
                + "simply be an editor with extra steps")
            .isEqualTo(404);

        assertThat(send("POST", "/api/documents/" + document + "/suggestions",
            watcher.token(), "{\"kind\":\"insert\",\"text\":\"x\",\"after\":"
                + wireId(seedOp, 1) + "}").statusCode())
            .as("a viewer may read and nothing else")
            .isEqualTo(404);

        assertThat(send("POST",
            "/api/documents/" + document + "/suggestions/" + id + "/accept",
            anna.token(), null).statusCode()).isEqualTo(204);
    }

    @Test
    @DisplayName("accepting an insertion keeps the text and drops the proposal")
    void acceptingAnInsertionKeepsTheText() throws Exception {
        User anna = newUser("Anna");
        User critic = newUser("Critic");
        UUID document = privateDocument(anna, "Accept insert");
        permissions.grant(document, critic.id(), Role.COMMENTER);

        Op seedOp = seed(document, anna, 812, "The cat sat");
        String id = proposeInsertion(document, critic, seedOp, 10, " on the mat");

        assertThat(documents.textOf(document))
            .as("a proposed insertion is real text from the moment it is proposed — that is what "
                + "lets everybody see it merge like any other change")
            .isEqualTo("The cat sat on the mat");
        assertThat(suggestions.openFor(document)).hasSize(1);

        send("POST", "/api/documents/" + document + "/suggestions/" + id + "/accept",
            anna.token(), null);
        Thread.sleep(300);

        assertThat(documents.textOf(document)).isEqualTo("The cat sat on the mat");
        assertThat(suggestions.openFor(document)).isEmpty();
    }

    @Test
    @DisplayName("rejecting an insertion removes the proposed text")
    void rejectingAnInsertionRemovesIt() throws Exception {
        User anna = newUser("Anna");
        User critic = newUser("Critic");
        UUID document = privateDocument(anna, "Reject insert");
        permissions.grant(document, critic.id(), Role.COMMENTER);

        Op seedOp = seed(document, anna, 813, "The cat sat");
        String id = proposeInsertion(document, critic, seedOp, 10, " on the mat");

        send("POST", "/api/documents/" + document + "/suggestions/" + id + "/reject",
            anna.token(), null);
        Thread.sleep(300);

        assertThat(documents.textOf(document)).isEqualTo("The cat sat");
        assertThat(suggestions.openFor(document)).isEmpty();
    }

    @Test
    @DisplayName("accepting a deletion removes the text; rejecting one leaves it alone")
    void decidingOnADeletion() throws Exception {
        User anna = newUser("Anna");
        User critic = newUser("Critic");
        UUID document = privateDocument(anna, "Deletions");
        permissions.grant(document, critic.id(), Role.COMMENTER);

        Op seedOp = seed(document, anna, 814, "The cat sat");

        String rejected = proposeDeletion(document, critic, seedOp, 0, 2);
        send("POST", "/api/documents/" + document + "/suggestions/" + rejected + "/reject",
            anna.token(), null);
        Thread.sleep(300);
        assertThat(documents.textOf(document))
            .as("nothing is deleted until somebody accepts it")
            .isEqualTo("The cat sat");

        String accepted = proposeDeletion(document, critic, seedOp, 7, 10);
        send("POST", "/api/documents/" + document + "/suggestions/" + accepted + "/accept",
            anna.token(), null);
        Thread.sleep(300);
        assertThat(documents.textOf(document)).isEqualTo("The cat");
    }

    @Test
    @DisplayName("a proposal moves with the text around it")
    void aProposalMovesWithTheTextAroundIt() throws Exception {
        User anna = newUser("Anna");
        User critic = newUser("Critic");
        UUID document = privateDocument(anna, "Anchored proposals");
        permissions.grant(document, critic.id(), Role.COMMENTER);

        Op seedOp = seed(document, anna, 815, "The cat sat");
        String id = proposeDeletion(document, critic, seedOp, 7, 10);

        var before = suggestions.openFor(document).getFirst();
        assertThat(before.range().from()).isEqualTo(7);

        // Somebody types BEFORE the proposal. An offset would now point four characters short.
        try (TestStompClient anna2 = new TestStompClient(port, anna.token(), document, 816)) {
            anna2.insert(0, "Yes: ");
            anna2.awaitCondition(() -> anna2.lastAckSeq() > 1, PATIENCE, "insertion");
        }
        Thread.sleep(200);

        var after = suggestions.openFor(document).getFirst();
        assertThat(after.range().from())
            .as("the proposal is anchored to characters, not to positions — the same principle "
                + "as cursors and marks")
            .isEqualTo(12);
        assertThat(UUID.fromString(id)).isEqualTo(after.id());

        send("POST", "/api/documents/" + document + "/suggestions/" + id + "/accept",
            anna.token(), null);
        Thread.sleep(300);
        assertThat(documents.textOf(document))
            .as("and it deletes the words it was attached to, not the ones at the old offset")
            .isEqualTo("Yes: The cat");
    }

    @Test
    @DisplayName("deciding twice changes nothing the second time")
    void decidingTwiceChangesNothingTheSecondTime() throws Exception {
        User anna = newUser("Anna");
        UUID document = privateDocument(anna, "Idempotence");

        Op seedOp = seed(document, anna, 817, "The cat sat");
        String id = proposeInsertion(document, anna, seedOp, 10, " on the mat");

        assertThat(send("POST",
            "/api/documents/" + document + "/suggestions/" + id + "/accept",
            anna.token(), null).statusCode()).isEqualTo(204);
        Thread.sleep(200);

        assertThat(send("POST",
            "/api/documents/" + document + "/suggestions/" + id + "/reject",
            anna.token(), null).statusCode())
            .as("the second decision must not re-open a settled one — two editors pressing at "
                + "the same moment is the ordinary case, not the exotic one")
            // 409, not 404, and the change is the point rather than a cosmetic renumbering.
            // "Already decided" and "no such proposal" used to be the same answer, because
            // SuggestionService.resolve returned one boolean for both — and they mean opposite
            // things on screen: one says the list is stale in a way re-reading fixes, the other
            // says the link is wrong. CommentController answers 409 for the same situation one
            // package over, which is now the convention everywhere.
                .isEqualTo(409);
        assertThat(documents.textOf(document)).isEqualTo("The cat sat on the mat");
    }

    @Test
    @DisplayName("a proposal that cannot be recorded does not change the document either")
    void aProposalThatCannotBeRecordedLeavesNoTrace() throws Exception {
        User anna = newUser("Anna");
        UUID document = privateDocument(anna, "Ordering");
        Op seedOp = seed(document, anna, 820, "The cat sat");

        // An author the users table does not know. The row hits a foreign key and the insert
        // throws — which is the point of the test rather than its setup: whatever fails while a
        // proposal is being made, the in-memory document must not be left holding text that was
        // never written to the log and never broadcast. That state survives no restart and no
        // other client ever sees it.
        var missingAuthor = new User(UUID.randomUUID().toString(), null);
        assertThatThrownBy(() -> suggestions.proposeInsertion(document, missingAuthor.id(),
            new dev.collabedit.crdt.Id(seedOp.id().replicaId(), seedOp.id().lamport() + 10),
            " on the mat"))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        Thread.sleep(200);

        assertThat(documents.textOf(document))
            .as("the row is written BEFORE the replica is touched, so a failure to record leaves "
                + "the document exactly as it was")
            .isEqualTo("The cat sat");
        assertThat(suggestions.openFor(document)).isEmpty();
    }

    @Test
    @DisplayName("a proposal whose text somebody deleted resolves without pretending")
    void aProposalWhoseTextIsGoneResolvesWithoutPretending() throws Exception {
        User anna = newUser("Anna");
        User critic = newUser("Critic");
        UUID document = privateDocument(anna, "Vanished");
        permissions.grant(document, critic.id(), Role.COMMENTER);

        Op seedOp = seed(document, anna, 818, "The cat sat");
        String id = proposeDeletion(document, critic, seedOp, 7, 10);

        // An editor deletes the anchored fragment the ordinary way, without touching the
        // suggestion.
        try (TestStompClient anna2 = new TestStompClient(port, anna.token(), document, 819)) {
            // Sync first: a delete is expressed against the local replica, and a client that has
            // not caught up would be describing a range of a document it has not got.
            anna2.requestSync(0);
            anna2.awaitCondition(() -> anna2.text().equals("The cat sat"), PATIENCE, "caught up");

            // Captured BEFORE the delete, and compared against. `> 1` was already true when it was
            // asked: the sync above raises lastAckSeq to the head, which is 2 by this point (the
            // seed and the suggest-delete mark). So the await returned on its first poll, proved
            // nothing about the deletion, and the sleep below was doing the actual waiting.
            long beforeDelete = anna2.lastAckSeq();
            anna2.delete(7, 4);
            anna2.awaitCondition(() -> anna2.lastAckSeq() > beforeDelete, PATIENCE, "deletion");
        }

        var open = suggestions.openFor(document);
        assertThat(open).hasSize(1);
        assertThat(open.getFirst().anchored())
            .as("the proposal is still listed, and it is honest about having nowhere to apply")
            .isFalse();
        assertThat(open.getFirst().quotedText())
            .as("what it was about survives in the row, for the same reason a detached comment "
                + "keeps its quotation")
            .isEqualTo(" sat");

        assertThat(send("POST",
            "/api/documents/" + document + "/suggestions/" + id + "/accept",
            anna.token(), null).statusCode()).isEqualTo(204);
        Thread.sleep(200);
        assertThat(documents.textOf(document)).isEqualTo("The cat");
        assertThat(suggestions.openFor(document)).isEmpty();
    }
}
