package dev.collabedit.app.persist;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Document metadata: title, workspace membership, deletion state, content preview.
 *
 * <h2>What does NOT go here</h2>
 * The document's content. That lives in the operation log ({@link OperationStore}) and in the
 * in-memory replicas — this catalog knows only what is needed to <b>render a list</b>. The split is
 * deliberate: listing a hundred documents should be one query rather than a hundred loaded CRDT
 * replicas. The {@code preview_text} column exists for exactly that and is a cache on the same
 * terms as snapshots — it may be discarded at any moment.
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

    /**
     * Whether the caller can read the document — as SQL, so that a {@code LIMIT} applies to rows
     * they can actually see.
     *
     * <h2>Why the rule is here as well as in PermissionService</h2>
     * Every listing used to be "the N most recently updated documents ON THE INSTANCE", filtered
     * for permission afterwards in Java. On an instance holding more than {@code LIST_LIMIT}
     * recently updated documents, a user whose own documents were not in that global slice got an
     * EMPTY list — and empty search results for a word that is in their own document. Silently, on
     * the first screen of the product, and worse the busier the instance gets.
     *
     * <p>So the predicate has to be in the query. It duplicates part of the resolution order in
     * {@code PermissionService.roleFrom}, and the duplication is pinned rather than trusted:
     * {@code DocumentCatalogPermissionParityIT} generates ownership, membership and visibility
     * combinations and fails if this ever admits something that service would refuse.
     *
     * <p>The Java filter in {@code DocumentRestController} stays as well. This narrows the
     * candidate set; that one remains the authority. So a mistake here can only ever show too
     * FEW documents, never somebody else's.
     *
     * <h2>Why {@code visibility = 'link'} is NOT in here, though it grants access</h2>
     * <b>Listing and access are two different questions, and this predicate answers only the
     * first.</b> It used to answer both, and that was the single line that made the product look
     * like it had no permissions at all: every logged-in person's sidebar listed every
     * link-visible document in the database, because "may I open this" was being used as "does
     * this belong on your screen".
     *
     * <p>Which defeats the point of the mode. {@code link} means <i>anyone with the link</i> —
     * possession of an unguessable URL is the credential. Handing that URL to everybody who logs
     * in, in a list, gives it away. The whole mechanism only means something if the link is the
     * thing you have to have.
     *
     * <p>So a link-visible document is still fully readable and editable by anyone who holds its
     * address — {@code PermissionService.roleFrom} is unchanged and still answers EDITOR — it
     * simply stops appearing in the lists of people it was never shared with. Owners, document
     * members and workspace members see it exactly as before, because those are the three ways a
     * document is actually somebody's.
     *
     * <p>The parity test changed shape with it, from an equivalence to an implication: everything
     * listed must be readable, and not everything readable need be listed. That is the direction
     * that matters — the other one is a leak.
     *
     * <p>Takes the caller's id three times.
     */
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

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    /** The caller's visible documents — the ones not deleted. Most recently changed first. */
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

    /** The bin — soft-deleted documents, available to restore. */
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

    /**
     * Full-text search over the title and the content preview.
     *
     * <h2>The content, since CP29</h2>
     * This used to search {@code title || ' ' || preview_text}, and the preview is the first 200
     * characters — so it worked exactly as far as the opening sentence and then stopped. A search
     * that answers, looks like it searched, and does not return the document containing the word
     * is worse than no search at all.
     *
     * <p>The vector is built on the snapshot path, so <b>the index lags</b>: a document is
     * searchable as of its last snapshot. Stated rather than hidden — see V9 for why that is the
     * right side of this trade.
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
    public List<DocumentSummary> search(String callerId, UUID workspaceId, String query, int limit) {
        return jdbc.query(
            "SELECT " + SUMMARY_COLUMNS + " FROM documents d "
                + "WHERE d.deleted_at IS NULL "
                + "AND (d.search_vector @@ websearch_to_tsquery('simple', ?) "
                // The title, separately, for two reasons that are both about staleness rather
                // than about titles being special. A document that has never been snapshotted has
                // no vector at all — a document created a second ago is exactly the one somebody
                // is most likely to look for. And renaming does not rebuild the vector, so
                // between a rename and the next snapshot the new title exists only here.
                + " OR to_tsvector('simple', d.title) @@ websearch_to_tsquery('simple', ?)) "
                // Scoped to the workspace when one was asked for. The endpoint accepts `q` and
                // `workspace` together and used to drop the second silently, so "search inside
                // this workspace" searched every workspace and returned other people's results.
                + "AND (CAST(? AS uuid) IS NULL OR d.workspace_id = CAST(? AS uuid)) "
                + "AND " + READABLE_BY_CALLER
                + "ORDER BY d.updated_at DESC LIMIT ?",
            SUMMARY, query, query, workspaceId, workspaceId, callerId, callerId, callerId, limit);
    }

    // ------------------------------------------------------------------
    // Writes
    // ------------------------------------------------------------------

    /**
     * Creates a document — <b>private</b>, and says so in the INSERT.
     *
     * <p>It used to leave {@code visibility} to the column default, which was {@code 'link'}. So
     * every document created by any path that did not follow up with an explicit
     * {@code setVisibility} was world-editable from the moment it existed, and the only reason the
     * product's own endpoint was safe is that it remembered to make that second call. V12 drops the
     * default for the same reason V10 dropped {@code document_members.role}'s: a default that
     * grants access is a decision nobody makes on purpose, taken by whoever forgets.
     */
    public void create(UUID documentId, String title, UUID workspaceId) {
        jdbc.update(
            "INSERT INTO documents (id, title, workspace_id, visibility) VALUES (?, ?, ?, 'private')",
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
        // The search vector is built HERE for the same reason the preview is: this is the one
        // moment the content is in hand. It covers the title as well, so one index answers both
        // halves of the question people actually ask (CP29).
        //
        // The vector, not the text. Storing the content beside the document would duplicate what
        // the operation log owns; a tsvector is what search needs, nothing else can be read back
        // out of it, and that also stops this column from quietly becoming a second source of
        // truth for the text.
        jdbc.update("UPDATE documents SET preview_text = ?, "
                + "search_vector = to_tsvector('simple', title || ' ' || ?) WHERE id = ?",
            preview, indexable(text), documentId);
    }

    public static final int PREVIEW_LENGTH = 200;

    /**
     * The upper bound on what is handed to {@code to_tsvector}.
     *
     * <p>Postgres refuses a string of about a megabyte with <i>"string is too long for
     * tsvector"</i>, and that refusal would land on the SNAPSHOT path — which is the one place
     * that must not throw. It would not lose data (the snapshot is written first) and it would
     * do something worse than losing it: {@code snapshotTaken()} would never run, so the sweep
     * would re-snapshot the same document every second, forever, for as long as it stayed above
     * the limit.
     *
     * <p>250 000 characters is at most a megabyte of UTF-8 even for four-byte characters. Beyond
     * it, a document is searchable by its first quarter-million characters — a limit worth
     * naming, and a much later one than the 200 characters CP29 started from.
     */
    private static final int INDEXABLE_LENGTH = 250_000;

    private static String indexable(String text) {
        return text.length() <= INDEXABLE_LENGTH ? text : text.substring(0, INDEXABLE_LENGTH);
    }

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
