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

@Repository
public class SnapshotStore {
    private record Row(byte[] state, long upToServerSeq) {
    }

    private final JdbcTemplate jdbc;
    private final CollabEditProperties properties;

    public SnapshotStore(JdbcTemplate jdbc, CollabEditProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

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
