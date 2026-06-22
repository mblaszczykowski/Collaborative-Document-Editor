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

/**
 * The operation log — the <b>source of truth</b> of the whole system.
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
     * The cluster design bases detection of lost Redis messages on the <b>continuity</b> of
     * {@code server_seq} — a gap in the numbering means "I am missing operations". A Postgres
     * sequence does not give that property (it leaves gaps when a transaction rolls back), so the
     * counter lives in the {@code documents.next_server_seq} column and is allocated under a row
     * lock.
     *
     * <h2>Why duplicates are filtered out BEFORE numbers are assigned</h2>
     * Relying on {@code ON CONFLICT DO NOTHING} would reject part of the batch <i>after</i> numbers
     * had been reserved for it — leaving exactly the kind of gap the cluster design forbids. The filtering is
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

    private static final RowMapper<StoredOperation> STORED =
        (rs, rowNum) -> new StoredOperation(
            OpCodec.decode(rs.getString("payload")),
            rs.getLong("server_seq"),
            rs.getString("user_id"),
            rs.getTimestamp("created_at").toInstant());

    /**
     * Every operation above a cursor — <b>unbounded, and only for loading a replica</b>.
     *
     * <p>The one caller is {@code DocumentRegistry.load}, which replays the tail of the log on top
     * of a snapshot. That read has to be complete by definition: a replica built from part of its
     * own log is a replica holding the wrong document, and there is no later mechanism that would
     * notice — {@code appliedServerSeq} would already claim the operations it never applied.
     *
     * <p>Its size is bounded by something else, and that is why this is acceptable where the client
     * path is not: the snapshot retention policy keeps a snapshot every 500 operations or 30
     * seconds of quiet, so the tail replayed here is short by construction. The client's cursor,
     * by contrast, is whatever the client says it is.
     */
    public List<StoredOperation> loadAfter(UUID documentId, long afterServerSeq) {
        return jdbc.query(
            "SELECT payload, server_seq, user_id, created_at FROM operations "
                + "WHERE doc_id = ? AND server_seq > ? ORDER BY server_seq",
            STORED, documentId, afterServerSeq);
    }

    /**
     * The same, capped — the client's resynchronisation cursor after reconnecting (CP6) and the
     * repair of a replica after a lost Redis message.
     *
     * <h2>Why the cap is not optional on those two paths</h2>
     * {@code sinceServerSeq} arrives from the client and nothing validated it. Every row above it
     * was materialised into a decoded {@code Op}, and the chunker then re-encoded each one into a
     * {@code JsonNode} and a {@code String} — all of it live at once. That is an attack surface, and
     * it is also the ORDINARY recovery path: the shipped client sends 0 whenever it detects a gap,
     * so a 250 000-operation document paid the whole log for a routine reconnect.
     *
     * <p>The cap does not silently truncate anywhere. The sync path reads one more row than it will
     * send and answers {@code SNAPSHOT_REQUIRED} when that row exists; the repair path loops until
     * the log is drained. Both are spelled out at their call sites, because "what happens at the
     * boundary" is the part of a limit that actually matters.
     */
    public List<StoredOperation> loadAfter(UUID documentId, long afterServerSeq, int limit) {
        return jdbc.query(
            "SELECT payload, server_seq, user_id, created_at FROM operations "
                + "WHERE doc_id = ? AND server_seq > ? ORDER BY server_seq LIMIT ?",
            STORED, documentId, afterServerSeq, limit);
    }


    /**
     * The operations in the range {@code (from, to]} — needed when rebuilding the state "at moment
     * T" on top of a snapshot (CP5).
     */
    public List<StoredOperation> loadRange(UUID documentId, long afterServerSeq, long throughServerSeq) {
        return jdbc.query(
            "SELECT payload, server_seq, user_id, created_at FROM operations "
                + "WHERE doc_id = ? AND server_seq > ? AND server_seq <= ? ORDER BY server_seq",
            STORED, documentId, afterServerSeq, throughServerSeq);
    }

    /**
     * One entry of a document's timeline: a run of operations by one author within one time window.
     *
     * <p>The display name and colour are joined in rather than looked up per row, because the
     * alternative is one query per timeline entry for a value that exists to be printed.
     */
    public record TimelineEntry(long throughServerSeq, String userId, String displayName,
                                String color, Instant startedAt, int operationCount,
                                int charactersInserted, int charactersDeleted) {
    }

    /**
     * A document's timeline, newest first.
     *
     * <h2>Why the query is here and not in HistoryService</h2>
     * This class's javadoc calls itself the sole access path to the {@code operations} table, and
     * {@code HistoryService} was holding a raw {@code JdbcTemplate} and issuing a 45-line query
     * against that table anyway. A "sole access path" with a second door in it is a comment rather
     * than a boundary — and this particular door is the one that reads the table's shape most
     * closely (it depends on {@code server_seq} ordering and on the {@code chars_inserted} /
     * {@code chars_deleted} columns V6 added), so it is the one most likely to break when the
     * table changes.
     *
     * <p>The {@code users} join stays with it. Splitting the query to keep this class purely about
     * one table would trade a boundary for an N+1, which is the worse of the two.
     *
     * <h2>The grouping rule</h2>
     * A new entry starts when the author changes <b>or</b> when more than {@code windowSeconds}
     * passes since the <i>previous operation</i> — not since the start of the group. {@code LAG}
     * over {@code server_seq} expresses exactly that.
     *
     * <p>Sessionised in the database rather than in Java, and that is not a preference. The Java
     * version read every operation row of the document, PAYLOAD INCLUDED — the largest column in
     * the table — to produce a few dozen summary lines. What crosses the wire now is one row per
     * timeline entry.
     *
     * @param limit the upper bound on entries returned. The history panel shows a timeline, not an
     * archive — the same reasoning, and the same number, as {@code LIST_LIMIT} in the
     * document list. Without it this had no bound at all on a table that grows for the
     * life of the document
     */
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
    // then. This method is a leftover from the shape that idea had before the operation-log decision settled it.
}
