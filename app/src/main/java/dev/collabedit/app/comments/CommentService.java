package dev.collabedit.app.comments;

import dev.collabedit.app.auth.ThreadDocumentResolver;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Wątki komentarzy (CP14).
 *
 * <h2>Co tu NIE ma — i to jest najważniejsze</h2>
 * Kotwicy. Zakres w dokumencie, którego wątek dotyczy, jest <b>znacznikiem CRDT</b> typu
 * {@code comment} z identyfikatorem wątku jako wartością — czyli tą samą maszynerią, którą
 * wprowadził CP13 (ADR-0010). Ten checkpoint jej używa i nie dokłada do niej niczego.
 *
 * <p>Kotwica żyje w logu operacji, więc przesuwa się razem z tekstem i przeżywa edycję
 * sąsiedztwa dokładnie tak samo jak pogrubienie. Trzymanie jej w bazie obok wątku byłoby
 * powrotem do offsetów, czyli błędu odrzuconego już dwa razy (ADR-0006 dla kursorów,
 * ADR-0010 dla formatowania).
 *
 * <h2>Dlaczego treść wątku NIE jest CRDT</h2>
 * Wiadomość ma jednego autora, powstaje w całości i nie jest współbieżnie edytowana znak
 * po znaku. CRDT rozwiązuje konkretny problem — współbieżną edycję tej samej sekwencji —
 * i stosowanie go tam, gdzie tego problemu nie ma, to koszt bez korzyści: log operacji,
 * scalanie, tombstone'y i GC dla tekstu, którego nikt nigdy nie edytuje równolegle.
 *
 * <p>Pokusa „skoro mamy CRDT, wszystko będzie CRDT" jest realna. To jest miejsce,
 * w którym świadomie jej nie ulegamy.
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
     * @param anchored czy zakotwiczony tekst nadal istnieje w dokumencie; wyliczane
     *                 z warstwy CRDT, nie z bazy — patrz {@code CommentController}
     */
    public record Thread(UUID id, UUID documentId, String createdBy, Instant createdAt,
                         Instant resolvedAt, String quotedText, boolean anchored,
                         List<Message> messages) {
    }

    /**
     * Zakłada wątek i zapisuje pierwszą wiadomość — <b>w jednej transakcji</b>.
     *
     * <p>Wątek bez wiadomości jest stanem, którego interfejs nie umie pokazać i którego
     * użytkownik nie umiał wytworzyć celowo. Powstałby wyłącznie z połowicznego zapisu,
     * więc odbieramy tę możliwość, zamiast dokładać obsługę przypadku, który nie powinien
     * istnieć.
     */
    @Transactional
    public UUID openThread(UUID documentId, String authorId, String quotedText, String body) {
        UUID threadId = UUID.randomUUID();
        jdbc.update("INSERT INTO comment_threads (id, doc_id, created_by, quoted_text) "
            + "VALUES (?, ?, ?, ?)", threadId, documentId, authorId, quotedText);
        addMessage(threadId, authorId, body);
        return threadId;
    }

    public UUID addMessage(UUID threadId, String authorId, String body) {
        UUID messageId = UUID.randomUUID();
        jdbc.update("INSERT INTO comment_messages (id, thread_id, author_id, body) "
            + "VALUES (?, ?, ?, ?)", messageId, threadId, authorId, body);
        return messageId;
    }

    /**
     * Rozwiązuje wątek. <b>Nie usuwa go.</b>
     *
     * <p>Rozmowa zostaje — zmienia się tylko to, czy jest domyślnie widoczna. Usunięcie
     * byłoby utratą kontekstu decyzji, a to jest zwykle jedyna rzecz, dla której ktoś
     * wraca do komentarzy po miesiącach.
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
     * Wątki dokumentu wraz z wiadomościami.
     *
     * <p>Dwa zapytania zamiast N+1: jedno po wątki, jedno po wszystkie ich wiadomości.
     * Dokument z pięćdziesięcioma wątkami po pięć wiadomości to inaczej pięćdziesiąt jeden
     * zapytań przy każdym otwarciu panelu komentarzy.
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
                // Zakotwiczenie wylicza warstwa wyżej — tutaj domyślnie true, bo baza
                // nie wie nic o stanie dokumentu.
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
