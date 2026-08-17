package dev.collabedit.app.persist;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class DocumentCatalog {
    private final JdbcTemplate jdbc;

    public DocumentCatalog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

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

    private static final String READABLE_BY_CALLER =
        "(d.owner_id = ? "
            + " OR EXISTS (SELECT 1 FROM document_members dm "
            + " WHERE dm.doc_id = d.id AND dm.user_id = ?) "
            + " OR EXISTS (SELECT 1 FROM workspace_members wm "
            + " WHERE wm.workspace_id = d.workspace_id AND wm.user_id = ?)) ";

    private static final RowMapper<DocumentSummary> SUMMARY =
        (rs, rowNum) -> new DocumentSummary(
            UUID.fromString(rs.getString("id")),
            rs.getString("title"),
            rs.getString("workspace_id") == null ? null : UUID.fromString(rs.getString("workspace_id")),
            rs.getString("preview_text"),
            rs.getTimestamp("updated_at").toInstant());

    public List<DocumentSummary> list(String callerId, UUID workspaceId, int limit) {
        if (workspaceId == null) {
            return jdbc.query("SELECT " + SUMMARY_COLUMNS + " FROM documents d "
                + "WHERE d.deleted_at IS NULL AND " + READABLE_BY_CALLER
                + "ORDER BY d.updated_at DESC LIMIT ?",
                SUMMARY, callerId, callerId, callerId, limit);
        }
        return jdbc.query("SELECT " + SUMMARY_COLUMNS + " FROM documents d "
            + "WHERE d.deleted_at IS NULL AND d.workspace_id = ? AND " + READABLE_BY_CALLER
            + "ORDER BY d.updated_at DESC LIMIT ?",
            SUMMARY, workspaceId, callerId, callerId, callerId, limit);
    }

    public List<DocumentSummary> listDeleted(String callerId, int limit) {
        return jdbc.query("SELECT " + SUMMARY_COLUMNS + " FROM documents d "
            + "WHERE d.deleted_at IS NOT NULL AND " + READABLE_BY_CALLER
            + "ORDER BY d.deleted_at DESC LIMIT ?",
            SUMMARY, callerId, callerId, callerId, limit);
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

    public List<DocumentSummary> search(String callerId, UUID workspaceId, String query, int limit) {
        return jdbc.query(
            "SELECT " + SUMMARY_COLUMNS + " FROM documents d "
                + "WHERE d.deleted_at IS NULL "
                + "AND (d.search_vector @@ websearch_to_tsquery('simple', ?) "
                + " OR to_tsvector('simple', d.title) @@ websearch_to_tsquery('simple', ?)) "
                + "AND (CAST(? AS uuid) IS NULL OR d.workspace_id = CAST(? AS uuid)) "
                + "AND " + READABLE_BY_CALLER
                + "ORDER BY d.updated_at DESC LIMIT ?",
            SUMMARY, query, query, workspaceId, workspaceId, callerId, callerId, callerId, limit);
    }

    public void create(UUID documentId, String title, UUID workspaceId) {
        jdbc.update(
            "INSERT INTO documents (id, title, workspace_id, visibility) VALUES (?, ?, ?, 'private')",
            documentId, title, workspaceId);
    }

    public boolean rename(UUID documentId, String title) {
        return jdbc.update("UPDATE documents SET title = ?, updated_at = now() "
            + "WHERE id = ? AND deleted_at IS NULL", title, documentId) == 1;
    }

    public boolean softDelete(UUID documentId) {
        return jdbc.update("UPDATE documents SET deleted_at = now() "
            + "WHERE id = ? AND deleted_at IS NULL", documentId) == 1;
    }

    public boolean restore(UUID documentId) {
        return jdbc.update("UPDATE documents SET deleted_at = NULL, updated_at = now() "
            + "WHERE id = ? AND deleted_at IS NOT NULL", documentId) == 1;
    }

    public void updatePreview(UUID documentId, String text) {
        String preview = text.length() <= PREVIEW_LENGTH ? text : text.substring(0, PREVIEW_LENGTH);
        jdbc.update("UPDATE documents SET preview_text = ?, "
                + "search_vector = to_tsvector('simple', title || ' ' || ?) WHERE id = ?",
            preview, indexable(text), documentId);
    }

    public static final int PREVIEW_LENGTH = 200;

    private static final int INDEXABLE_LENGTH = 250_000;

    private static String indexable(String text) {
        return text.length() <= INDEXABLE_LENGTH ? text : text.substring(0, INDEXABLE_LENGTH);
    }

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
