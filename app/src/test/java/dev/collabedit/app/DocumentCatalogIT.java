package dev.collabedit.app;

import static org.assertj.core.api.Assertions.assertThat;

import dev.collabedit.app.auth.TokenService;
import dev.collabedit.app.persist.DocumentCatalog;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The document catalog (CP11) — listing, search, soft delete, preview.
 *
 * <table>
 *   <caption>Test -> failure mode</caption>
 *   <tr><th>Test</th><th>What it catches</th></tr>
 *   <tr><td>{@code softDeleteKeepsOperationLog}</td>
 *       <td><b>Irreversible destruction of the source of truth.</b> {@code operations} has
 *           {@code ON DELETE CASCADE} on {@code doc_id}, so one {@code DELETE} on a document
 *           erases its entire history — a table that per the operation-log decision is append-only. It cannot be
 *           undone and is invisible until somebody asks for a restore.</td></tr>
 *   <tr><td>{@code deletedDocumentDisappearsFromList}</td>
 *       <td>A delete that hides nothing. Without this, "delete" does nothing visible.</td></tr>
 *   <tr><td>{@code restoreBringsDocumentBack}</td>
 *       <td>Soft delete with no way back — which is hard delete with the extra cost of database
 *           space and none of its benefits.</td></tr>
 *   <tr><td>{@code previewIsMaterialisedAtSnapshot}</td>
 *       <td>Without a preview, the document list would have to load a CRDT replica per entry.
 *           Correctness is intact; the cost of opening the list grows with the number of documents
 *           times their size.</td></tr>
 *   <tr><td>{@code searchFindsByTitleAndContent}</td>
 *       <td>A full-text index that does not cover content — a search that finds only what the user
 *           named themselves.</td></tr>
 * </table>
 */
class DocumentCatalogIT extends IntegrationTestBase {

    private static final java.time.Duration PATIENCE = java.time.Duration.ofSeconds(10);

    @LocalServerPort
    int port;

    @Autowired
    DocumentCatalog catalog;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TokenService tokens;

    /**
     * Any signed-in user.
     *
     * <p>The listings are scoped to the caller since the LIMIT was moved behind the access
     * rule. These fixtures create documents through {@code catalog.create}, which leaves
     * {@code visibility} at its default of {@code link} — readable by anybody signed in — so
     * the identity here does not select anything, it only has to exist.
     */
    private static final String ANY_USER = "user-" + UUID.randomUUID();

    private UUID newDocument(String title) {
        UUID id = UUID.randomUUID();
        catalog.create(id, title, null);
        return id;
    }

    private int operationRows(UUID documentId) {
        Integer rows = jdbc.queryForObject(
            "SELECT COUNT(*) FROM operations WHERE doc_id = ?", Integer.class, documentId);
        return rows == null ? 0 : rows;
    }

    @Test
    @DisplayName("a soft delete does NOT touch the operation log")
    void softDeleteKeepsOperationLog() throws Exception {
        UUID document = newDocument("To be deleted");

        try (TestStompClient anna = new TestStompClient(
            port, tokens.issue(new TokenService.Principal(
                UUID.randomUUID().toString(), "Anna", "#C1592E")), document, 901)) {
            anna.insert(0, "content that must not be lost");
            anna.awaitCondition(() -> anna.lastAckSeq() > 0, PATIENCE, "write acknowledged");
        }

        int before = operationRows(document);
        assertThat(before).isPositive();

        assertThat(catalog.softDelete(document)).isTrue();

        assertThat(operationRows(document))
            .as("deleting the document erased its operation log — that is irreversible")
            .isEqualTo(before);
    }

    @Test
    @DisplayName("a deleted document disappears from the list and appears in the bin")
    void deletedDocumentDisappearsFromList() {
        UUID document = newDocument("Vanishing");
        assertThat(catalog.list(ANY_USER, null, 500)).extracting(DocumentCatalog.DocumentSummary::id)
            .contains(document);

        catalog.softDelete(document);

        assertThat(catalog.list(ANY_USER, null, 500)).extracting(DocumentCatalog.DocumentSummary::id)
            .doesNotContain(document);
        assertThat(catalog.listDeleted(ANY_USER, 500)).extracting(DocumentCatalog.DocumentSummary::id)
            .contains(document);
        assertThat(catalog.find(document)).isEmpty();
    }

    @Test
    @DisplayName("restoring brings the document back to the list")
    void restoreBringsDocumentBack() {
        UUID document = newDocument("Returning");
        catalog.softDelete(document);

        assertThat(catalog.restore(document)).isTrue();
        assertThat(catalog.find(document)).isPresent();
        // A repeated restore has nothing to restore, and says so plainly.
        assertThat(catalog.restore(document)).isFalse();
    }

    @Test
    @DisplayName("a repeated delete does not move the deletion date")
    void repeatedDeleteIsIdempotent() {
        UUID document = newDocument("Twice");

        assertThat(catalog.softDelete(document)).isTrue();
        assertThat(catalog.softDelete(document))
            .as("the second delete reported success — meaning it moved the date to today")
            .isFalse();
    }

    @Test
    @DisplayName("the content preview is materialised at snapshot time, so the list loads no replica")
    void previewIsMaterialisedAtSnapshot() throws Exception {
        UUID document = newDocument("With a preview");

        try (TestStompClient anna = new TestStompClient(
            port, tokens.issue(new TokenService.Principal(
                UUID.randomUUID().toString(), "Anna", "#C1592E")), document, 902)) {
            // The snapshot threshold in tests is 20 operations.
            for (int i = 0; i < 25; i++) {
                anna.insert(i, "x");
            }
            anna.awaitCondition(() -> anna.lastAckSeq() >= 25, PATIENCE, "write acknowledged");
        }

        for (int attempt = 0; attempt < 200; attempt++) {
            if (!catalog.find(document).orElseThrow().preview().isEmpty()) {
                break;
            }
            Thread.sleep(20);
        }

        assertThat(catalog.find(document).orElseThrow().preview())
            .as("the preview was not created — the document list would have to load CRDT replicas")
            .isEqualTo("x".repeat(25));
    }

    @Test
    @DisplayName("search finds by title and by content")
    void searchFindsByTitleAndContent() throws Exception {
        String marker = "quantum" + System.nanoTime();
        UUID byTitle = newDocument("Report " + marker);

        assertThat(catalog.search(ANY_USER, null, marker, 50))
            .as("search does not find by title")
            .extracting(DocumentCatalog.DocumentSummary::id)
            .contains(byTitle);

        // Content enters the index through preview_text, so only after a snapshot.
        UUID byContent = newDocument("No marker in the title");
        String contentMarker = "photonic" + System.nanoTime();
        try (TestStompClient anna = new TestStompClient(
            port, tokens.issue(new TokenService.Principal(
                UUID.randomUUID().toString(), "Anna", "#C1592E")), byContent, 903)) {
            for (int i = 0; i < 25; i++) {
                anna.insert(anna.text().length(), i == 0 ? contentMarker : " ");
            }
            anna.awaitCondition(() -> anna.lastAckSeq() >= 25, PATIENCE, "write acknowledged");
        }

        boolean found = false;
        for (int attempt = 0; attempt < 200 && !found; attempt++) {
            found = catalog.search(ANY_USER, null, contentMarker, 50).stream()
                .anyMatch(d -> d.id().equals(byContent));
            if (!found) {
                Thread.sleep(20);
            }
        }
        assertThat(found)
            .as("search does not cover content — it finds only what the user named themselves")
            .isTrue();
    }
}
