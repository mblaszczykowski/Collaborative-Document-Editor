package dev.collabedit.app.persist;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Document metadata: title, workspace membership, deletion state, content preview.
 *
 * <h2>What does NOT go here</h2>
 * The document's content. That lives in the operation log ({@link OperationStore}) and in the
 * in-memory replicas — this catalog knows only what is needed to <b>render a list</b>. The split is
 * deliberate: listing a hundred documents should be one query rather than a hundred loaded CRDT
 * replicas. The {@code preview_text} column exists for exactly that and is a cache on the same
 * terms as snapshots (ADR-0003) — it may be discarded at any moment.
 *
 * <h2>Why deletion is soft</h2>
 * {@code operations} has {@code ON DELETE CASCADE} on {@code doc_id}. A hard delete of a document
 * would therefore erase its operation log, that is, the source of truth — a table that in this
 * system is append-only. "Delete" in the interface means "hide", and that is all the user clicking
 * it wants.
 */
@Repository
public class DocumentCatalog {

    private final JdbcTemplate jdbc;

    public DocumentCatalog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param preview the first characters of the content; empty until the first snapshot exists
     */
    public record DocumentSummary(
        UUID id,
        String title,
        UUID workspaceId,
        String preview,
        Instant updatedAt
    ) {
    }

    public record Workspace(UUID id, String name, String ownerId, Instant createdAt) {
    }

    private static final String SUMMARY_COLUMNS =
        "id, title, workspace_id, preview_text, updated_at";

    private static final org.springframework.jdbc.core.RowMapper<DocumentSummary> SUMMARY =
        (rs, rowNum) -> new DocumentSummary(
            UUID.fromString(rs.getString("id")),
            rs.getString("title"),
            rs.getString("workspace_id") == null ? null : UUID.fromString(rs.getString("workspace_id")),
            rs.getString("preview_text"),
            rs.getTimestamp("updated_at").toInstant());

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    /** The visible documents — that is, the ones not deleted. Most recently changed first. */
    public List<DocumentSummary> list(UUID workspaceId, int limit) {
        if (workspaceId == null) {
            return jdbc.query("SELECT " + SUMMARY_COLUMNS + " FROM documents "
                + "WHERE deleted_at IS NULL ORDER BY updated_at DESC LIMIT ?", SUMMARY, limit);
        }
        return jdbc.query("SELECT " + SUMMARY_COLUMNS + " FROM documents "
            + "WHERE deleted_at IS NULL AND workspace_id = ? ORDER BY updated_at DESC LIMIT ?",
            SUMMARY, workspaceId, limit);
    }

    /** The bin — soft-deleted documents, available to restore. */
    public List<DocumentSummary> listDeleted(int limit) {
        return jdbc.query("SELECT " + SUMMARY_COLUMNS + " FROM documents "
            + "WHERE deleted_at IS NOT NULL ORDER BY deleted_at DESC LIMIT ?", SUMMARY, limit);
    }

    public Optional<DocumentSummary> find(UUID documentId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                "SELECT " + SUMMARY_COLUMNS + " FROM documents WHERE id = ? AND deleted_at IS NULL",
                SUMMARY, documentId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * Full-text search over the title and the content preview.
     *
     * <p>The {@code simple} configuration rather than a language-specific one — the latter needs
     * ispell dictionaries that the stock Postgres image does not carry, so the migration would fail
     * at startup. No stemming means "documents" will not be found by searching for "document". A
     * known limitation, listed in the README.
     *
     * <p>{@code websearch_to_tsquery} rather than {@code plainto_tsquery}: it accepts what people
     * actually type into a search box (quotes, {@code or}, {@code -}) and does not blow up on the
     * syntax, interpreting it as best it can instead.
     */
    public List<DocumentSummary> search(String query, int limit) {
        return jdbc.query(
            "SELECT " + SUMMARY_COLUMNS + " FROM documents "
                + "WHERE deleted_at IS NULL "
                + "AND to_tsvector('simple', title || ' ' || preview_text) "
                + "    @@ websearch_to_tsquery('simple', ?) "
                + "ORDER BY updated_at DESC LIMIT ?",
            SUMMARY, query, limit);
    }

    // ------------------------------------------------------------------
    // Writes
    // ------------------------------------------------------------------

    public void create(UUID documentId, String title, UUID workspaceId) {
        jdbc.update("INSERT INTO documents (id, title, workspace_id) VALUES (?, ?, ?)",
            documentId, title, workspaceId);
    }

    /** @return {@code true} when the document existed and was not deleted */
    public boolean rename(UUID documentId, String title) {
        return jdbc.update("UPDATE documents SET title = ?, updated_at = now() "
            + "WHERE id = ? AND deleted_at IS NULL", title, documentId) == 1;
    }

    /**
     * Hides a document. The operation log is left untouched — see the class comment.
     *
     * <p>The {@code deleted_at IS NULL} condition means a repeated delete does not move the date:
     * "deleted yesterday" should mean yesterday, even if someone clicks again.
     */
    public boolean softDelete(UUID documentId) {
        return jdbc.update("UPDATE documents SET deleted_at = now() "
            + "WHERE id = ? AND deleted_at IS NULL", documentId) == 1;
    }

    public boolean restore(UUID documentId) {
        return jdbc.update("UPDATE documents SET deleted_at = NULL, updated_at = now() "
            + "WHERE id = ? AND deleted_at IS NOT NULL", documentId) == 1;
    }

    /**
     * Refreshes the content preview. Called while snapshotting, because we have the state in hand
     * anyway at that point.
     *
     * <p>The preview is truncated to {@link #PREVIEW_LENGTH} characters — the document list shows
     * one line, and keeping the whole document in a column next to the document would be
     * duplicating the content rather than caching a fragment of it.
     */
    public void updatePreview(UUID documentId, String text) {
        String preview = text.length() <= PREVIEW_LENGTH ? text : text.substring(0, PREVIEW_LENGTH);
        jdbc.update("UPDATE documents SET preview_text = ? WHERE id = ?", preview, documentId);
    }

    public static final int PREVIEW_LENGTH = 200;

    // ------------------------------------------------------------------
    // Workspaces
    // ------------------------------------------------------------------

    public void createWorkspace(UUID id, String name, String ownerId) {
        jdbc.update("INSERT INTO workspaces (id, name, owner_id) VALUES (?, ?, ?)",
            id, name, ownerId);
    }

    public List<Workspace> workspacesOf(String ownerId) {
        return jdbc.query("SELECT id, name, owner_id, created_at FROM workspaces "
                + "WHERE owner_id = ? ORDER BY created_at",
            (rs, rowNum) -> new Workspace(
                UUID.fromString(rs.getString("id")),
                rs.getString("name"),
                rs.getString("owner_id"),
                rs.getTimestamp("created_at").toInstant()),
            ownerId);
    }
}
