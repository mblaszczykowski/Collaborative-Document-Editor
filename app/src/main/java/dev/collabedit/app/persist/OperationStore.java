package dev.collabedit.app.persist;

import dev.collabedit.crdt.Id;
import dev.collabedit.crdt.Op;
import dev.collabedit.crdt.OpCodec;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class OperationStore {
    private final JdbcTemplate jdbc;

    public OperationStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public List<StoredOperation> append(UUID documentId, List<PendingOperation> operations) {
        Long nextSeq = jdbc.queryForObject(
            "SELECT next_server_seq FROM documents WHERE id = ? FOR UPDATE",
            Long.class, documentId);
        if (nextSeq == null) {
            throw new IllegalArgumentException("no such document " + documentId);
        }

        Set<String> alreadyStored = existingKeys(documentId, operations);

        List<Object[]> rows = new ArrayList<>(operations.size());
        List<StoredOperation> appended = new ArrayList<>(operations.size());
        Instant now = Instant.now();
        long seq = nextSeq;
        for (PendingOperation pending : operations) {
            Op op = pending.op();
            if (!alreadyStored.add(key(op))) {
                continue;
            }
            rows.add(new Object[] {
                documentId,
                op.id().replicaId(),
                op.id().lamport(),
                seq,
                opTypeCode(op),
                OpCodec.encode(op),
                pending.userId(),
                charsInserted(op),
                charsDeleted(op)
            });
            appended.add(new StoredOperation(op, seq, pending.userId(), now));
            seq++;
        }

        if (rows.isEmpty()) {
            return List.of();
        }

        jdbc.batchUpdate(
            "INSERT INTO operations "
                + "(doc_id, replica_id, lamport, server_seq, op_type, payload, user_id, "
                + " chars_inserted, chars_deleted) "
                + "VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)",
            rows);
        jdbc.update("UPDATE documents SET next_server_seq = ?, updated_at = now() WHERE id = ?",
            seq, documentId);

        return appended;
    }

    private static final String EXISTING_KEYS_SQL =
        "SELECT replica_id, lamport FROM operations "
            + "WHERE doc_id = ? AND (replica_id, lamport) IN (SELECT * FROM unnest(?, ?))";

    private Set<String> existingKeys(UUID documentId, List<PendingOperation> operations) {
        if (operations.isEmpty()) {
            return new HashSet<>();
        }
        Integer[] replicas = new Integer[operations.size()];
        Long[] lamports = new Long[operations.size()];
        for (int i = 0; i < operations.size(); i++) {
            Id id = operations.get(i).op().id();
            replicas[i] = id.replicaId();
            lamports[i] = id.lamport();
        }

        List<String> found = jdbc.query(
            connection -> {
                PreparedStatement statement = connection.prepareStatement(EXISTING_KEYS_SQL);
                statement.setObject(1, documentId);
                statement.setArray(2, connection.createArrayOf("int4", replicas));
                statement.setArray(3, connection.createArrayOf("int8", lamports));
                return statement;
            },
            (rs, rowNum) -> rs.getInt("replica_id") + ":" + rs.getLong("lamport"));
        return new HashSet<>(found);
    }

    private static String opTypeCode(Op op) {
        return switch (op) {
            case Op.Insert ignored -> "i";
            case Op.Delete ignored -> "d";
            case Op.AddMark ignored -> "m";
            case Op.RemoveMark ignored -> "x";
        };
    }

    private static String key(Op op) {
        return op.id().replicaId() + ":" + op.id().lamport();
    }

    private static int charsInserted(Op op) {
        return op instanceof Op.Insert insert ? insert.text().length() : 0;
    }

    private static int charsDeleted(Op op) {
        if (!(op instanceof Op.Delete delete)) {
            return 0;
        }
        int total = 0;
        for (Op.Range range : delete.targets()) {
            total += range.length();
        }
        return total;
    }

    private static final RowMapper<StoredOperation> STORED =
        (rs, rowNum) -> new StoredOperation(
            OpCodec.decode(rs.getString("payload")),
            rs.getLong("server_seq"),
            rs.getString("user_id"),
            rs.getTimestamp("created_at").toInstant());

    public List<StoredOperation> loadAfter(UUID documentId, long afterServerSeq) {
        return jdbc.query(
            "SELECT payload, server_seq, user_id, created_at FROM operations "
                + "WHERE doc_id = ? AND server_seq > ? ORDER BY server_seq",
            STORED, documentId, afterServerSeq);
    }

    public List<StoredOperation> loadAfter(UUID documentId, long afterServerSeq, int limit) {
        return jdbc.query(
            "SELECT payload, server_seq, user_id, created_at FROM operations "
                + "WHERE doc_id = ? AND server_seq > ? ORDER BY server_seq LIMIT ?",
            STORED, documentId, afterServerSeq, limit);
    }

    public List<StoredOperation> loadRange(UUID documentId, long afterServerSeq, long throughServerSeq) {
        return jdbc.query(
            "SELECT payload, server_seq, user_id, created_at FROM operations "
                + "WHERE doc_id = ? AND server_seq > ? AND server_seq <= ? ORDER BY server_seq",
            STORED, documentId, afterServerSeq, throughServerSeq);
    }

    public record TimelineEntry(long throughServerSeq, String userId, String displayName,
                                String color, Instant startedAt, int operationCount,
                                int charactersInserted, int charactersDeleted) {
    }

    public List<TimelineEntry> timelineOf(UUID documentId, long windowSeconds, int limit) {
        return jdbc.query(
            """
            WITH ops AS (
                SELECT o.server_seq,
                       o.created_at,
                       COALESCE(o.user_id, '?') AS user_id,
                       o.chars_inserted,
                       o.chars_deleted
                  FROM operations o
                 WHERE o.doc_id = ?
            ),
            boundaries AS (
                SELECT *,
                       CASE
                         WHEN LAG(user_id) OVER ordered IS DISTINCT FROM user_id
                           OR created_at - LAG(created_at) OVER ordered > ?::interval
                         THEN 1 ELSE 0
                       END AS starts_group
                  FROM ops
                WINDOW ordered AS (ORDER BY server_seq)
            ),
            grouped AS (
                SELECT *,
                       SUM(starts_group) OVER (ORDER BY server_seq) AS group_id
                  FROM boundaries
            ),
            sessions AS (
                SELECT group_id,
                       user_id,
                       MAX(server_seq) AS through_server_seq,
                       MIN(created_at) AS started_at,
                       COUNT(*) AS operation_count,
                       SUM(chars_inserted) AS inserted,
                       SUM(chars_deleted) AS deleted
                  FROM grouped
                 GROUP BY group_id, user_id
            )
            SELECT s.through_server_seq,
                   s.user_id,
                   COALESCE(u.display_name, 'Unknown') AS display_name,
                   COALESCE(u.color, '#999999') AS color,
                   s.started_at,
                   s.operation_count,
                   s.inserted,
                   s.deleted
              FROM sessions s
              LEFT JOIN users u ON u.id = s.user_id
             ORDER BY s.through_server_seq DESC
             LIMIT ?
            """,
            (rs, rowNum) -> new TimelineEntry(
                rs.getLong("through_server_seq"),
                rs.getString("user_id"),
                rs.getString("display_name"),
                rs.getString("color"),
                rs.getTimestamp("started_at").toInstant(),
                rs.getInt("operation_count"),
                rs.getInt("inserted"),
                rs.getInt("deleted")),
            documentId, windowSeconds + " seconds", limit);
    }

    public long maxServerSeq(UUID documentId) {
        Long value = jdbc.queryForObject(
            "SELECT COALESCE(MAX(server_seq), 0) FROM operations WHERE doc_id = ?",
            Long.class, documentId);
        return value == null ? 0L : value;
    }
}
