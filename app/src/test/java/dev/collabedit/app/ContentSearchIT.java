package dev.collabedit.app;

import static org.assertj.core.api.Assertions.assertThat;

import dev.collabedit.app.auth.TokenService;
import dev.collabedit.app.persist.DocumentCatalog;
import dev.collabedit.app.persist.SnapshotStore;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Searching what a document says (CP29).
 *
 * <p>Until CP29 the index covered the title and the first 200 characters, so search worked exactly
 * as far as the opening sentence. That is the kind of half-feature worse than none: it answers, it
 * looks like it searched, and the document containing the word is not in the results.
 *
 * <table>
 *   <caption>Test → failure mode</caption>
 *   <tr><th>Test</th><th>What it catches</th></tr>
 *   <tr><td>{@code aWordDeepInTheDocumentIsFound}</td>
 *       <td>The debt this checkpoint pays. The word is placed past the preview boundary on
 *           purpose.</td></tr>
 *   <tr><td>{@code aDocumentWithNoSnapshotIsStillFoundByTitle}</td>
 *       <td>The index lag turning into "a document created a second ago cannot be found at all" —
 *           which is exactly the document somebody is most likely to be looking for.</td></tr>
 *   <tr><td>{@code renamingIsVisibleToSearchImmediately}</td>
 *       <td>The same lag on the other edge: a rename does not rebuild the vector, so without the
 *           title fallback the new name would not be searchable until the next snapshot.</td></tr>
 * </table>
 */
class ContentSearchIT extends IntegrationTestBase {

    private static final Duration PATIENCE = Duration.ofSeconds(15);

    @LocalServerPort
    int port;

    @Autowired
    TokenService tokens;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    DocumentCatalog catalog;
    @Autowired
    SnapshotStore snapshots;

    /** Any signed-in user — these fixtures leave visibility at its default of `link`. */
    private static final String ANY_USER = "user-" + UUID.randomUUID();

    private UUID newDocument(String title) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO documents (id, title) VALUES (?, ?)", id, title);
        return id;
    }

    private String tokenFor(String name) {
        return tokens.issue(
            new TokenService.Principal(UUID.randomUUID().toString(), name, "#4C5FD5"));
    }

    private void awaitSnapshot(UUID document) throws InterruptedException {
        for (int attempt = 0; attempt < 300; attempt++) {
            if (snapshots.count(document) > 0) {
                Thread.sleep(100);
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("no snapshot was created for document " + document);
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("a word past the preview boundary is found")
    void aWordDeepInTheDocumentIsFound() throws Exception {
        UUID document = newDocument("Report");
        String needle = "needle" + System.nanoTime();

        // The preview is 200 characters. The needle sits well beyond it, which is where every
        // word of a real document sits.
        String filler = "filler ".repeat(40);
        try (TestStompClient author = new TestStompClient(port, tokenFor("Anna"), document, 961)) {
            author.insert(0, filler);
            author.awaitCondition(() -> author.lastAckSeq() >= 1, PATIENCE, "filler");
            author.insert(filler.length(), needle);
            author.awaitCondition(() -> author.lastAckSeq() >= 2, PATIENCE, "needle");
        }
        awaitSnapshot(document);

        assertThat(catalog.search(ANY_USER, null, needle, 20))
            .as("the word is in the document and the search does not find it — which is worse "
                + "than not having search, because the answer looks like an answer")
            .extracting(DocumentCatalog.DocumentSummary::id)
            .contains(document);
    }

    @Test
    @DisplayName("a document nobody has snapshotted yet is still findable by title")
    void aDocumentWithNoSnapshotIsStillFoundByTitle() {
        String title = "Brand" + System.nanoTime();
        UUID document = newDocument(title);

        // No operations, so no snapshot, so no search vector. This is the state of every document
        // in its first seconds — and the one somebody is most likely to be looking for.
        assertThat(catalog.search(ANY_USER, null, title, 20))
            .extracting(DocumentCatalog.DocumentSummary::id)
            .contains(document);
    }

    @Test
    @DisplayName("a document too large for a tsvector is still indexed, up to the bound")
    void aVeryLargeDocumentDoesNotBreakTheSnapshotPath() {
        UUID document = newDocument("Huge");
        String needle = "needle" + System.nanoTime();

        // Postgres refuses a string of about a megabyte with "string is too long for tsvector",
        // and this call sits on the SNAPSHOT path. The failure would not lose data — the snapshot
        // is written first — and would do something worse: `snapshotTaken()` never runs, so the
        // sweep re-snapshots the same document every second, forever.
        // DISTINCT words, not one word repeated: the limit is on the resulting tsvector, and a
        // repeated word compresses to a single lexeme with positions. Prose has hundreds of
        // thousands of distinct forms in a document this size, and that is what overflows.
        StringBuilder builder = new StringBuilder(needle).append(' ');
        for (int i = 0; i < 200_000; i++) {
            builder.append("wyraz").append(i).append(' ');
        }
        String huge = builder.toString();
        assertThat(huge.length()).isGreaterThan(1_000_000);

        catalog.updatePreview(document, huge);

        assertThat(catalog.search(ANY_USER, null, needle, 20))
            .extracting(DocumentCatalog.DocumentSummary::id)
            .contains(document);
    }

    @Test
    @DisplayName("a renamed document is searchable under its new name at once")
    void renamingIsVisibleToSearchImmediately() {
        UUID document = newDocument("Before" + System.nanoTime());
        String after = "After" + System.nanoTime();

        catalog.rename(document, after);

        assertThat(catalog.search(ANY_USER, null, after, 20))
            .as("renaming does not rebuild the vector, so without the title fallback the new "
                + "name would be invisible to search until the next snapshot — which for a "
                + "document nobody is typing in is never")
            .extracting(DocumentCatalog.DocumentSummary::id)
            .contains(document);
    }
}
