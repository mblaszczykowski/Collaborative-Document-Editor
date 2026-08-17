package dev.collabedit.app;

import static org.assertj.core.api.Assertions.assertThat;

import dev.collabedit.app.auth.PermissionService;
import dev.collabedit.app.auth.Role;
import dev.collabedit.app.auth.TokenService;
import dev.collabedit.app.comments.CommentService;
import dev.collabedit.app.domain.DocumentRegistry;
import dev.collabedit.app.domain.DocumentService;
import dev.collabedit.app.history.HistoryService;
import dev.collabedit.app.persist.DocumentCatalog;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

class CommentIT extends IntegrationTestBase {
    private static final Duration PATIENCE = Duration.ofSeconds(5);

    @LocalServerPort
    int port;

    @Autowired
    TokenService tokens;
    @Autowired
    PermissionService permissions;
    @Autowired
    CommentService comments;
    @Autowired
    DocumentCatalog catalog;
    @Autowired
    DocumentService documents;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    HistoryService history;
    @Autowired
    DocumentRegistry registry;

    private record User(String id, String token) {
    }

    private User newUser(String name) {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO users (id, display_name, color) VALUES (?, ?, ?) "
            + "ON CONFLICT (id) DO NOTHING", id, name, "#4C5FD5");
        return new User(id, tokens.issue(new TokenService.Principal(id, name, "#4C5FD5")));
    }

    private UUID privateDocument(User owner) {
        UUID id = UUID.randomUUID();
        catalog.create(id, "With comments", null);
        permissions.setOwner(id, owner.id());
        permissions.setVisibility(id, "private");
        return id;
    }

    @Test
    @DisplayName("a commenter CANNOT change the text, despite using the same channel")
    void commenterCannotChangeText() throws Exception {
        User ania = newUser("Ania");
        User critic = newUser("Critic");
        UUID document = privateDocument(ania);
        permissions.grant(document, critic.id(), Role.COMMENTER);

        try (TestStompClient owner = new TestStompClient(port, ania.token(), document, 801)) {
            owner.insert(0, "the original text");
            owner.awaitCondition(() -> owner.lastAckSeq() > 0, PATIENCE, "write acknowledged");
        }

        try (TestStompClient commenter = new TestStompClient(port, critic.token(), document, 802)) {
            commenter.requestSync(0);
            commenter.awaitCondition(() -> commenter.text().equals("the original text"),
                PATIENCE, "the commenter sees the text");

            commenter.insert(0, "VANDALISM");
            Thread.sleep(500);

            assertThat(documents.textOf(document))
                .as("the commenter role changed the document's content")
                .isEqualTo("the original text");
        }
    }

    @Test
    @DisplayName("a commenter CAN place a comment anchor")
    void commenterCanAnchorComment() throws Exception {
        User ania = newUser("Ania");
        User critic = newUser("Critic");
        UUID document = privateDocument(ania);
        permissions.grant(document, critic.id(), Role.COMMENTER);

        try (TestStompClient owner = new TestStompClient(port, ania.token(), document, 803)) {
            owner.insert(0, "a sentence to discuss");
            owner.awaitCondition(() -> owner.lastAckSeq() > 0, PATIENCE, "write acknowledged");
        }

        UUID threadId = comments.openThread(document, critic.id(), "sentence", "I disagree.");

        try (TestStompClient commenter = new TestStompClient(port, critic.token(), document, 804)) {
            commenter.requestSync(0);
            commenter.awaitCondition(() -> commenter.text().length() == 21, PATIENCE, "sees the text");

            commenter.markReturningOp(0, 6, "comment", threadId.toString());

            commenter.awaitCondition(
                () -> documents.markCountOf(document) > 0, PATIENCE,
                "the comment anchor never arrived - a commenter cannot anchor a conversation");
        }
    }

    @Test
    @DisplayName("a commenter CANNOT anchor a thread from another document")
    void commenterCannotAnchorAThreadFromAnotherDocument() throws Exception {
        User ania = newUser("Ania");
        User critic = newUser("Critic");
        UUID document = privateDocument(ania);
        permissions.grant(document, critic.id(), Role.COMMENTER);

        try (TestStompClient owner = new TestStompClient(port, ania.token(), document, 813)) {
            owner.insert(0, "a sentence to discuss");
            owner.awaitCondition(() -> owner.lastAckSeq() > 0, PATIENCE, "write acknowledged");
        }

        UUID elsewhere = privateDocument(ania);
        UUID foreignThread = comments.openThread(elsewhere, ania.id(), "sentence", "Elsewhere.");

        try (TestStompClient commenter = new TestStompClient(port, critic.token(), document, 814)) {
            commenter.requestSync(0);
            commenter.awaitCondition(() -> commenter.text().length() == 21, PATIENCE, "sees the text");

            commenter.markReturningOp(0, 21, "comment", foreignThread.toString());

            commenter.requestSync(0);
            commenter.awaitCondition(() -> commenter.text().length() == 21, PATIENCE, "round trip");

            assertThat(documents.markCountOf(document))
                .as("a comment anchor naming a thread from another document was accepted, so a "
                    + "mark's value is trusted without being checked against the document the "
                    + "frame was authorized for")
                .isZero();
        }
    }

    @Test
    @DisplayName("the same comment sent twice is posted once")
    void retryingAnUncertainCommentDoesNotDuplicateIt() {
        User ania = newUser("Ania");
        UUID document = privateDocument(ania);

        String clientId = UUID.randomUUID().toString();
        UUID first = comments.openThread(document, ania.id(), "fragment", "Is this clear?",
            clientId);
        UUID second = comments.openThread(document, ania.id(), "fragment", "Is this clear?",
            clientId);

        assertThat(second)
            .as("the retry created a second conversation instead of recognising the first")
            .isEqualTo(first);
        assertThat(comments.threadsOf(document, true)).hasSize(1);
        assertThat(comments.threadsOf(document, true).getFirst().messages())
            .as("and the message inside it must not be doubled either")
            .hasSize(1);

        String replyKey = UUID.randomUUID().toString();
        comments.addMessage(first, ania.id(), "Yes.", replyKey);
        comments.addMessage(first, ania.id(), "Yes.", replyKey);
        assertThat(comments.threadsOf(document, true).getFirst().messages()).hasSize(2);
    }

    @Test
    @DisplayName("a comment with no key is still inserted every time")
    void anUnkeyedCommentKeepsTheOldBehaviour() {
        User ania = newUser("Ania");
        UUID document = privateDocument(ania);

        comments.openThread(document, ania.id(), "fragment", "Agreed");
        comments.openThread(document, ania.id(), "fragment", "Agreed");

        assertThat(comments.threadsOf(document, true)).hasSize(2);
    }

    @Test
    @DisplayName("a read-only role cannot comment")
    void viewerCannotComment() {
        User ania = newUser("Ania");
        User observer = newUser("Observer");
        UUID document = privateDocument(ania);
        permissions.grant(document, observer.id(), Role.VIEWER);

        assertThat(permissions.roleOf(observer.id(), document).filter(Role::canComment))
            .as("an observer can comment - a read-only role writes into the document")
            .isEmpty();
    }

    @Test
    @DisplayName("a thread survives deletion of its anchored text, with the quoted fragment")
    void threadSurvivesDeletionOfAnchoredText() throws Exception {
        User ania = newUser("Ania");
        UUID document = privateDocument(ania);

        try (TestStompClient owner = new TestStompClient(port, ania.token(), document, 805)) {
            owner.insert(0, "fragment to be deleted");
            owner.awaitCondition(() -> owner.lastAckSeq() > 0, PATIENCE, "write acknowledged");

            UUID threadId = comments.openThread(document, ania.id(), "fragment", "Does this stay?");
            assertThat(comments.findThread(threadId)).get()
                .satisfies(thread -> assertThat(thread.messages()).hasSize(1));

            owner.delete(0, 8);
            owner.awaitCondition(() -> owner.text().startsWith(" to"), PATIENCE, "text deleted");
            Thread.sleep(300);
        }

        var threads = comments.threadsOf(document, false);
        assertThat(threads)
            .as("deleting the anchored text removed the CONVERSATION")
            .hasSize(1);
        assertThat(threads.getFirst().quotedText())
            .as("a detached thread with no quoted fragment - there is no telling what it was about")
            .isEqualTo("fragment");
    }

    @Test
    @DisplayName("resolving a thread keeps the conversation")
    void resolvingKeepsTheConversation() {
        User ania = newUser("Ania");
        UUID document = privateDocument(ania);

        UUID threadId = comments.openThread(document, ania.id(), "quote", "First remark");
        comments.addMessage(threadId, ania.id(), "Second remark");

        assertThat(comments.resolve(threadId, ania.id())).isTrue();

        assertThat(comments.threadsOf(document, false))
            .as("a resolved thread is still on the default list")
            .isEmpty();

        var withResolved = comments.threadsOf(document, true);
        assertThat(withResolved).hasSize(1);
        assertThat(withResolved.getFirst().messages())
            .as("resolving the thread deleted the conversation instead of hiding it")
            .hasSize(2);

        assertThat(comments.reopen(threadId)).isTrue();
        assertThat(comments.threadsOf(document, false)).hasSize(1);
    }

    @Test
    @DisplayName("restoring an earlier version does not delete comments added later")
    void threadSurvivesDocumentRestore() throws Exception {
        User ania = newUser("Ania");
        UUID document = privateDocument(ania);

        try (TestStompClient owner = new TestStompClient(port, ania.token(), document, 806)) {
            owner.insert(0, "the first version");
            owner.awaitCondition(() -> owner.lastAckSeq() > 0, PATIENCE, "first write");
            long checkpoint = owner.lastAckSeq();

            owner.insert(17, " and an addendum");
            owner.awaitCondition(() -> owner.lastAckSeq() > checkpoint, PATIENCE, "second write");

            UUID threadId = comments.openThread(document, ania.id(), "addendum",
                "A remark on the addendum");
            assertThat(comments.findThread(threadId))
                .as("the conversation the restore below must not remove was never created")
                .isPresent();

            var restored = history.stateAt(document, checkpoint);
            documents.generateAndSubmit(document,
                model -> history.restoreOperations(model, restored), null);
            Thread.sleep(300);
        }

        var threads = comments.threadsOf(document, true);
        assertThat(threads)
            .as("restoring a version deleted a conversation added later")
            .hasSize(1);
        assertThat(threads.getFirst().messages().getFirst().body())
            .isEqualTo("A remark on the addendum");

        assertThat(threads.getFirst().quotedText())
            .as("the detached thread lost its quoted fragment")
            .isEqualTo("addendum");
    }

    @Test
    @DisplayName("a thread always has at least one message")
    void threadAlwaysHasAtLeastOneMessage() {
        User ania = newUser("Ania");
        UUID document = privateDocument(ania);

        comments.openThread(document, ania.id(), "quote", "The only message");

        assertThat(comments.threadsOf(document, true).getFirst().messages())
            .as("a thread with no messages is a state the interface cannot render")
            .isNotEmpty();
    }
}
