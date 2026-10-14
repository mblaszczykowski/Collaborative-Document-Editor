package dev.collabedit.app.persist;

import dev.collabedit.crdt.Id;
import dev.collabedit.crdt.Op;
import dev.collabedit.crdt.OpCodec;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Optional;
import java.util.function.Consumer;
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

        Map<String, Op> alreadyStored = existingOperations(documentId, operations);
        Map<String, String> claims = claimedHashes(documentId, operations);

        List<Object[]> rows = new ArrayList<>(operations.size());
        List<StoredOperation> appended = new ArrayList<>(operations.size());
        Instant now = Instant.now();
        long seq = nextSeq;
        for (PendingOperation pending : operations) {
            Op op = pending.op();
            Op previous = alreadyStored.putIfAbsent(key(op), op);
            if (previous != null) {
                requireSamePayload(op, previous);
                continue;
            }
            String claimed = claims.get(key(op));
            if (claimed != null && !claimed.equals(
                fingerprint(op))) {
                throw new dev.collabedit.app.domain.DocumentService.ReplicaIdConflictException(
                    "operation identity has a different payload");
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
        jdbc.batchUpdate("DELETE FROM operation_identity_claims "
            + "WHERE doc_id = ? AND replica_id = ? AND lamport = ?", appended.stream()
                .map(stored -> new Object[] {documentId, stored.op().id().replicaId(),
                    stored.op().id().lamport()}).toList());

        return appended;
    }

    /** Serialize identity claims across instances before any speculative model mutation. */
    @Transactional
    public Optional<StoredOperation> claim(UUID documentId, Op op) {
        jdbc.queryForObject("SELECT next_server_seq FROM documents WHERE id = ? FOR UPDATE",
            Long.class, documentId);
        Optional<StoredOperation> stored = find(documentId, op.id());
        if (stored.isPresent()) {
            requireSamePayload(op, stored.get().op());
            return stored;
        }
        String hash = fingerprint(op);
        String claimed = jdbc.queryForObject(
            "INSERT INTO operation_identity_claims(doc_id, replica_id, lamport, payload_hash) "
                + "VALUES (?, ?, ?, ?) ON CONFLICT (doc_id, replica_id, lamport) "
                + "DO UPDATE SET payload_hash = operation_identity_claims.payload_hash "
                + "RETURNING payload_hash", String.class, documentId, op.id().replicaId(),
            op.id().lamport(), hash);
        if (!hash.equals(claimed)) {
            throw new dev.collabedit.app.domain.DocumentService.ReplicaIdConflictException(
                "operation identity has a different payload");
        }
        return Optional.empty();
    }

    private static String fingerprint(Op op) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(
                OpCodec.encode(op).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void requireSamePayload(Op received, Op stored) {
        if (!received.equals(stored)) {
            throw new dev.collabedit.app.domain.DocumentService.ReplicaIdConflictException(
                "operation identity has a different payload");
        }
    }

    /** Commit a generated command and its metadata in the same database transaction. */
    @Transactional
    public List<StoredOperation> commitGenerated(UUID documentId,
                                                 List<PendingOperation> pending,
                                                 Runnable metadata) {
        List<StoredOperation> stored = append(documentId, pending);
        metadata.run();
        return stored;
    }

    public Optional<StoredOperation> find(UUID documentId, Id id) {
        return jdbc.query("SELECT payload, server_seq, user_id, created_at FROM operations "
            + "WHERE doc_id = ? AND replica_id = ? AND lamport = ?", STORED,
            documentId, id.replicaId(), id.lamport()).stream().findFirst();
    }

    public int allocateServerReplicaId() {
        Long id = jdbc.queryForObject("SELECT nextval('server_replica_id_seq')", Long.class);
        if (id == null || id > Integer.MAX_VALUE) {
            throw new IllegalStateException("server replica identity space exhausted");
        }
        return -id.intValue();
    }

    private Map<String, Op> existingOperations(UUID documentId, List<PendingOperation> pending) {
        Map<String, Op> existing = new java.util.HashMap<>();
        queryIdentities("operations", "payload", documentId, pending,
            (key, value) -> existing.put(key, OpCodec.decode(value)));
        return existing;
    }

    private Map<String, String> claimedHashes(UUID documentId, List<PendingOperation> pending) {
        Map<String, String> existing = new java.util.HashMap<>();
        queryIdentities("operation_identity_claims", "payload_hash", documentId, pending,
            existing::put);
        return existing;
    }

    private void queryIdentities(String table, String valueColumn, UUID documentId,
                                 List<PendingOperation> pending,
                                 java.util.function.BiConsumer<String, String> consumer) {
        if (pending.isEmpty()) {
            return;
        }
        Integer[] replicas = new Integer[pending.size()];
        Long[] lamports = new Long[pending.size()];
        for (int i = 0; i < pending.size(); i++) {
            Id id = pending.get(i).op().id();
            replicas[i] = id.replicaId();
            lamports[i] = id.lamport();
        }
        // Table/column names are fixed private callers, never request values.
        jdbc.query(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                "SELECT replica_id, lamport, " + valueColumn + " FROM " + table
                    + " WHERE doc_id = ? AND (replica_id, lamport) "
                    + "IN (SELECT * FROM unnest(?, ?))");
            statement.setObject(1, documentId);
            statement.setArray(2, connection.createArrayOf("int4", replicas));
            statement.setArray(3, connection.createArrayOf("int8", lamports));
            return statement;
        }, (org.springframework.jdbc.core.RowCallbackHandler) row -> consumer.accept(
            row.getInt("replica_id") + ":" + row.getLong("lamport"), row.getString(valueColumn)));
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

    public void forEachInRange(UUID documentId, long afterServerSeq, long throughServerSeq,
                               Consumer<StoredOperation> consumer) {
        long cursor = afterServerSeq;
        while (cursor < throughServerSeq) {
            List<StoredOperation> batch = jdbc.query(
                "SELECT payload, server_seq, user_id, created_at FROM operations "
                    + "WHERE doc_id = ? AND server_seq > ? AND server_seq <= ? "
                    + "ORDER BY server_seq LIMIT 1000", STORED, documentId, cursor,
                throughServerSeq);
            if (batch.isEmpty()) {
                break;
            }
            batch.forEach(consumer);
            cursor = batch.getLast().serverSeq();
        }
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
