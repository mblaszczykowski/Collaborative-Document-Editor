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

    private UUID newDocument() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO documents (id, title, visibility) VALUES (?, ?, 'link')", id, "GC window");
        return id;
    }

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

        progress.record(document, DEPARTED, VersionVector.of(Map.of(DEPARTED, 1L)));
        progress.markDeparted(document, DEPARTED);
        progress.record(document, PRESENT, VersionVector.of(Map.of(DEPARTED, 4L, PRESENT, 4L)));

        age(document, DEPARTED, Duration.ofHours(1));

        assertThat(stableFor(document, replica).get(DEPARTED))
            .as("""
                An hour after disconnecting, the departed replica no longer held the boundary \
                back, so tombstones it never saw became collectable - and its return becomes an \
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
        progress.record(document, PRESENT, VersionVector.of(Map.of(DEPARTED, 4L, PRESENT, 4L)));

        age(document, DEPARTED, Duration.ofDays(7).plusHours(1));

        assertThat(stableFor(document, replica).get(DEPARTED))
            .as("""
                The window has expired and the replica still holds the boundary back. Then the \
                window is not finite, which is option A in the tombstone-collection design - \
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

        progress.pruneExpired(WINDOW_MS);
        assertThat(rowsFor(document))
            .as("a row still inside the GC window was pruned, so the window is zero by another route")
            .isEqualTo(1);

        age(document, DEPARTED, Duration.ofDays(14));
        progress.pruneExpired(WINDOW_MS);

        assertThat(rowsFor(document))
            .as("""
                Rows past the window are never removed, so this table grows with every replica \
                that has ever touched the document - and replica identifiers are never reused. \
                Keeping a version vector per departed client forever is the same unbounded growth \
                the rest of this system takes care to avoid.""")
            .isZero();
    }

    @Test
    @DisplayName("an unrecorded departure receives offline grace but expires after the window")
    void silentReplicaReceivesOfflineGraceUntilWindowExpires() {
        UUID document = newDocument();
        DocumentReplica replica = replicaWithHistory(document);

        progress.record(document, DEPARTED, VersionVector.of(Map.of(DEPARTED, 1L)));
        progress.record(document, PRESENT, VersionVector.of(Map.of(DEPARTED, 4L, PRESENT, 4L)));

        age(document, DEPARTED, Duration.ofMinutes(10));

        assertThat(stableFor(document, replica).get(DEPARTED))
            .as("a crashed server cannot record departure; it must preserve the same offline grace")
            .isEqualTo(1L);
        age(document, DEPARTED, Duration.ofDays(14));
        assertThat(stableFor(document, replica).get(DEPARTED))
            .as("offline retention is bounded even when no departure was recorded")
            .isEqualTo(4L);
    }

    @Test
    @DisplayName("progress recorded for one instance's client constrains every instance")
    void progressIsClusterWide() {
        UUID document = newDocument();
        DocumentReplica replica = replicaWithHistory(document);

        progress.record(document, DEPARTED, VersionVector.of(Map.of(DEPARTED, 1L)));
        progress.record(document, PRESENT, VersionVector.of(Map.of(DEPARTED, 4L, PRESENT, 4L)));

        assertThat(stableFor(document, replica).get(DEPARTED))
            .as("""
                The boundary ignored a replica this instance has no session for, so a snapshot \
                here would collect tombstones a client connected elsewhere still needs - and that \
                client's next operation buffers forever, with no RESYNC_REQUIRED, because \
                applyRemote does not go through the unanchored refusal that submit does.""")
            .isEqualTo(1L);
    }

    @Test
    @DisplayName("a replica that never departed is still reclaimed eventually")
    void abandonedRowsAreReclaimed() {
        UUID document = newDocument();
        progress.record(document, PRESENT, VersionVector.of(Map.of(PRESENT, 1L)));

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
            .as("a row untouched for two GC windows belongs to a replica that is not coming back - "
                + "a live one refreshes every fifteen seconds - and nothing else will ever remove it")
            .isZero();
    }

    private int rowsFor(UUID documentId) {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM replica_progress WHERE doc_id = ?", Integer.class, documentId);
        return count == null ? 0 : count;
    }
}
