package dev.collabedit.app.persist;

import dev.collabedit.crdt.DocumentSnapshot;
import dev.collabedit.crdt.SnapshotCodec;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Snapshoty — <b>cache</b> nad logiem operacji (ADR-0003).
 *
 * <p>Cały ten magazyn wolno w każdej chwili wyczyścić. System odtworzy stan z logu:
 * wolniej, ale identycznie. Test w CP5 kasuje wszystkie snapshoty i sprawdza, że żaden
 * wynik się nie zmienia — bez takiego testu cache po cichu stałby się źródłem prawdy,
 * co jest jednym z tych błędów, które ujawniają się dopiero przy awarii.
 */
@Repository
public class SnapshotStore {

    /** Snapshot z niezgodną wersją formatu jest ignorowany, a nie migrowany (ADR-0003). */
    private record Row(byte[] state, long upToServerSeq) {
    }

    private final JdbcTemplate jdbc;

    public SnapshotStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void save(UUID documentId, long upToServerSeq, DocumentSnapshot snapshot) {
        jdbc.update(
            "INSERT INTO snapshots (doc_id, up_to_server_seq, format_version, state, text_length, tombstone_count) "
                + "VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (doc_id, up_to_server_seq) DO NOTHING",
            documentId,
            upToServerSeq,
            snapshot.formatVersion(),
            SnapshotCodec.encode(snapshot),
            snapshot.textLength(),
            snapshot.tombstoneCount());
    }

    /**
     * Najnowszy czytelny snapshot nie późniejszy niż podany {@code server_seq}.
     *
     * <p>Zwraca puste, gdy snapshotu nie ma <b>albo</b> gdy jest zapisany w nieobsługiwanym
     * formacie. Wywołujący nie musi tych przypadków rozróżniać — w obu odpowiedzią jest
     * odtworzenie z logu od zera.
     */
    public Optional<LoadedSnapshot> findLatestUpTo(UUID documentId, long throughServerSeq) {
        try {
            Row row = jdbc.queryForObject(
                "SELECT state, up_to_server_seq FROM snapshots "
                    + "WHERE doc_id = ? AND up_to_server_seq <= ? AND format_version = ? "
                    + "ORDER BY up_to_server_seq DESC LIMIT 1",
                (rs, rowNum) -> new Row(rs.getBytes("state"), rs.getLong("up_to_server_seq")),
                documentId, throughServerSeq, DocumentSnapshot.CURRENT_FORMAT_VERSION);
            if (row == null) {
                return Optional.empty();
            }
            DocumentSnapshot snapshot = SnapshotCodec.decode(row.state());
            return snapshot == null
                ? Optional.empty()
                : Optional.of(new LoadedSnapshot(snapshot, row.upToServerSeq()));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /** Ile snapshotów ma dokument — do diagnostyki i do testu „skasuj cache, wyniki bez zmian". */
    public int count(UUID documentId) {
        Integer value = jdbc.queryForObject(
            "SELECT COUNT(*) FROM snapshots WHERE doc_id = ?", Integer.class, documentId);
        return value == null ? 0 : value;
    }

    public void deleteAll(UUID documentId) {
        jdbc.update("DELETE FROM snapshots WHERE doc_id = ?", documentId);
    }

    public record LoadedSnapshot(DocumentSnapshot snapshot, long upToServerSeq) {
    }
}
