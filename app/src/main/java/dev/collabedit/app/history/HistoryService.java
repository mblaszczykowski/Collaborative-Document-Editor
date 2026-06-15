package dev.collabedit.app.history;

import dev.collabedit.app.persist.OperationStore;
import dev.collabedit.app.persist.SnapshotStore;
import dev.collabedit.app.persist.StoredOperation;
import dev.collabedit.crdt.Op;
import dev.collabedit.crdt.RgaDocument;
import dev.collabedit.crdt.TreapBlockList;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Change history and travelling back in time (ADR-0003).
 *
 * <p>This whole feature is a free by-product of the decision in ADR-0003: if the operation log is
 * the source of truth and is append-only, then <b>the history already exists</b> — it only has to
 * be read. There is no separate versioning mechanism here, no separate table and no extra write on
 * every change.
 *
 * <p>The price is on the read side: "the state at moment T" needs a snapshot plus a replay of the
 * operation tail. Stepping through the timeline one entry at a time multiplies that cost by the
 * number of steps — to be measured in CP8.
 */
@Service
public class HistoryService {

    /**
     * The window over which operations are grouped into one history entry.
     *
     * <p>The log has the granularity of a single operation, which in practice means a few
     * characters. A timeline with an entry per keystroke would be useless, so consecutive
     * operations by the same author are glued into one entry. Two minutes is the typical length of
     * one "thought" while writing — a value chosen from intuition rather than data, and described
     * that way in the README.
     */
    private static final Duration GROUPING_WINDOW = Duration.ofMinutes(2);

    private final OperationStore operations;
    private final SnapshotStore snapshots;
    private final JdbcTemplate jdbc;

    public HistoryService(OperationStore operations, SnapshotStore snapshots, JdbcTemplate jdbc) {
        this.operations = operations;
        this.snapshots = snapshots;
        this.jdbc = jdbc;
    }

    /**
     * A timeline entry: a run of operations by one author within one time window.
     *
     * @param throughServerSeq the sequence number the log has to be replayed up to in order to see
     *                         this state
     */
    public record Version(
        long throughServerSeq,
        String userId,
        String displayName,
        String color,
        Instant at,
        int operationCount,
        int charactersInserted,
        int charactersDeleted
    ) {
    }

    /**
     * The document's timeline, newest first.
     *
     * <h2>The grouping happens in the database, and it did not use to</h2>
     * This method used to read <b>every operation row of the document, payload included</b>, with
     * no limit and no pagination, and group them in Java. The payload is the largest column in
     * the table, so opening the history panel on a document with 100 000 operations sent the
     * entire operation log across the wire in order to produce a few dozen summary lines.
     *
     * <p>The previous version half-saw this: it read the character counts out of the JSON lazily
     * rather than deserialising an operation per row, with a comment explaining that building
     * thousands of objects "would be wasteful on a path that only needs lengths". It optimised
     * the parsing and paid for the transfer, which is the larger of the two costs.
     *
     * <p>Two changes together fix it. The counts are now columns, written when the operation is
     * (V5), so nothing needs the payload at all; and the sessionisation runs as a window function,
     * so what crosses the wire is <b>one row per timeline entry</b> rather than one per keystroke.
     *
     * <h2>The grouping rule is unchanged</h2>
     * A new entry starts when the author changes <b>or</b> when more than {@link #GROUPING_WINDOW}
     * passes since the <i>previous operation</i> — not since the start of the group. That is what
     * the Java version did, and {@code LAG} over {@code server_seq} expresses exactly the same
     * thing, which is why this could move without changing any test.
     */
    public List<Version> timeline(UUID documentId) {
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
                       MAX(server_seq)   AS through_server_seq,
                       MIN(created_at)   AS started_at,
                       COUNT(*)          AS operation_count,
                       SUM(chars_inserted) AS inserted,
                       SUM(chars_deleted)  AS deleted
                  FROM grouped
                 GROUP BY group_id, user_id
            )
            SELECT s.through_server_seq,
                   s.user_id,
                   COALESCE(u.display_name, 'Unknown') AS display_name,
                   COALESCE(u.color, '#999999')         AS color,
                   s.started_at,
                   s.operation_count,
                   s.inserted,
                   s.deleted
              FROM sessions s
              LEFT JOIN users u ON u.id = s.user_id
             ORDER BY s.through_server_seq DESC
            """,
            (rs, rowNum) -> new Version(
                rs.getLong("through_server_seq"),
                rs.getString("user_id"),
                rs.getString("display_name"),
                rs.getString("color"),
                rs.getTimestamp("started_at").toInstant(),
                rs.getInt("operation_count"),
                rs.getInt("inserted"),
                rs.getInt("deleted")),
            documentId,
            GROUPING_WINDOW.toSeconds() + " seconds");
    }

    /**
     * The document's text at the moment the log reached the given sequence number.
     *
     * <p>The snapshot is purely a shortcut here: without it, replaying the whole log would give the
     * same result. That equivalence is tested explicitly, because without a test the cache would
     * quietly become the source of truth.
     */
    public String textAt(UUID documentId, long throughServerSeq) {
        var snapshot = snapshots.findLatestUpTo(documentId, throughServerSeq);

        RgaDocument document;
        long from;
        if (snapshot.isPresent()) {
            document = RgaDocument.restore(0, new TreapBlockList(), snapshot.get().snapshot());
            from = snapshot.get().upToServerSeq();
        } else {
            document = new RgaDocument(0, new TreapBlockList());
            from = 0;
        }

        for (StoredOperation stored : operations.loadRange(documentId, from, throughServerSeq)) {
            document.apply(stored.op());
        }
        return document.text();
    }

    /**
     * The operations needed to restore the state from a given moment.
     *
     * <p><b>A restore is a NEW CHANGE, not an undo of the log</b> (ADR-0003, point 6). Truncating
     * the log would destroy three things at once: auditability (who restored, and when), the
     * consistency of connected clients (their replicas would still hold the removed operations) and
     * the "append-only table" invariant that write idempotence rests on.
     *
     * <p>The operations are generated on a replica holding the <b>current</b> state of the document
     * and bring it to the target state. To connected clients it looks like an ordinary edit —
     * because it is one.
     *
     * @param replica a replica with the current state, on which we generate the change
     */
    public List<Op> restoreOperations(RgaDocument replica, String targetText) {
        String current = replica.text();
        if (current.equals(targetText)) {
            return List.of();
        }

        int prefix = 0;
        int maxPrefix = Math.min(current.length(), targetText.length());
        while (prefix < maxPrefix && current.charAt(prefix) == targetText.charAt(prefix)) {
            prefix++;
        }

        int suffix = 0;
        int maxSuffix = Math.min(current.length() - prefix, targetText.length() - prefix);
        while (suffix < maxSuffix
            && current.charAt(current.length() - 1 - suffix)
            == targetText.charAt(targetText.length() - 1 - suffix)) {
            suffix++;
        }

        // A common prefix and suffix instead of a full diff: a restore usually rolls back one
        // contiguous fragment, and an operation covering too wide a range is correct, merely less
        // economical. A full diff (Myers) would give smaller operations at the cost of complexity
        // that does not show in the result here.
        List<Op> generated = new ArrayList<>();
        int removeFrom = prefix;
        int removeTo = current.length() - suffix;
        if (removeTo > removeFrom) {
            generated.add(replica.delete(removeFrom, removeTo - removeFrom));
        }
        String insert = targetText.substring(prefix, targetText.length() - suffix);
        if (!insert.isEmpty()) {
            generated.add(replica.insert(prefix, insert));
        }
        return generated;
    }

}
