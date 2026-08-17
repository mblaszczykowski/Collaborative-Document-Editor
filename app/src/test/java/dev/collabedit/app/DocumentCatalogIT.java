package dev.collabedit.app;

import static org.assertj.core.api.Assertions.assertThat;

import dev.collabedit.app.auth.PermissionService;
import dev.collabedit.app.auth.TokenService;
import dev.collabedit.app.persist.DocumentCatalog;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

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
    @Autowired
    PermissionService permissions;

    private static final String OWNER = "user-" + UUID.randomUUID();

    private UUID newDocument(String title) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, display_name, color) VALUES (?, ?, ?) "
            + "ON CONFLICT (id) DO NOTHING", OWNER, "Owner", "#4C5FD5");
        catalog.create(id, title, null);
        permissions.setOwner(id, OWNER);
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
                OWNER, "Anna", "#C1592E")), document, 901)) {
            anna.insert(0, "content that must not be lost");
            anna.awaitCondition(() -> anna.lastAckSeq() > 0, PATIENCE, "write acknowledged");
        }

        int before = operationRows(document);
        assertThat(before).isPositive();

        assertThat(catalog.softDelete(document)).isTrue();

        assertThat(operationRows(document))
            .as("deleting the document erased its operation log - that is irreversible")
            .isEqualTo(before);
    }

    @Test
    @DisplayName("a deleted document disappears from the list and appears in the bin")
    void deletedDocumentDisappearsFromList() {
        UUID document = newDocument("Vanishing");
        assertThat(catalog.list(OWNER, null, 500)).extracting(DocumentCatalog.DocumentSummary::id)
            .contains(document);

        catalog.softDelete(document);

        assertThat(catalog.list(OWNER, null, 500)).extracting(DocumentCatalog.DocumentSummary::id)
            .doesNotContain(document);
        assertThat(catalog.listDeleted(OWNER, 500)).extracting(DocumentCatalog.DocumentSummary::id)
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
        assertThat(catalog.restore(document)).isFalse();
    }

    @Test
    @DisplayName("a repeated delete does not move the deletion date")
    void repeatedDeleteIsIdempotent() {
        UUID document = newDocument("Twice");

        assertThat(catalog.softDelete(document)).isTrue();
        assertThat(catalog.softDelete(document))
            .as("the second delete reported success - meaning it moved the date to today")
            .isFalse();
    }

    @Test
    @DisplayName("the content preview is materialised at snapshot time, so the list loads no replica")
    void previewIsMaterialisedAtSnapshot() throws Exception {
        UUID document = newDocument("With a preview");

        try (TestStompClient anna = new TestStompClient(
            port, tokens.issue(new TokenService.Principal(
                OWNER, "Anna", "#C1592E")), document, 902)) {
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
            .as("the preview was not created - the document list would have to load CRDT replicas")
            .isEqualTo("x".repeat(25));
    }

    @Test
    @DisplayName("search finds by title and by content")
    void searchFindsByTitleAndContent() throws Exception {
        String marker = "quantum" + System.nanoTime();
        UUID byTitle = newDocument("Report " + marker);

        assertThat(catalog.search(OWNER, null, marker, 50))
            .as("search does not find by title")
            .extracting(DocumentCatalog.DocumentSummary::id)
            .contains(byTitle);

        UUID byContent = newDocument("No marker in the title");
        String contentMarker = "photonic" + System.nanoTime();
        try (TestStompClient anna = new TestStompClient(
            port, tokens.issue(new TokenService.Principal(
                OWNER, "Anna", "#C1592E")), byContent, 903)) {
            for (int i = 0; i < 25; i++) {
                anna.insert(anna.text().length(), i == 0 ? contentMarker : " ");
            }
            anna.awaitCondition(() -> anna.lastAckSeq() >= 25, PATIENCE, "write acknowledged");
        }

        boolean found = false;
        for (int attempt = 0; attempt < 200 && !found; attempt++) {
            found = catalog.search(OWNER, null, contentMarker, 50).stream()
                .anyMatch(d -> d.id().equals(byContent));
            if (!found) {
                Thread.sleep(20);
            }
        }
        assertThat(found)
            .as("search does not cover content - it finds only what the user named themselves")
            .isTrue();
    }
}
