package dev.collabedit.app.domain;

import static org.assertj.core.api.Assertions.assertThat;

import dev.collabedit.crdt.RgaDocument;
import dev.collabedit.crdt.TreapBlockList;
import dev.collabedit.crdt.VersionVector;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The GC window, which was configured for five checkpoints and read by nothing.
 *
 * <h2>What was wrong</h2>
 * {@code collabedit.gc.window-days: 7} existed in {@code application.yml} with a paragraph of
 * justification, and in {@code CollabEditProperties} as a field. <b>No code read it.</b>
 *
 * <p>The tombstone-collection design defines the replicas that hold
 * GC back as those <i>"connected now, or disconnected for less than the GC window"</i>, and
 * devotes a section to the client that returns after it. The implementation had the first clause
 * and, on disconnect, deleted the replica's entry outright — so a departed client held nothing
 * back at all.
 *
 * <p>The distance between the document and the code was five orders of magnitude. The README
 * describes {@code RESYNC_REQUIRED} as what happens to "a client offline longer than the GC window
 * (7 days)"; what actually happened is that closing a laptop removed that client from the
 * calculation immediately, so the tombstones its unsent work was anchored to became collectable
 * as soon as the remaining clients moved past them and a snapshot ran. Minutes, not days — on the
 * one path in this system that refuses a user's work by design.
 *
 * <h2>Why the two timeouts are not one</h2>
 * They answer different questions and this test pins both. Silence from a client that is
 * <b>still connected</b> is evidence something is wrong with it, so after
 * {@code unresponsive-replica-timeout-ms} it stops counting — otherwise one hung tab blocks
 * compaction for everyone. Silence from a client that has <b>disconnected</b> is exactly what is
 * expected, and is the case the window exists for.
 */
@DisplayName("A departed replica holds GC back for the GC window, not for zero")
class GcWindowTest {

    private static final long UNRESPONSIVE_MS = Duration.ofSeconds(60).toMillis();
    private static final long WINDOW_MS = Duration.ofDays(7).toMillis();

    /** An arbitrary fixed "now"; every time in this test is relative to it. */
    private static final long NOW = 1_760_000_000_000L;

    private static final int DEPARTED = 42;
    private static final int PRESENT = 43;

    /**
     * A replica whose own state is well ahead of the clients.
     *
     * <p>The server's document is included in the meet as a replica in its own right, so it has
     * to know about the replica ids used below — otherwise its own vector contributes nothing for
     * them and the meet is decided by the clients alone, which would make the assertions pass for
     * the wrong reason.
     */
    private static DocumentReplica replicaWithHistory() {
        RgaDocument document = new RgaDocument(1, new TreapBlockList());
        DocumentReplica replica = new DocumentReplica(UUID.randomUUID(), document, 0, NOW);
        RgaDocument departedClient = new RgaDocument(DEPARTED, new TreapBlockList());
        RgaDocument presentClient = new RgaDocument(PRESENT, new TreapBlockList());
        replica.merge(departedClient.insert(0, "aaaaa"), "u1", null, NOW);
        replica.merge(presentClient.insert(0, "bbbbb"), "u2", null, NOW);
        return replica;
    }

    /** How far the meet lets GC go for a given replica id. */
    private static long boundaryFor(VersionVector stable, int replicaId) {
        return stable.get(replicaId);
    }

    @Test
    @DisplayName("a replica that just left still constrains the boundary")
    void recentlyDepartedReplicaStillCounts() {
        DocumentReplica replica = replicaWithHistory();

        // It saw only the first two of its own five characters before leaving.
        replica.reportProgress(DEPARTED, VersionVector.of(Map.of(DEPARTED, 1L)), NOW);
        replica.markDeparted(DEPARTED, NOW);
        replica.reportProgress(PRESENT, VersionVector.of(Map.of(DEPARTED, 4L, PRESENT, 4L)), NOW);

        VersionVector stable = replica.stableVersion(
            NOW + Duration.ofHours(1).toMillis(), UNRESPONSIVE_MS, WINDOW_MS);

        assertThat(boundaryFor(stable, DEPARTED))
            .as("""
                An hour after disconnecting, the departed replica no longer held the boundary \
                back, so tombstones it never saw became collectable — and its return becomes an \
                unmergeable RESYNC_REQUIRED. The tombstone-collection design promises seven days.""")
            .isEqualTo(1L);
    }

    @Test
    @DisplayName("past the window it stops counting, so GC can proceed")
    void replicaGoneLongerThanTheWindowStopsCounting() {
        DocumentReplica replica = replicaWithHistory();

        replica.reportProgress(DEPARTED, VersionVector.of(Map.of(DEPARTED, 1L)), NOW);
        replica.markDeparted(DEPARTED, NOW);

        long wellPast = NOW + WINDOW_MS + Duration.ofHours(1).toMillis();
        // The remaining client reports AT that moment, not at NOW. Reporting at NOW would make it
        // unresponsive too by the time we ask, every replica would drop out, and stableVersion
        // would return empty — the test would then pass or fail for a reason unrelated to the
        // window. (It failed exactly that way when first written.)
        replica.reportProgress(PRESENT, VersionVector.of(Map.of(DEPARTED, 4L, PRESENT, 4L)), wellPast);

        VersionVector stable = replica.stableVersion(wellPast, UNRESPONSIVE_MS, WINDOW_MS);

        assertThat(boundaryFor(stable, DEPARTED))
            .as("""
                The window has expired and the replica still holds the boundary back. Then the \
                window is not finite, which is option A in the tombstone-collection design — tombstones accumulate for \
                the lifetime of the document.""")
            .isEqualTo(4L);
    }

    @Test
    @DisplayName("the entry is pruned once the window expires, not kept forever")
    void expiredEntriesArePruned() {
        DocumentReplica replica = replicaWithHistory();
        replica.reportProgress(DEPARTED, VersionVector.of(Map.of(DEPARTED, 1L)), NOW);
        replica.markDeparted(DEPARTED, NOW);

        assertThat(replica.replicaProgress()).containsKey(DEPARTED);

        replica.stableVersion(NOW + WINDOW_MS * 2, UNRESPONSIVE_MS, WINDOW_MS);

        assertThat(replica.replicaProgress())
            .as("""
                Entries past the window are never removed, so this map grows with every replica \
                that has ever touched the document. Keeping a version vector per departed client \
                forever is the same unbounded growth the rest of this class avoids.""")
            .doesNotContainKey(DEPARTED);
    }

    /**
     * The rule that was already there, re-pinned because the change could easily have merged the
     * two timeouts into one.
     */
    @Test
    @DisplayName("a connected but silent replica is still excluded after the shorter timeout")
    void connectedButSilentReplicaIsExcluded() {
        DocumentReplica replica = replicaWithHistory();

        // Reported once and then went quiet WITHOUT disconnecting — a hung tab.
        replica.reportProgress(DEPARTED, VersionVector.of(Map.of(DEPARTED, 1L)), NOW);
        replica.reportProgress(PRESENT, VersionVector.of(Map.of(DEPARTED, 4L, PRESENT, 4L)),
            NOW + Duration.ofMinutes(10).toMillis());

        VersionVector stable = replica.stableVersion(
            NOW + Duration.ofMinutes(10).toMillis(), UNRESPONSIVE_MS, WINDOW_MS);

        assertThat(boundaryFor(stable, DEPARTED))
            .as("""
                A connected client that stopped reporting ten minutes ago is still holding GC \
                back. One hung tab then blocks compaction for the whole document, which is the \
                negative consequence the tombstone-collection design introduced the shorter timeout to avoid.""")
            .isEqualTo(4L);
    }
}
