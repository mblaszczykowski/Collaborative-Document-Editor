package dev.collabedit.app.persist;

import dev.collabedit.app.config.CollabEditProperties;
import dev.collabedit.crdt.DocumentSnapshot;
import dev.collabedit.crdt.SnapshotCodec;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

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
    private final CollabEditProperties properties;

    public SnapshotStore(JdbcTemplate jdbc, CollabEditProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    /**
     * Writes a snapshot and drops the ones that are no longer worth keeping.
     *
     * <p>Both halves happen in one transaction, because a crash between them would leave the
     * table in the state this method exists to prevent.
     */
    @Transactional
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
        prune(documentId);
    }

    /**
     * Deletes all but the newest {@code snapshots-kept-per-document} snapshots of a document.
     *
     * <h2>Why this was missing, and why that mattered</h2>
     * Nothing removed a snapshot row until this method existed. One is written every 500
     * operations <b>or</b> after 30 s of quiet, and each holds a full compressed copy of the
     * document, so the table grew for the lifetime of the deployment — and grew fastest for the
     * documents people actually used.
     *
     * <p>The asymmetry is what makes it worth a comment rather than a fix. Tombstone garbage
     * collection <i>in memory</i> has a 345-line ADR, a causal-stability calculation, a rule for
     * excluding unresponsive replicas, and a checkpoint of its own. The cache <i>on disk</i> had
     * no policy at all. {@link dev.collabedit.crdt.DocumentSnapshot} is described throughout as a
     * disposable cache; the code had implemented "cache" and skipped "disposable".
     *
     * <h2>Why the newest is not enough</h2>
     * {@code HistoryService.textAt} asks for the newest snapshot <b>not later than</b> a past
     * {@code server_seq}, so a retention of one would make every history lookup replay the log
     * from zero. Keeping a few recent ones keeps that path cheap while still bounding the table
     * at a constant per document instead of at the number of edits ever made.
     */
    private void prune(UUID documentId) {
        int kept = properties.persistence().snapshotsKeptPerDocument();
        jdbc.update("""
            DELETE FROM snapshots
             WHERE doc_id = ?
               AND up_to_server_seq NOT IN (
                     SELECT up_to_server_seq FROM snapshots
                      WHERE doc_id = ?
                      ORDER BY up_to_server_seq DESC
                      LIMIT ?)
            """, documentId, documentId, kept);
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
