package dev.collabedit.app.comments;

import dev.collabedit.app.auth.CommentAnchorAuthority;
import dev.collabedit.app.auth.ThreadDocumentResolver;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 * introduced. This checkpoint uses it and adds nothing to it.
 *
 * <p>The anchor lives in the operation log, so it moves with the text and survives edits to its
 * surroundings in exactly the way bold does. Keeping it in the database next to the thread would be
 * a return to offsets, a mistake already rejected twice (for cursors, the mark layer for
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
public class CommentService implements ThreadDocumentResolver, CommentAnchorAuthority {

    private final JdbcTemplate jdbc;

    public CommentService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * One message in a thread.
     *
     * <p>{@code editedAt} used to be here, read from the column {@code V4} created for it and
     * published all the way to the client's type. Nothing has ever written it — there is no
     * edit-a-comment endpoint — so it was {@code null} in every response the system has ever sent,
     * which is a field that promises something the product does not do.
     *
     * <p>The COLUMN stays. Dropping it means either editing an applied migration, which breaks the
     * checksum of every database that has run it, or adding one whose whole content is removing an
     * unused column. Both cost more than the column does, and if editing ever arrives it is the one
     * piece already in place.
     */
    public record Message(UUID id, String authorId, String authorName, String authorColor,
                          String body, Instant createdAt) {
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
     * <p>The comment split claimed comments do not need to be a CRDT because a message has one author, is
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
        // ON CONFLICT, not a bare INSERT. The check above and this write are two statements, so
        // two in-flight retries of the same comment both found nothing and both inserted — and the
        // loser got a DuplicateKeyException, i.e. a 500 from the one mechanism whose entire purpose
        // is making the second attempt a no-op. That is reachable from the shipped client: `flush`
        // has no in-flight guard and runs from both an effect and the browser's `online` event.
        //
        // Letting the unique index decide closes the window instead of narrowing it. On conflict
        // nothing is written and the row that won is read back below.
        int inserted = jdbc.update(
            "INSERT INTO comment_threads (id, doc_id, created_by, quoted_text, client_id) "
                + "VALUES (?, ?, ?, ?, ?) "
                // The index is PARTIAL (V8: WHERE client_id IS NOT NULL), so the
                // predicate has to be repeated here for Postgres to infer it.
                + "ON CONFLICT (client_id) WHERE client_id IS NOT NULL DO NOTHING",
            threadId, documentId, authorId, quotedText, clientId);
        if (inserted == 0) {
            UUID winner = threadByClientId(clientId);
            if (winner != null) {
                return winner;
            }
            // Only reachable if the conflict was on something other than client_id, which the
            // schema does not allow. Loud rather than a silent null.
            throw new IllegalStateException(
                "the thread insert conflicted but no row with client_id " + clientId + " exists");
        }
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
        // Same reasoning as openThread: the check above and this write are two statements, and the
        // unique index is what actually decides. The predicate is repeated because the index is
        // partial (V8).
        int inserted = jdbc.update(
            "INSERT INTO comment_messages (id, thread_id, author_id, body, client_id) "
                + "VALUES (?, ?, ?, ?, ?) "
                + "ON CONFLICT (client_id) WHERE client_id IS NOT NULL DO NOTHING",
            messageId, threadId, authorId, body, clientId);
        if (inserted == 0) {
            UUID winner = messageByClientId(clientId);
            if (winner != null) {
                return winner;
            }
            throw new IllegalStateException(
                "the message insert conflicted but no row with client_id " + clientId + " exists");
        }
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
     * Whether this caller may anchor this thread in this document.
     *
     * <p>The implementation of {@link CommentAnchorAuthority}; see there for what an unchecked mark
     * value allowed.
     *
     * <h2>Why the thread's document, and NOT "you created it"</h2>
     * The first version of this asked for {@code created_by = ?}, which is the tightest rule and
     * matches the only moment the product legitimately anchors a thread — immediately after
     * creating it. It was the wrong rule when it was written, because identity did not outlive a
     * tab: the only login was passwordless, {@code UserDirectory.create} minted a fresh
     * {@code UUID} per call, and {@code session.ts} keeps the session in {@code sessionStorage}
     * precisely so it is per-tab.
     *
     * <p><b>That constraint is now lifted for federated users</b> — a provider returns the same
     * {@code (provider, subject)} every time, so {@code created_by} is durable. The rule has not
     * been tightened back, deliberately: the development login still mints an identifier per call,
     * so a deployment with it enabled would strand replayed anchors exactly as described below.
     * Tightening it belongs with removing that login, not before, and it is written here so the
     * next person finds a decision rather than an oversight.
     *
     * <p>The client's unacknowledged-operation buffer, by contrast, is in IndexedDB — origin-scoped
     * and replayed on every reconnect, for every operation type including this one. So: post a
     * comment, close the tab before the {@code AddMark} is acknowledged, open a new one. The buffer
     * replays the mark under a new user id, {@code created_by} no longer matches, and the frame is
     * refused — silently, because a refusal deliberately sends nothing back — and resent on every
     * reconnect for ever. That is the same shape of permanent silent refusal
     * {@code SessionLifecycle.userOfReplica} documents having already been caught once, and it
     * would hit ordinary users doing nothing wrong.
     *
     * <p>So the rule is the strongest one that identity in this system can actually carry: the
     * thread must exist and belong to <b>this</b> document. That stops a mark carrying a thread from
     * another document, and it stops one carrying an identifier that is not a thread at all.
     *
     * <p><b>What it does not stop</b>, stated rather than left to be found: a commenter can still
     * widen or re-anchor another thread <i>in the same document</i>, because they can read every
     * thread identifier in it. The cost is a highlight in the wrong place — no content escapes, and
     * an editor can remove the mark — and closing it properly needs the operation's authorship to be
     * something the frame proves rather than something the body claims. That is the debt
     * {@code limitations.md} records under "authorization looks inside the frame body", and it is
     * the same debt, not a new one.
     *
     * <p>An editor never reaches this check at all: {@code DocumentAccessInterceptor} allows their
     * frames on the strength of the role. Consistent rather than an oversight — an editor can
     * already delete the text a thread is attached to.
     */
    @Override
    public boolean mayAnchorThread(String userId, UUID documentId, UUID threadId) {
        if (userId == null || documentId == null || threadId == null) {
            return false;
        }
        return documentId.equals(documentOf(threadId));
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

        // The message query filters on the SAME condition as the thread query, not just on the
        // document. It used to select every message of every thread in the document and then throw
        // away the ones belonging to threads the caller had already excluded — so asking for the
        // three open threads on a document with two hundred resolved ones transferred all two
        // hundred conversations to discard them in Java. The javadoc above reasoned about the query
        // COUNT and not at all about the row count.
        List<MessageRow> messages = jdbc.query(
            "SELECT m.id, m.thread_id, m.author_id, m.body, m.created_at, "
                + "       u.display_name, u.color "
                + "  FROM comment_messages m "
                + "  JOIN comment_threads t ON t.id = m.thread_id "
                + "  JOIN users u           ON u.id = m.author_id "
                + " WHERE t.doc_id = ? "
                + (includeResolved ? "" : "AND t.resolved_at IS NULL ")
                + " ORDER BY m.created_at",
            (rs, rowNum) -> new MessageRow(
                UUID.fromString(rs.getString("thread_id")),
                new Message(
                    UUID.fromString(rs.getString("id")),
                    rs.getString("author_id"),
                    rs.getString("display_name"),
                    rs.getString("color"),
                    rs.getString("body"),
                    rs.getTimestamp("created_at").toInstant())),
            documentId);

        return assemble(threads, messages);
    }

    /**
     * One thread with its messages, by identifier.
     *
     * <p>Exists because {@code CommentController.open} used to read back the thread it had just
     * created by listing EVERY thread and message in the document and filtering for one row. The
     * cost of posting a comment grew with the size of the conversation around it.
     */
    public Optional<Thread> findThread(UUID threadId) {
        List<ThreadRow> threads = jdbc.query(
            "SELECT id, doc_id, created_by, created_at, resolved_at, quoted_text "
                + "FROM comment_threads WHERE id = ?",
            (rs, rowNum) -> new ThreadRow(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("doc_id")),
                rs.getString("created_by"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("resolved_at") == null ? null : rs.getTimestamp("resolved_at").toInstant(),
                rs.getString("quoted_text")),
            threadId);
        if (threads.isEmpty()) {
            return Optional.empty();
        }

        List<MessageRow> messages = jdbc.query(
            "SELECT m.id, m.thread_id, m.author_id, m.body, m.created_at, "
                + "       u.display_name, u.color "
                + "  FROM comment_messages m "
                + "  JOIN users u ON u.id = m.author_id "
                + " WHERE m.thread_id = ? "
                + " ORDER BY m.created_at",
            (rs, rowNum) -> new MessageRow(
                UUID.fromString(rs.getString("thread_id")),
                new Message(
                    UUID.fromString(rs.getString("id")),
                    rs.getString("author_id"),
                    rs.getString("display_name"),
                    rs.getString("color"),
                    rs.getString("body"),
                    rs.getTimestamp("created_at").toInstant())),
            threadId);

        return Optional.of(assemble(threads, messages).getFirst());
    }

    /**
     * Puts the messages back with their threads.
     *
     * <p>Grouped once rather than re-filtered per thread: the shape this replaces was a nested
     * stream, i.e. one pass over every message for every thread.
     */
    private static List<Thread> assemble(List<ThreadRow> threads, List<MessageRow> messages) {
        Map<UUID, List<Message>> byThread = new HashMap<>();
        for (MessageRow row : messages) {
            byThread.computeIfAbsent(row.threadId(), id -> new ArrayList<>()).add(row.message());
        }
        return threads.stream()
            .map(thread -> new Thread(
                thread.id(), thread.documentId(), thread.createdBy(), thread.createdAt(),
                thread.resolvedAt(), thread.quotedText(),
                // Anchoring is computed a layer up — true by default here, because the database
                // knows nothing about the document's state.
                true,
                byThread.getOrDefault(thread.id(), List.of())))
            .toList();
    }

    private record ThreadRow(UUID id, UUID documentId, String createdBy, Instant createdAt,
                             Instant resolvedAt, String quotedText) {
    }

    private record MessageRow(UUID threadId, Message message) {
    }
}
