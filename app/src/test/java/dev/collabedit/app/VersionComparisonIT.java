package dev.collabedit.app;

import static org.assertj.core.api.Assertions.assertThat;

import dev.collabedit.app.auth.TokenService;
import dev.collabedit.app.domain.DocumentRegistry;
import dev.collabedit.app.domain.DocumentService;
import dev.collabedit.app.history.HistoryService;
import dev.collabedit.crdt.MarkSet;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Comparing and restoring versions of a document that is no longer plain text (CP22).
 *
 * <p>The history layer was written in CP5, when a document was a string, and was not revisited
 * when CP13 gave it formatting and CP14 gave it conversations. Nothing failed: the code kept doing
 * exactly what it said, on a smaller object than the one it was pointed at.
 *
 * <table>
 *   <caption>Test → failure mode</caption>
 *   <tr><th>Test</th><th>What it catches</th></tr>
 *   <tr><td>{@code restoringBringsFormattingBack}</td>
 *       <td>A restore that returns the text and leaves the appearance as it happens to be — so
 *           "restore this version" produces a state that never existed.</td></tr>
 *   <tr><td>{@code restoringLeavesConversationsAlone}</td>
 *       <td>The opposite mistake, made while fixing the first: reconciling comment anchors too,
 *           which would silently detach every conversation started after the target version.
 *           CP14 already ruled that a text change must not delete a conversation.</td></tr>
 *   <tr><td>{@code retypingTheSameWordIsAChange}</td>
 *       <td>The identity diff quietly degenerating into a text diff. If it ever starts comparing
 *           characters instead of identifiers, this is the case that says so — and it is the one
 *           case that makes the whole approach worth having.</td></tr>
 *   <tr><td>{@code segmentsReconstructBothVersions}</td>
 *       <td>A diff that looks plausible and drops or duplicates text. The invariant is exact:
 *           same+removed is the earlier version, same+added is the later one.</td></tr>
 * </table>
 */
class VersionComparisonIT extends IntegrationTestBase {

    private static final Duration PATIENCE = Duration.ofSeconds(10);

    @LocalServerPort
    int port;

    @Autowired
    TokenService tokens;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    HistoryService history;
    @Autowired
    DocumentService documents;
    @Autowired
    DocumentRegistry registry;

    private UUID newDocument(String title) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO documents (id, title) VALUES (?, ?)", id, title);
        return id;
    }

    private String tokenFor(String name) {
        return tokens.issue(
            new TokenService.Principal(UUID.randomUUID().toString(), name, "#4C5FD5"));
    }

    /** Applies a restore the way {@code HistoryController} does. */
    private int restoreTo(UUID document, long throughServerSeq) throws Exception {
        var target = history.stateAt(document, throughServerSeq);
        var replica = registry.get(document);
        var generated = replica.withLock(model -> history.restoreOperations(model, target));
        for (var op : generated) {
            documents.submitAlreadyMerged(document, op, null);
        }
        Thread.sleep(300);
        return generated.size();
    }

    private List<MarkSet.Span> liveFormatting(UUID document) {
        return registry.get(document).withLock(model -> model.formatting());
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("restoring a version brings its formatting back, not only its text")
    void restoringBringsFormattingBack() throws Exception {
        UUID document = newDocument("Formatted history");

        long formatted;
        try (TestStompClient anna = new TestStompClient(port, tokenFor("Anna"), document, 771)) {
            anna.insert(0, "The cat sat");
            anna.awaitCondition(() -> anna.lastAckSeq() >= 1, PATIENCE, "text");

            anna.markReturningOp(0, 3, "bold", null);
            anna.awaitCondition(() -> anna.lastAckSeq() >= 2, PATIENCE, "bold");
            formatted = anna.lastAckSeq();

            anna.markReturningOp(0, 3, "bold", null);
            anna.markReturningOp(0, 3, "italic", null);
            anna.awaitCondition(() -> anna.lastAckSeq() >= 4, PATIENCE, "changed formatting");
        }

        // The state to go back to is bold, no italics. Before CP22 this call returned a String,
        // so both of those facts were outside what a restore could even express.
        var target = history.stateAt(document, formatted);
        assertThat(target.marks())
            .as("the past state must carry its marks, or there is nothing to restore them from")
            .isNotEmpty();

        restoreTo(document, formatted);

        var live = liveFormatting(document);
        assertThat(live).isNotEmpty();
        assertThat(live.getFirst().marks())
            .as("bolding was on at the target version and must come back")
            .containsKey("bold");
        assertThat(live.stream().anyMatch(span -> span.marks().containsKey("italic")))
            .as("italics were added AFTER the target version, so restoring must remove them")
            .isFalse();
        assertThat(documents.textOf(document)).isEqualTo("The cat sat");
    }

    @Test
    @DisplayName("restoring does not detach conversations started since")
    void restoringLeavesConversationsAlone() throws Exception {
        UUID document = newDocument("History with comments");

        long beforeTheComment;
        try (TestStompClient anna = new TestStompClient(port, tokenFor("Anna"), document, 772)) {
            anna.insert(0, "The cat sat");
            anna.awaitCondition(() -> anna.lastAckSeq() >= 1, PATIENCE, "text");
            beforeTheComment = anna.lastAckSeq();

            anna.markReturningOp(7, 11, "comment", "thread-1");
            anna.awaitCondition(() -> anna.lastAckSeq() >= 2, PATIENCE, "comment anchor");

            anna.insert(11, " on the mat");
            anna.awaitCondition(() -> anna.lastAckSeq() >= 3, PATIENCE, "more text");
        }

        restoreTo(document, beforeTheComment);

        assertThat(documents.textOf(document))
            .as("the text goes back")
            .isEqualTo("The cat sat");
        assertThat(liveFormatting(document).stream()
            .anyMatch(span -> span.marks().containsKey("comment")))
            .as("the conversation was started after the target version, and a rollback of the "
                + "TEXT must not be a way of deleting it — CP14 settled that for deletion, and "
                + "restoring is the same question asked by a different button")
            .isTrue();
    }

    @Test
    @DisplayName("deleting a word and retyping it identically is a change, and a text diff cannot see it")
    void retypingTheSameWordIsAChange() throws Exception {
        UUID document = newDocument("Retyped");

        long before;
        try (TestStompClient anna = new TestStompClient(port, tokenFor("Anna"), document, 773)) {
            anna.insert(0, "The cat sat");
            anna.awaitCondition(() -> anna.lastAckSeq() >= 1, PATIENCE, "text");
            before = anna.lastAckSeq();

            anna.delete(7, 4);
            anna.awaitCondition(() -> anna.lastAckSeq() >= 2, PATIENCE, "deletion");
            anna.insert(7, " sat");
            anna.awaitCondition(() -> anna.lastAckSeq() >= 3, PATIENCE, "retyped");
        }
        long after = history.timeline(document).getFirst().throughServerSeq();

        assertThat(history.textAt(document, before))
            .as("the premise of this test: the two versions are the same STRING")
            .isEqualTo(history.textAt(document, after));

        var segments = history.diff(document, before, after);
        assertThat(segments.stream().anyMatch(s -> s.kind().equals("removed") && s.text().equals(" sat")))
            .as("the old characters are gone — a text diff reports nothing here, because it has "
                + "nothing to compare but the letters")
            .isTrue();
        assertThat(segments.stream().anyMatch(s -> s.kind().equals("added") && s.text().equals(" sat")))
            .as("and different characters, with different identifiers, took their place")
            .isTrue();
    }

    @Test
    @DisplayName("the segments reconstruct both versions exactly")
    void segmentsReconstructBothVersions() throws Exception {
        UUID document = newDocument("Diff invariant");

        long before;
        try (TestStompClient anna = new TestStompClient(port, tokenFor("Anna"), document, 774)) {
            anna.insert(0, "The first sentence of the document.");
            anna.awaitCondition(() -> anna.lastAckSeq() >= 1, PATIENCE, "text");
            before = anna.lastAckSeq();

            anna.delete(0, 9);
            anna.awaitCondition(() -> anna.lastAckSeq() >= 2, PATIENCE, "deletion");
            anna.insert(0, "Second ");
            anna.awaitCondition(() -> anna.lastAckSeq() >= 3, PATIENCE, "insertion");
            anna.insert(anna.text().length(), " And one more.");
            anna.awaitCondition(() -> anna.lastAckSeq() >= 4, PATIENCE, "insertion at the end");
        }
        long after = history.timeline(document).getFirst().throughServerSeq();

        var segments = history.diff(document, before, after);

        StringBuilder earlier = new StringBuilder();
        StringBuilder later = new StringBuilder();
        for (var segment : segments) {
            if (!segment.kind().equals("added")) {
                earlier.append(segment.text());
            }
            if (!segment.kind().equals("removed")) {
                later.append(segment.text());
            }
        }

        assertThat(earlier.toString()).isEqualTo(history.textAt(document, before));
        assertThat(later.toString()).isEqualTo(history.textAt(document, after));
    }

    @Test
    @DisplayName("comparing a version with itself finds nothing")
    void identicalVersionsProduceNoChange() throws Exception {
        UUID document = newDocument("No change");

        long seq;
        try (TestStompClient anna = new TestStompClient(port, tokenFor("Anna"), document, 775)) {
            anna.insert(0, "No change");
            anna.awaitCondition(() -> anna.lastAckSeq() >= 1, PATIENCE, "text");
            seq = anna.lastAckSeq();
        }

        var segments = history.diff(document, seq, seq);
        assertThat(segments).hasSize(1);
        assertThat(segments.getFirst().kind()).isEqualTo("same");
        assertThat(segments.getFirst().text()).isEqualTo("No change");
    }
}
