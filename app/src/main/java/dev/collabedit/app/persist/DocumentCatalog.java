package dev.collabedit.app.persist;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Metadane dokumentów: tytuł, przynależność do workspace'u, stan usunięcia, podgląd treści.
 *
 * <h2>Co tu NIE trafia</h2>
 * Treść dokumentu. Ta żyje w logu operacji ({@link OperationStore}) i w replikach w pamięci —
 * ten katalog wie wyłącznie to, co potrzebne do <b>wypisania listy</b>. Rozdzielenie jest
 * celowe: lista stu dokumentów ma być jednym zapytaniem, a nie stoma załadowanymi replikami
 * CRDT. Kolumna {@code preview_text} istnieje dokładnie po to i jest cache'em na tych samych
 * zasadach co snapshoty (ADR-0003) — wolno ją skasować w każdej chwili.
 *
 * <h2>Dlaczego usuwanie jest miękkie</h2>
 * {@code operations} ma {@code ON DELETE CASCADE} na {@code doc_id}. Twarde usunięcie
 * dokumentu skasowałoby więc jego log operacji, czyli źródło prawdy — tabelę, która
 * w tym systemie jest wyłącznie dopisywana. „Usuń" w interfejsie znaczy „schowaj",
 * i tylko tego chce użytkownik, który to klika.
 */
@Repository
public class DocumentCatalog {

    private final JdbcTemplate jdbc;

    public DocumentCatalog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param preview pierwsze znaki treści; pusty, dopóki nie powstał pierwszy snapshot
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
    // Odczyt
    // ------------------------------------------------------------------

    /** Dokumenty widoczne — czyli nieusunięte. Kolejność: ostatnio zmieniane na górze. */
    public List<DocumentSummary> list(UUID workspaceId, int limit) {
        if (workspaceId == null) {
            return jdbc.query("SELECT " + SUMMARY_COLUMNS + " FROM documents "
                + "WHERE deleted_at IS NULL ORDER BY updated_at DESC LIMIT ?", SUMMARY, limit);
        }
        return jdbc.query("SELECT " + SUMMARY_COLUMNS + " FROM documents "
            + "WHERE deleted_at IS NULL AND workspace_id = ? ORDER BY updated_at DESC LIMIT ?",
            SUMMARY, workspaceId, limit);
    }

    /** Kosz — dokumenty usunięte miękko, do przywrócenia. */
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
     * Wyszukiwanie pełnotekstowe po tytule i podglądzie treści.
     *
     * <p>Konfiguracja {@code simple}, nie {@code polish} — ta druga wymaga słowników ispell,
     * których standardowy obraz Postgresa nie zawiera, więc migracja padałaby przy starcie.
     * Brak stemmingu znaczy, że „dokumenty" nie znajdzie się po „dokument”. Znane ograniczenie,
     * wymienione w README.
     *
     * <p>{@code websearch_to_tsquery}, a nie {@code plainto_tsquery}: przyjmuje to, co ludzie
     * naprawdę wpisują w pole wyszukiwania (cudzysłowy, {@code or}, {@code -}) i nie wywala się
     * na składni, tylko interpretuje ją najlepiej jak umie.
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
    // Zapis
    // ------------------------------------------------------------------

    public void create(UUID documentId, String title, UUID workspaceId) {
        jdbc.update("INSERT INTO documents (id, title, workspace_id) VALUES (?, ?, ?)",
            documentId, title, workspaceId);
    }

    /** @return {@code true}, gdy dokument istniał i nie był usunięty */
    public boolean rename(UUID documentId, String title) {
        return jdbc.update("UPDATE documents SET title = ?, updated_at = now() "
            + "WHERE id = ? AND deleted_at IS NULL", title, documentId) == 1;
    }

    /**
     * Chowa dokument. Log operacji zostaje nietknięty — patrz komentarz nad klasą.
     *
     * <p>Warunek {@code deleted_at IS NULL} sprawia, że powtórzone usunięcie nie przesuwa
     * daty: „usunięty wczoraj" ma znaczyć wczoraj, również gdy ktoś kliknie ponownie.
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
     * Odświeża podgląd treści. Wołane przy snapshotowaniu, bo wtedy i tak mamy stan w ręku.
     *
     * <p>Podgląd jest ucinany do {@link #PREVIEW_LENGTH} znaków — lista dokumentów pokazuje
     * jedną linijkę, a przechowywanie całego dokumentu w kolumnie obok dokumentu byłoby
     * duplikowaniem treści, nie cache'owaniem jej fragmentu.
     */
    public void updatePreview(UUID documentId, String text) {
        String preview = text.length() <= PREVIEW_LENGTH ? text : text.substring(0, PREVIEW_LENGTH);
        jdbc.update("UPDATE documents SET preview_text = ? WHERE id = ?", preview, documentId);
    }

    public static final int PREVIEW_LENGTH = 200;

    // ------------------------------------------------------------------
    // Workspace'y
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
