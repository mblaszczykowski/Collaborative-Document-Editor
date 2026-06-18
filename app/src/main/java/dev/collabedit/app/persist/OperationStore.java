package dev.collabedit.app.persist;

import dev.collabedit.crdt.Id;
import dev.collabedit.crdt.Op;
import dev.collabedit.crdt.OpCodec;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The operation log — the <b>source of truth</b> of the whole system (ADR-0003).
 *
 * <p>The table is append-only. There is no update or delete method here, and that is not an
 * oversight: restoring an old version of a document is recorded as new operations, because
 * truncating the log would destroy auditability and diverge the clients currently connected.
 */
@Repository
public class OperationStore {

    private final JdbcTemplate jdbc;

    public OperationStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Appends a batch of operations, assigning them consecutive {@code server_seq} values.
     *
     * <h2>Why a row lock on the document</h2>
     * ADR-0004 bases detection of lost Redis messages on the <b>continuity</b> of
     * {@code server_seq} — a gap in the numbering means "I am missing operations". A Postgres
     * sequence does not give that property (it leaves gaps when a transaction rolls back), so the
     * counter lives in the {@code documents.next_server_seq} column and is allocated under a row
     * lock.
     *
     * <h2>Why duplicates are filtered out BEFORE numbers are assigned</h2>
     * Relying on {@code ON CONFLICT DO NOTHING} would reject part of the batch <i>after</i> numbers
     * had been reserved for it — leaving exactly the kind of gap ADR-0004 forbids. The filtering is
     * correct despite concurrency, because the document's row lock serialises every writer to that
     * document, including writers on other instances.
     *
     * <p>The cost is serialised writes <b>within a single document</b>. That is acceptable, because
     * writes are batched every 50 ms anyway — the lock is taken once per batch, not once per
     * keystroke.
     *
     * <p><b>Verified in CP10:</b> with 50 clients typing simultaneously into one document the lock
     * is not the bottleneck — operation intake throughput reaches 116k/s, and convergence is
     * achieved in 239 ms on a 100k-character document. Since CP10 the batch also has an upper size
     * bound ({@code op-batch-max-size}), so the lock hold time is bounded regardless of typing rate.
     *
     * <p>What the measurement does NOT cover: the clients and the server run in one process, so
     * with a real network RTT the batches would be larger and the lock held longer. The bound is
     * therefore <b>no lower</b> than measured, but not necessarily equal to it.
     *
     * @return the operations actually appended, with their assigned sequence numbers; an empty list
     *         when the whole batch turned out to be a duplicate
     */
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

    /**
     * A query with <b>fixed text</b> — the batch's identifiers arrive as two arrays.
     *
     * <p>The previous version assembled a list of tuples {@code IN ((?,?),(?,?),…)} with a
     * StringBuilder. It worked and was safe (only question marks reached the text; the values went
     * as parameters), but it had three flaws, two of them measurable:
     *
     * <ol>
     *   <li><b>A different query for every batch size.</b> A batch holds one to a hundred
     *       operations, so up to a hundred distinct SQL texts were produced — each planned afresh,
     *       with no chance of plan reuse.</li>
     *   <li><b>A worse plan.</b> Postgres built a {@code BitmapOr} with a separate branch per
     *       tuple. The {@code unnest} version gets an {@code Index Only Scan} on the primary key:
     *       one lookup per pair, with no heap access.</li>
     *   <li><b>Concatenated SQL as a pattern.</b> This was the only place in the project where a
     *       query was built by concatenation — and a pattern like that gets copied later to
     *       somewhere it is no longer safe.</li>
     * </ol>
     *
     * <p>Measured on a document with 50k operations, at a batch of 100 operations:
     * planning <b>3.27 ms -> 1.03 ms</b>, execution <b>1.54 ms -> 0.30 ms</b>.
     */
    private static final String EXISTING_KEYS_SQL =
        "SELECT replica_id, lamport FROM operations "
            + "WHERE doc_id = ? AND (replica_id, lamport) IN (SELECT * FROM unnest(?, ?))";

    /** The batch's operation keys already in the database — duplicate filtering in one query. */
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

    /**
     * The single-character operation type code in the {@code operations.op_type} column.
     *
     * <p><b>An exhaustive {@code switch}, not a ternary {@code instanceof}.</b> The previous
     * version read {@code op instanceof Op.Insert ? "i" : "d"} and was correct exactly as long as
     * {@link Op} had two variants. {@code Op} is a sealed interface, so the compiler points at
     * every exhaustive {@code switch} when a variant is added — but it will not point at a ternary
     * {@code instanceof}, because that keeps compiling and silently records the new operation type
     * AS A DELETE.
     *
     * <p>That would be corruption of the source of truth, visible only when rebuilding state from
     * the log — that is, after a failure, at the worst possible moment.
     */
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

    /**
     * How many characters this operation adds.
     *
     * <p>Recorded at write time rather than derived on read. The timeline used to fetch every
     * {@code payload} in the document to compute exactly this, which meant sending the whole
     * operation log — the largest column in the table — across the wire to produce two integers
     * per group. The write path already has the number in hand.
     *
     * <p>Not a cache and cannot drift: the log is append-only, so a row's counts are as immutable
     * as its payload.
     */
    private static int charsInserted(Op op) {
        return op instanceof Op.Insert insert ? insert.text().length() : 0;
    }

    /** How many characters this operation removes; see {@link #charsInserted}. */
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

    /**
     * The operations with a {@code server_seq} greater than the one given — the client's
     * resynchronisation cursor after reconnecting (CP6), and the repair of a replica after a lost
     * Redis message (ADR-0004).
     */
    public List<StoredOperation> loadAfter(UUID documentId, long afterServerSeq) {
        return jdbc.query(
            "SELECT payload, server_seq, user_id, created_at FROM operations "
                + "WHERE doc_id = ? AND server_seq > ? ORDER BY server_seq",
            (rs, rowNum) -> new StoredOperation(
                OpCodec.decode(rs.getString("payload")),
                rs.getLong("server_seq"),
                rs.getString("user_id"),
                rs.getTimestamp("created_at").toInstant()),
            documentId, afterServerSeq);
    }

    /**
     * The operations in the range {@code (from, to]} — needed when rebuilding the state "at moment
     * T" on top of a snapshot (ADR-0003, CP5).
     */
    public List<StoredOperation> loadRange(UUID documentId, long afterServerSeq, long throughServerSeq) {
        return jdbc.query(
            "SELECT payload, server_seq, user_id, created_at FROM operations "
                + "WHERE doc_id = ? AND server_seq > ? AND server_seq <= ? ORDER BY server_seq",
            (rs, rowNum) -> new StoredOperation(
                OpCodec.decode(rs.getString("payload")),
                rs.getLong("server_seq"),
                rs.getString("user_id"),
                rs.getTimestamp("created_at").toInstant()),
            documentId, afterServerSeq, throughServerSeq);
    }

    /** The highest stored {@code server_seq}; 0 when the document has no operations yet. */
    public long maxServerSeq(UUID documentId) {
        Long value = jdbc.queryForObject(
            "SELECT COALESCE(MAX(server_seq), 0) FROM operations WHERE doc_id = ?",
            Long.class, documentId);
        return value == null ? 0L : value;
    }

    // maxServerSeqAt(documentId, moment) used to live here — "the highest server_seq stored no
    // later than the given moment (CP5)". Nothing ever called it, in main or in tests.
    //
    // Removed rather than indexed. It was also the only query in this class with no supporting
    // index: filtering on created_at is not served by operations_by_seq (doc_id, server_seq), so
    // it scanned every row of the document. Adding an index for a caller that does not exist
    // would have been paying storage and write cost for a query nobody runs.
    //
    // The feature it was written for does exist, and takes a different route: history addresses
    // states by server_seq, not by timestamp (HistoryService.textAt), because a sequence number
    // identifies a state exactly and a timestamp identifies whatever happened to be written by
    // then. This method is a leftover from the shape that idea had before ADR-0003 settled it.
}
