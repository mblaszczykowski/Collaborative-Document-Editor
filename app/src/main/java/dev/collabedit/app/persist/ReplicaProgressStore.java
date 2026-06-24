package dev.collabedit.app.persist;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import dev.collabedit.crdt.VersionVector;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * How far each replica has caught up — durably, and for the whole cluster.
 *
 * <p>The set of version vectors garbage collection is allowed to reason about. See
 * {@code V11__replica_progress.sql} for the two bugs this table exists to close; in short, the map
 * it replaces was per-instance and did not survive eviction, so the seven-day GC window was really
 * "until this instance forgets", and one instance would collect tombstones another instance's
 * clients still needed.
 *
 * <h2>The safe direction</h2>
 * Every failure here has to mean <b>GC waits</b>, never <b>GC proceeds</b>. A progress report that
 * does not get written leaves the previous row in place, which is a stale-but-older vector — it
 * constrains more, not less. A read that fails throws, the snapshot is skipped, and the sweep tries
 * again a second later. There is no path on which losing information here makes a tombstone look
 * collectable when it is not.
 */
@Component
public class ReplicaProgressStore {

    private static final Logger log = LoggerFactory.getLogger(ReplicaProgressStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Long>> VERSION_SHAPE = new TypeReference<>() { };

    private final JdbcTemplate jdbc;

    public ReplicaProgressStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Records where a replica has got to, and marks it connected.
     *
     * <p>An upsert rather than an insert: a client reports every fifteen seconds for as long as it
     * is open, and reconnecting has to clear a previous departure — the same replica identifier
     * coming back is the case the GC window exists for.
     *
     * <h2>A late report can move a vector backwards, and that is accepted</h2>
     * Reports from one replica can arrive out of order — two connections, a retransmission, or two
     * instances writing for the same client across a reconnect — and the row simply takes the last
     * one to arrive. So a vector can go backwards.
     *
     * <p>It is worth being precise about why that is tolerable rather than merely unlikely: a
     * backwards vector says a replica has seen <b>less</b> than it has, which makes the meet
     * smaller and GC more conservative. Every disagreement here therefore fails towards keeping
     * tombstones, never towards collecting one somebody still needs, and the next report fifteen
     * seconds later corrects it.
     *
     * <p>An earlier version of this method carried {@code WHERE reported_at <= EXCLUDED.reported_at}
     * with a comment claiming it kept the row monotonic. It did not, and could not: both sides of
     * that comparison are {@code now()} evaluated when the row is <b>written</b>, so the newer
     * write always has the newer timestamp whatever it carries, and the clause was always true.
     * Ordering by generation would need a counter the client does not send. Removed rather than
     * left as a guard that reads like one and filters nothing.
     */
    public void record(UUID documentId, int replicaId, VersionVector version) {
        String json = encode(version);
        jdbc.update("""
            INSERT INTO replica_progress (doc_id, replica_id, version, reported_at, departed_at)
                 VALUES (?, ?, ?::jsonb, now(), NULL)
            ON CONFLICT (doc_id, replica_id) DO UPDATE
                    SET version     = EXCLUDED.version,
                        reported_at = EXCLUDED.reported_at,
                        departed_at = NULL
            """, documentId, replicaId, json);
    }

    /**
     * Notes that a replica has gone, without forgetting it.
     *
     * <p>It keeps constraining collection until the GC window expires. Deleting the row here is the
     * bug the tombstone-collection design records having already made once: a departed client stopped constraining
     * anything, so the tombstones its unsynchronised work was anchored to became collectable as soon
     * as everyone still present moved past them.
     *
     * <p>Only sets the timestamp if it is not already set, so a second departure — a duplicate
     * DISCONNECT, which Spring's own documentation says can happen — does not extend the window.
     */
    public void markDeparted(UUID documentId, int replicaId) {
        jdbc.update("""
            UPDATE replica_progress
               SET departed_at = now()
             WHERE doc_id = ? AND replica_id = ? AND departed_at IS NULL
            """, documentId, replicaId);
    }

    /**
     * The version vectors that still constrain collection for this document.
     *
     * <p>Two rules, because two different failure modes are involved, and they are the same two
     * {@code DocumentReplica.stableVersion} applied when this lived in memory:
     * <ul>
     *   <li><b>connected but silent</b> — dropped after {@code unresponsiveTimeoutMs}, or one hung
     *       client blocks collection for the whole document forever;</li>
     *   <li><b>departed</b> — kept for {@code offlineGraceMillis}, the GC window, because silence
     *       from somebody who closed their laptop is exactly what that window is for.</li>
     * </ul>
     *
     * <p>Both thresholds are evaluated against the database's {@code now()} rather than the calling
     * instance's clock, so a skewed instance cannot shorten somebody else's grace period.
     */
    public List<VersionVector> liveFor(UUID documentId, long unresponsiveTimeoutMs,
                                       long offlineGraceMillis) {
        List<String> rows = jdbc.query("""
            SELECT version::text AS version
              FROM replica_progress
             WHERE doc_id = ?
               AND ((departed_at IS     NULL AND reported_at > now() - make_interval(secs => ?))
                 OR (departed_at IS NOT NULL AND departed_at > now() - make_interval(secs => ?)))
            """,
            (rs, rowNum) -> rs.getString("version"),
            documentId, unresponsiveTimeoutMs / 1000.0, offlineGraceMillis / 1000.0);

        List<VersionVector> live = new ArrayList<>(rows.size());
        for (String row : rows) {
            VersionVector decoded = decode(row);
            if (decoded != null) {
                live.add(decoded);
            }
        }
        return live;
    }

    /**
     * Drops rows past the GC window.
     *
     * <p>Bounded growth, for the same reason every other map in this system has a bound: without it
     * the table keeps one row per replica identifier that has ever opened a document, and replica
     * identifiers are never reused. Safe to do at any time — a row past the window has already
     * stopped being counted by {@link #liveFor}, so removing it changes no decision.
     *
     * <p><b>Across every document, not one.</b> It used to take a document id and run from the
     * snapshot path, which meant it only ever ran for documents somebody was still editing — and
     * those are exactly the ones whose rows are being refreshed anyway. A document edited once and
     * abandoned took its final snapshot and was never swept again, so its rows outlived the window
     * indefinitely. The in-memory map this replaced got away with the same trigger because it died
     * with the replica after five minutes of quiet; a table has no such backstop.
     *
     * @return how many rows were removed
     */
    public int pruneExpired(long offlineGraceMillis) {
        return jdbc.update("""
            DELETE FROM replica_progress
             WHERE (departed_at IS NOT NULL AND departed_at <= now() - make_interval(secs => ?))
                OR (departed_at IS     NULL AND reported_at <= now() - make_interval(secs => ?))
            """, offlineGraceMillis / 1000.0, abandonedAfterSeconds(offlineGraceMillis));
    }

    /**
     * When a row that never departed becomes collectable.
     *
     * <h2>Why the second clause exists at all</h2>
     * {@code departed_at} is set by the session teardown, and a teardown does not always happen: an
     * instance killed with {@code -9}, an out-of-memory container, a rolling deploy, or a failure to
     * write the departure. Those rows keep {@code departed_at IS NULL} for ever.
     *
     * <p>{@link #liveFor} already stops counting them after {@code unresponsiveTimeoutMs}, so they
     * never hold collection back — but nothing deleted them, and one crashed instance then leaks one
     * row per open tab, permanently. That contradicted this table's own promise of a bound, which is
     * a claim worth either honouring or withdrawing.
     *
     * <p><b>Twice the GC window</b>, not the window itself. A live replica refreshes its row every
     * fifteen seconds, so a row untouched for two windows belongs to something that is not coming
     * back — and the doubling means no client can lose its protection early merely by being slow.
     * This is a leak bound rather than a policy; the policy is the window.
     */
    private static double abandonedAfterSeconds(long offlineGraceMillis) {
        return (offlineGraceMillis / 1000.0) * 2;
    }

    private static String encode(VersionVector version) {
        try {
            return MAPPER.writeValueAsString(version.asMap());
        } catch (JsonProcessingException e) {
            // A map of numbers to numbers. Unreachable, and loud rather than a silent empty vector:
            // an empty one would be a replica that has seen nothing, which constrains GC completely.
            throw new IllegalStateException("a version vector could not be serialised", e);
        }
    }

    /**
     * @return the vector, or {@code null} when the row cannot be read — logged and skipped rather
     *     than thrown, because one unreadable row must not stop the snapshot of a whole document.
     *     Skipping is the direction that collects MORE, so it is also the one that has to be rare;
     *     the write side is a serialised map of numbers, so this is a corruption path rather than a
     *     format-drift path.
     */
    private static VersionVector decode(String json) {
        try {
            Map<Integer, Long> parsed = new java.util.HashMap<>();
            MAPPER.readValue(json, VERSION_SHAPE)
                .forEach((replicaId, lamport) -> parsed.put(Integer.valueOf(replicaId), lamport));
            return VersionVector.of(parsed);
        } catch (JsonProcessingException | NumberFormatException e) {
            log.warn("Unreadable replica progress row, ignored for causal stability: {}",
                e.toString());
            return null;
        }
    }
}
