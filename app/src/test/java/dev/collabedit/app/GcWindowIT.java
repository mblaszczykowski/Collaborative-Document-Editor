package dev.collabedit.app;

import static org.assertj.core.api.Assertions.assertThat;

import dev.collabedit.app.domain.DocumentReplica;
import dev.collabedit.app.persist.ReplicaProgressStore;
import dev.collabedit.crdt.RgaDocument;
import dev.collabedit.crdt.TreapBlockList;
import dev.collabedit.crdt.VersionVector;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The GC window, which was configured for five checkpoints and read by nothing.
 *
 * <h2>What was wrong</h2>
 * {@code collabedit.gc.window-days: 7} existed in {@code application.yml} with a paragraph of
 * justification, and in {@code CollabEditProperties} as a field. <b>No code read it.</b>
 *
 * <p>The tombstone-collection design defines the replicas that hold GC back as those
 * <i>"connected now, or disconnected for less than the GC window"</i>, and devotes a section to the
 * client that returns after it. The implementation had the first clause and, on disconnect, deleted
 * the replica's entry outright — so a departed client held nothing back at all.
 *
 * <h2>Why this is an integration test now, and was a unit test before</h2>
 * The two rules below used to be evaluated in Java against a {@code Map} on {@code DocumentReplica},
 * and that map had two defects the unit test could not see because it never left one process: it
 * was <b>per instance</b>, and it did <b>not survive eviction</b>. So the seven-day window was
 * really "until this instance forgets", and one instance collected tombstones another instance's
 * clients still needed.
 *
 * <p>Both are properties of WHERE the set of replicas comes from, so the answer was to move it into
 * {@code replica_progress} — durable, shared, and expiring on the database's clock rather than on
 * whichever instance happens to run the sweep. The rules moved into the query with it, and a test
 * that mocks the database would now be checking that the mock agrees with itself. The scenarios and
 * the reasoning are the ones the unit test pinned; only the layer changed.
 *
 * <h2>Why the two timeouts are not one</h2>
 * They answer different questions and this test pins both. Silence from a client that is
 * <b>still connected</b> is evidence something is wrong with it, so after
 * {@code unresponsive-replica-timeout-ms} it stops counting — otherwise one hung tab blocks
 * compaction for everyone. Silence from a client that has <b>disconnected</b> is exactly what is
 * expected, and is the case the window exists for.
 */
@DisplayName("A departed replica holds GC back for the GC window, not for zero")
class GcWindowIT extends IntegrationTestBase {

    private static final long UNRESPONSIVE_MS = Duration.ofSeconds(60).toMillis();
    private static final long WINDOW_MS = Duration.ofDays(7).toMillis();

    private static final int DEPARTED = 42;
    private static final int PRESENT = 43;

    @Autowired
    ReplicaProgressStore progress;

    @Autowired
    JdbcTemplate jdbc;

    /**
     * A document row, because {@code replica_progress.doc_id} is a foreign key — which is itself
     * part of the design: progress for a deleted document goes with it, by cascade.
     */
    private UUID newDocument() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO documents (id, title, visibility) VALUES (?, ?, 'link')", id, "GC window");
        return id;
    }

    /**
     * A replica whose own state is well ahead of the clients.
     *
     * <p>The server's document is included in the meet as a replica in its own right, so it has
     * to know about the replica ids used below — otherwise its own vector contributes nothing for
     * them and the meet is decided by the clients alone, which would make the assertions pass for
     * the wrong reason.
     */
    private static DocumentReplica replicaWithHistory(UUID documentId) {
        long now = System.currentTimeMillis();
        RgaDocument document = new RgaDocument(1, new TreapBlockList());
        DocumentReplica replica = new DocumentReplica(documentId, document, 0, now);
        RgaDocument departedClient = new RgaDocument(DEPARTED, new TreapBlockList());
        RgaDocument presentClient = new RgaDocument(PRESENT, new TreapBlockList());
        replica.merge(departedClient.insert(0, "aaaaa"), "u1", null, now);
        replica.merge(presentClient.insert(0, "bbbbb"), "u2", null, now);
        return replica;
    }

    /**
     * Moves a row's clocks into the past.
     *
     * <p>The store writes {@code now()} on purpose — the whole point of the change is that these
     * timestamps come from one clock rather than from whichever instance wrote them — so a test
     * about "an hour later" has to move the row rather than the reader's idea of the time. It still
     * exercises the real predicate, which is what these tests are about.
     */
    private void age(UUID documentId, int replicaId, Duration by) {
        jdbc.update("""
            UPDATE replica_progress
               SET reported_at = reported_at - make_interval(secs => ?),
                   departed_at = departed_at - make_interval(secs => ?)
             WHERE doc_id = ? AND replica_id = ?
            """, (double) by.toSeconds(), (double) by.toSeconds(), documentId, replicaId);
    }

    private VersionVector stableFor(UUID documentId, DocumentReplica replica) {
        List<VersionVector> live = progress.liveFor(documentId, UNRESPONSIVE_MS, WINDOW_MS);
        return replica.stableVersion(live);
    }

    @Test
    @DisplayName("a replica that just left still constrains the boundary")
    void recentlyDepartedReplicaStillCounts() {
        UUID document = newDocument();
        DocumentReplica replica = replicaWithHistory(document);

        // It saw only the first two of its own five characters before leaving.
        progress.record(document, DEPARTED, VersionVector.of(Map.of(DEPARTED, 1L)));
        progress.markDeparted(document, DEPARTED);
        progress.record(document, PRESENT, VersionVector.of(Map.of(DEPARTED, 4L, PRESENT, 4L)));

        age(document, DEPARTED, Duration.ofHours(1));

        assertThat(stableFor(document, replica).get(DEPARTED))
            .as("""
                An hour after disconnecting, the departed replica no longer held the boundary \
                back, so tombstones it never saw became collectable — and its return becomes an \
                unmergeable RESYNC_REQUIRED. The tombstone-collection design promises seven days.""")
            .isEqualTo(1L);
    }

    @Test
    @DisplayName("past the window it stops counting, so GC can proceed")
    void replicaGoneLongerThanTheWindowStopsCounting() {
        UUID document = newDocument();
        DocumentReplica replica = replicaWithHistory(document);

        progress.record(document, DEPARTED, VersionVector.of(Map.of(DEPARTED, 1L)));
        progress.markDeparted(document, DEPARTED);
        // The remaining client reports NOW and is not aged, so it is still responsive when we ask.
        // Ageing it too would drop every replica out, stableVersion would return empty, and the
        // test would pass or fail for a reason unrelated to the window.
        progress.record(document, PRESENT, VersionVector.of(Map.of(DEPARTED, 4L, PRESENT, 4L)));

        age(document, DEPARTED, Duration.ofDays(7).plusHours(1));

        assertThat(stableFor(document, replica).get(DEPARTED))
            .as("""
                The window has expired and the replica still holds the boundary back. Then the \
                window is not finite, which is option A in the tombstone-collection design — \
                tombstones accumulate for the lifetime of the document.""")
            .isEqualTo(4L);
    }

    @Test
    @DisplayName("the row is pruned once the window expires, not kept forever")
    void expiredRowsArePruned() {
        UUID document = newDocument();
        progress.record(document, DEPARTED, VersionVector.of(Map.of(DEPARTED, 1L)));
        progress.markDeparted(document, DEPARTED);

        assertThat(rowsFor(document)).isEqualTo(1);

        // Inside the window: pruning must NOT touch it, or the window is zero again by another
        // route. The sweep is global, so the assertion is on THIS document's rows rather than on
        // the number deleted — another test's rows may legitimately expire in the same pass.
        progress.pruneExpired(WINDOW_MS);
        assertThat(rowsFor(document))
            .as("a row still inside the GC window was pruned, so the window is zero by another route")
            .isEqualTo(1);

        age(document, DEPARTED, Duration.ofDays(14));
        progress.pruneExpired(WINDOW_MS);

        assertThat(rowsFor(document))
            .as("""
                Rows past the window are never removed, so this table grows with every replica \
                that has ever touched the document — and replica identifiers are never reused. \
                Keeping a version vector per departed client forever is the same unbounded growth \
                the rest of this system takes care to avoid.""")
            .isZero();
    }

    /**
     * The rule that was already there, re-pinned because the change could easily have merged the
     * two timeouts into one.
     */
    @Test
    @DisplayName("a connected but silent replica is still excluded after the shorter timeout")
    void connectedButSilentReplicaIsExcluded() {
        UUID document = newDocument();
        DocumentReplica replica = replicaWithHistory(document);

        // Reported once and then went quiet WITHOUT disconnecting — a hung tab.
        progress.record(document, DEPARTED, VersionVector.of(Map.of(DEPARTED, 1L)));
        progress.record(document, PRESENT, VersionVector.of(Map.of(DEPARTED, 4L, PRESENT, 4L)));

        age(document, DEPARTED, Duration.ofMinutes(10));

        assertThat(stableFor(document, replica).get(DEPARTED))
            .as("""
                A connected client that stopped reporting ten minutes ago is still holding GC \
                back. One hung tab then blocks compaction for the whole document, which is the \
                negative consequence the tombstone-collection design introduced the shorter \
                timeout to avoid.""")
            .isEqualTo(4L);
    }

    /**
     * The property the old in-memory map could not have: what one instance records, another reads.
     *
     * <p>Written against the store rather than against two Spring contexts on purpose — two
     * contexts would prove the same thing far more slowly, and what makes this work is that the
     * state is in Postgres at all rather than in either instance's heap. {@code ClusterChaosIT}
     * covers the two-instance wiring.
     */
    @Test
    @DisplayName("progress recorded for one instance's client constrains every instance")
    void progressIsClusterWide() {
        UUID document = newDocument();
        DocumentReplica replica = replicaWithHistory(document);

        // A client of some OTHER instance, which this instance has never seen a frame from.
        progress.record(document, DEPARTED, VersionVector.of(Map.of(DEPARTED, 1L)));
        progress.record(document, PRESENT, VersionVector.of(Map.of(DEPARTED, 4L, PRESENT, 4L)));

        assertThat(stableFor(document, replica).get(DEPARTED))
            .as("""
                The boundary ignored a replica this instance has no session for, so a snapshot \
                here would collect tombstones a client connected elsewhere still needs — and that \
                client's next operation buffers forever, with no RESYNC_REQUIRED, because \
                applyRemote does not go through the unanchored refusal that submit does.""")
            .isEqualTo(1L);
    }

    /**
     * The leak the per-document, snapshot-triggered prune could not reach.
     *
     * <p>{@code departed_at} is written by the session teardown, and a teardown does not always
     * happen — an instance killed outright, an out-of-memory container, a rolling deploy. Those rows
     * stop counting towards causal stability after the unresponsive timeout, so they never hold
     * collection back, and nothing deleted them: one crashed instance leaked one row per open tab,
     * for ever, against a table whose own comment promises a bound.
     */
    @Test
    @DisplayName("a replica that never departed is still reclaimed eventually")
    void abandonedRowsAreReclaimed() {
        UUID document = newDocument();
        progress.record(document, PRESENT, VersionVector.of(Map.of(PRESENT, 1L)));

        // Never departed — no teardown ever ran for it.
        age(document, PRESENT, Duration.ofDays(8));
        progress.pruneExpired(WINDOW_MS);
        assertThat(rowsFor(document))
            .as("one GC window is NOT enough: a row that is merely old may still belong to a "
                + "client that is slow rather than gone, and dropping it early is the failure the "
                + "window exists to prevent")
            .isEqualTo(1);

        age(document, PRESENT, Duration.ofDays(8));
        progress.pruneExpired(WINDOW_MS);
        assertThat(rowsFor(document))
            .as("a row untouched for two GC windows belongs to a replica that is not coming back — "
                + "a live one refreshes every fifteen seconds — and nothing else will ever remove it")
            .isZero();
    }

    private int rowsFor(UUID documentId) {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM replica_progress WHERE doc_id = ?", Integer.class, documentId);
        return count == null ? 0 : count;
    }
}
