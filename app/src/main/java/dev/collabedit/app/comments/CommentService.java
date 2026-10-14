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

@Service
public class CommentService implements ThreadDocumentResolver, CommentAnchorAuthority {
    private final JdbcTemplate jdbc;

    public CommentService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Message(UUID id, String authorId, String authorName, String authorColor,
                          String body, Instant createdAt) {
    }

    public record Thread(UUID id, UUID documentId, String createdBy, Instant createdAt,
                         Instant resolvedAt, String quotedText, boolean anchored,
                         List<Message> messages) {
    }

    @Transactional
    public UUID openThread(UUID documentId, String authorId, String quotedText, String body) {
        return openThread(documentId, authorId, quotedText, body, null);
    }

    @Transactional
    public UUID openThread(UUID documentId, String authorId, String quotedText, String body,
                           String clientId) {
        if (clientId != null) {
            UUID existing = threadByClientId(documentId, authorId, clientId, quotedText, body);
            if (existing != null) {
                return existing;
            }
        }
        UUID threadId = UUID.randomUUID();
        int inserted = jdbc.update(
            "INSERT INTO comment_threads (id, doc_id, created_by, quoted_text, client_id, initial_body) "
                + "VALUES (?, ?, ?, ?, ?, ?) "
                + "ON CONFLICT (doc_id, created_by, client_id) WHERE client_id IS NOT NULL DO NOTHING",
            threadId, documentId, authorId, quotedText, clientId, body);
        if (inserted == 0) {
            UUID winner = threadByClientId(documentId, authorId, clientId, quotedText, body);
            if (winner != null) {
                return winner;
            }
            throw new IllegalStateException(
                "the thread insert conflicted but no row with client_id " + clientId + " exists");
        }
        addMessage(threadId, authorId, body, clientId == null ? null : clientId + ":first");
        return threadId;
    }

    public UUID addMessage(UUID threadId, String authorId, String body) {
        return addMessage(threadId, authorId, body, null);
    }

    public UUID addMessage(UUID threadId, String authorId, String body, String clientId) {
        if (clientId != null) {
            UUID existing = messageByClientId(threadId, authorId, clientId, body);
            if (existing != null) {
                return existing;
            }
        }
        UUID messageId = UUID.randomUUID();
        int inserted = jdbc.update(
            "INSERT INTO comment_messages (id, thread_id, author_id, body, client_id) "
                + "VALUES (?, ?, ?, ?, ?) "
                + "ON CONFLICT (thread_id, author_id, client_id) WHERE client_id IS NOT NULL DO NOTHING",
            messageId, threadId, authorId, body, clientId);
        if (inserted == 0) {
            UUID winner = messageByClientId(threadId, authorId, clientId, body);
            if (winner != null) {
                return winner;
            }
            throw new IllegalStateException(
                "the message insert conflicted but no row with client_id " + clientId + " exists");
        }
        return messageId;
    }

    private UUID threadByClientId(UUID documentId, String authorId, String clientId,
                                  String quotedText, String body) {
        return jdbc.query("SELECT id, quoted_text, initial_body FROM comment_threads "
                + "WHERE doc_id = ? AND created_by = ? AND client_id = ?",
            (rs, rowNum) -> {
                if (!java.util.Objects.equals(quotedText, rs.getString("quoted_text"))
                    || !java.util.Objects.equals(body, rs.getString("initial_body"))) {
                    throw new IllegalStateException("This comment retry key was already used with different content.");
                }
                return (UUID) rs.getObject("id");
            }, documentId, authorId, clientId).stream().findFirst().orElse(null);
    }

    private UUID messageByClientId(UUID threadId, String authorId, String clientId, String body) {
        return jdbc.query("SELECT id, body FROM comment_messages "
                + "WHERE thread_id = ? AND author_id = ? AND client_id = ?",
            (rs, rowNum) -> {
                if (!java.util.Objects.equals(body, rs.getString("body"))) {
                    throw new IllegalStateException("This reply retry key was already used with different content.");
                }
                return (UUID) rs.getObject("id");
            }, threadId, authorId, clientId).stream().findFirst().orElse(null);
    }

    public boolean resolve(UUID threadId, String userId) {
        return jdbc.update("UPDATE comment_threads SET resolved_at = now(), resolved_by = ? "
            + "WHERE id = ? AND resolved_at IS NULL", userId, threadId) == 1;
    }

    public boolean reopen(UUID threadId) {
        return jdbc.update("UPDATE comment_threads SET resolved_at = NULL, resolved_by = NULL "
            + "WHERE id = ? AND resolved_at IS NOT NULL", threadId) == 1;
    }

    @Override
    public UUID documentOf(UUID threadId) {
        List<UUID> found = jdbc.query("SELECT doc_id FROM comment_threads WHERE id = ?",
            (rs, rowNum) -> UUID.fromString(rs.getString("doc_id")), threadId);
        return found.isEmpty() ? null : found.getFirst();
    }

    @Override
    public boolean mayAnchorThread(String userId, UUID documentId, UUID threadId) {
        if (userId == null || documentId == null || threadId == null) {
            return false;
        }
        return documentId.equals(documentOf(threadId));
    }

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

    private static List<Thread> assemble(List<ThreadRow> threads, List<MessageRow> messages) {
        Map<UUID, List<Message>> byThread = new HashMap<>();
        for (MessageRow row : messages) {
            byThread.computeIfAbsent(row.threadId(), id -> new ArrayList<>()).add(row.message());
        }
        return threads.stream()
            .map(thread -> new Thread(
                thread.id(), thread.documentId(), thread.createdBy(), thread.createdAt(),
                thread.resolvedAt(), thread.quotedText(),
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
