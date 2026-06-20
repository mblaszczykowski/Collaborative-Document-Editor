package dev.collabedit.app.comments;

import dev.collabedit.app.auth.ThreadDocumentResolver;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Comment threads (CP14).
 *
 * <h2>What is NOT here — and this is the important part</h2>
 * The anchor. The range in the document a thread refers to is a <b>CRDT mark</b> of type
 * {@code comment} carrying the thread identifier as its value — that is, the same machinery CP13
 * introduced (ADR-0010). This checkpoint uses it and adds nothing to it.
 *
 * <p>The anchor lives in the operation log, so it moves with the text and survives edits to its
 * surroundings in exactly the way bold does. Keeping it in the database next to the thread would be
 * a return to offsets, a mistake already rejected twice (ADR-0006 for cursors, ADR-0010 for
 * formatting).
 *
 * <h2>Why a thread's content is NOT a CRDT</h2>
 * A message has one author, is created whole, and is not concurrently edited character by
 * character. A CRDT solves a specific problem — concurrent editing of the same sequence — and
 * applying it where that problem does not exist is a cost with no benefit: an operation log,
 * merging, tombstones and GC for text nobody ever edits in parallel.
 *
 * <p>The temptation of "we have a CRDT, so everything will be a CRDT" is real. This is the place
 * where we deliberately do not give in to it.
 */
@Service
public class CommentService implements ThreadDocumentResolver {

    private final JdbcTemplate jdbc;

    public CommentService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Message(UUID id, String authorId, String authorName, String authorColor,
                          String body, Instant createdAt, Instant editedAt) {
    }

    /**
     * @param anchored whether the anchored text still exists in the document; computed from the
     *                 CRDT layer, not from the database — see {@code CommentController}
     */
    public record Thread(UUID id, UUID documentId, String createdBy, Instant createdAt,
                         Instant resolvedAt, String quotedText, boolean anchored,
                         List<Message> messages) {
    }

    /**
     * Opens a thread and stores its first message — <b>in one transaction</b>.
     *
     * <p>A thread with no messages is a state the interface cannot render and the user could not
     * deliberately produce. It could only arise from a half-completed write, so we remove the
     * possibility instead of adding handling for a case that should not exist.
     */
    @Transactional
    public UUID openThread(UUID documentId, String authorId, String quotedText, String body) {
        return openThread(documentId, authorId, quotedText, body, null);
    }

    /**
     * Opens a thread, at most once per {@code clientId} (CP27).
     *
     * <h2>Why an idempotency key rather than a CRDT</h2>
     * A comment written without a connection waits in a client-side outbox and is retried on
     * reconnect — and the client cannot tell whether the first attempt reached the server before
     * the connection dropped. What it needs is not merging but <b>exactly once</b>, and that is a
     * unique column rather than a data structure.
     *
     * <p>ADR-0011 claimed comments do not need to be a CRDT because a message has one author, is
     * written in one go and is never edited concurrently character by character. This is where
     * that claim is tested by use instead of by argument, and it holds: the offline path needed a
     * key, not a merge.
     *
     * @param clientId the caller's own identifier for this comment, or {@code null} when it does
     *                 not have one — an unkeyed call keeps the old behaviour of always inserting
     */
    @Transactional
    public UUID openThread(UUID documentId, String authorId, String quotedText, String body,
                           String clientId) {
        if (clientId != null) {
            UUID existing = threadByClientId(clientId);
            if (existing != null) {
                // The retry of an attempt that DID arrive. Returning the original rather than
                // reporting a conflict: from the writer's point of view their comment is posted,
                // which is true, and it is the answer that lets them stop retrying.
                return existing;
            }
        }
        UUID threadId = UUID.randomUUID();
        jdbc.update("INSERT INTO comment_threads (id, doc_id, created_by, quoted_text, client_id) "
            + "VALUES (?, ?, ?, ?, ?)", threadId, documentId, authorId, quotedText, clientId);
        addMessage(threadId, authorId, body, clientId == null ? null : clientId + ":first");
        return threadId;
    }

    public UUID addMessage(UUID threadId, String authorId, String body) {
        return addMessage(threadId, authorId, body, null);
    }

    /** As above, at most once per {@code clientId}. */
    public UUID addMessage(UUID threadId, String authorId, String body, String clientId) {
        if (clientId != null) {
            UUID existing = messageByClientId(clientId);
            if (existing != null) {
                return existing;
            }
        }
        UUID messageId = UUID.randomUUID();
        jdbc.update("INSERT INTO comment_messages (id, thread_id, author_id, body, client_id) "
            + "VALUES (?, ?, ?, ?, ?)", messageId, threadId, authorId, body, clientId);
        return messageId;
    }

    private UUID threadByClientId(String clientId) {
        return jdbc.query("SELECT id FROM comment_threads WHERE client_id = ?",
            (rs, rowNum) -> (UUID) rs.getObject("id"), clientId).stream().findFirst().orElse(null);
    }

    private UUID messageByClientId(String clientId) {
        return jdbc.query("SELECT id FROM comment_messages WHERE client_id = ?",
            (rs, rowNum) -> (UUID) rs.getObject("id"), clientId).stream().findFirst().orElse(null);
    }

    /**
     * Resolves a thread. <b>It does not delete it.</b>
     *
     * <p>The conversation stays — all that changes is whether it is visible by default. Deleting
     * would lose the context behind a decision, and that is usually the only reason anyone comes
     * back to comments months later.
     */
    public boolean resolve(UUID threadId, String userId) {
        return jdbc.update("UPDATE comment_threads SET resolved_at = now(), resolved_by = ? "
            + "WHERE id = ? AND resolved_at IS NULL", userId, threadId) == 1;
    }

    public boolean reopen(UUID threadId) {
        return jdbc.update("UPDATE comment_threads SET resolved_at = NULL, resolved_by = NULL "
            + "WHERE id = ? AND resolved_at IS NOT NULL", threadId) == 1;
    }

    /**
     * Which document a thread belongs to.
     *
     * <p>Also the implementation of {@link ThreadDocumentResolver}, which is how
     * {@code DocumentAccessGuard} authorizes {@code /api/comments/{threadId}/…} without the
     * auth layer having to know that comments exist.
     */
    @Override
    public UUID documentOf(UUID threadId) {
        List<UUID> found = jdbc.query("SELECT doc_id FROM comment_threads WHERE id = ?",
            (rs, rowNum) -> UUID.fromString(rs.getString("doc_id")), threadId);
        return found.isEmpty() ? null : found.getFirst();
    }

    /**
     * A document's threads together with their messages.
     *
     * <p>Two queries instead of N+1: one for the threads, one for all of their messages. Otherwise
     * a document with fifty threads of five messages each means fifty-one queries every time the
     * comment panel is opened.
     */
    public List<Thread> threadsOf(UUID documentId, boolean includeResolved) {
        List<ThreadRow> threads = jdbc.query(
            "SELECT id, doc_id, created_by, created_at, resolved_at, quoted_text "
                + "FROM comment_threads WHERE doc_id = ? "
                + (includeResolved ? "" : "AND resolved_at IS NULL ")
                + "ORDER BY created_at",
            (rs, rowNum) -> new ThreadRow(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("doc_id")),
                rs.getString("created_by"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("resolved_at") == null ? null : rs.getTimestamp("resolved_at").toInstant(),
                rs.getString("quoted_text")),
            documentId);

        if (threads.isEmpty()) {
            return List.of();
        }

        List<MessageRow> messages = jdbc.query("""
            SELECT m.id, m.thread_id, m.author_id, m.body, m.created_at, m.edited_at,
                   u.display_name, u.color
              FROM comment_messages m
              JOIN comment_threads t ON t.id = m.thread_id
              JOIN users u           ON u.id = m.author_id
             WHERE t.doc_id = ?
             ORDER BY m.created_at
            """,
            (rs, rowNum) -> new MessageRow(
                UUID.fromString(rs.getString("thread_id")),
                new Message(
                    UUID.fromString(rs.getString("id")),
                    rs.getString("author_id"),
                    rs.getString("display_name"),
                    rs.getString("color"),
                    rs.getString("body"),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("edited_at") == null ? null : rs.getTimestamp("edited_at").toInstant())),
            documentId);

        return threads.stream()
            .map(thread -> new Thread(
                thread.id(), thread.documentId(), thread.createdBy(), thread.createdAt(),
                thread.resolvedAt(), thread.quotedText(),
                // Anchoring is computed a layer up — true by default here, because the database
                // knows nothing about the document's state.
                true,
                messages.stream()
                    .filter(row -> row.threadId().equals(thread.id()))
                    .map(MessageRow::message)
                    .toList()))
            .toList();
    }

    private record ThreadRow(UUID id, UUID documentId, String createdBy, Instant createdAt,
                             Instant resolvedAt, String quotedText) {
    }

    private record MessageRow(UUID threadId, Message message) {
    }
}
