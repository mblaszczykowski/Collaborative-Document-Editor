package dev.collabedit.app;

import static org.assertj.core.api.Assertions.assertThat;

import dev.collabedit.app.config.CollabEditProperties;
import dev.collabedit.app.history.HistoryService;
import dev.collabedit.app.persist.OperationStore;
import dev.collabedit.app.persist.PendingOperation;
import dev.collabedit.app.persist.SnapshotStore;
import dev.collabedit.crdt.RgaDocument;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The snapshot table is a cache, and a cache that is never evicted is a leak.
 *
 * <h2>What was wrong</h2>
 * Nothing ever deleted a snapshot row. One is written every {@code snapshot-every-n-ops}
 * operations <b>or</b> after {@code snapshot-after-idle-ms} of quiet, and each holds a full
 * compressed copy of the document — so the table grew for the lifetime of the deployment, and
 * fastest for the documents people actually used.
 *
 * <p>The asymmetry is the interesting part. Tombstone GC <i>in memory</i> has
 * a causal-stability calculation, a
 * rule for excluding unresponsive replicas, and a checkpoint of its own. The cache <i>on disk</i>
 * had no policy at all. The operation-log decision describes the snapshot as a disposable cache throughout; the
 * code had implemented "cache" and skipped "disposable".
 *
 * <h2>Why retention is not one</h2>
 * The newest snapshot is not the only one that gets read: {@link HistoryService#textAt} asks for
 * the newest snapshot <b>not later than</b> a past {@code server_seq}. Keeping only the head
 * would bound the table and make every history lookup replay the log from zero, which is trading
 * a disk problem for a latency problem. {@link #historyStillUsesASnapshotAfterPruning()} is the
 * test that keeps that trade honest.
 */
class SnapshotRetentionIT extends IntegrationTestBase {

    @Autowired
    SnapshotStore snapshots;
    @Autowired
    OperationStore operations;
    @Autowired
    HistoryService history;
    @Autowired
    CollabEditProperties properties;
    @Autowired
    JdbcTemplate jdbc;

    private UUID newDocument() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO documents (id, title) VALUES (?, ?)", id, "retention-test");
        return id;
    }

    /**
     * Builds a document with {@code rounds} snapshots taken along the way.
     *
     * <p>Operations go through {@link OperationStore} so that the log is real: the point of a
     * snapshot is to be a shortcut over a log that exists, and a test that skipped the log would
     * not be able to check that removing snapshots changes nothing.
     */
    private long buildWithSnapshots(UUID document, int rounds, int opsPerRound) {
        RgaDocument replica = new RgaDocument(1);
        long lastSeq = 0;
        for (int round = 0; round < rounds; round++) {
            List<PendingOperation> batch = new ArrayList<>();
            for (int i = 0; i < opsPerRound; i++) {
                batch.add(new PendingOperation(
                    replica.insert(replica.visibleLength(), "x"), "u1", null));
            }
            lastSeq = operations.append(document, batch).getLast().serverSeq();
            snapshots.save(document, lastSeq, replica.snapshot());
        }
        return lastSeq;
    }

    @Test
    @DisplayName("writing snapshots repeatedly does not grow the table without bound")
    void oldSnapshotsArePruned() {
        UUID document = newDocument();
        int kept = properties.persistence().snapshotsKeptPerDocument();

        buildWithSnapshots(document, kept + 5, 4);

        assertThat(snapshots.count(document))
            .as("""
                The snapshot table grew past the retention limit. Every snapshot holds a full \
                copy of the document, so without pruning this table grows for the lifetime of \
                the deployment — see SnapshotStore.prune.""")
            .isEqualTo(kept);
    }

    @Test
    @DisplayName("the snapshots kept are the newest ones, not an arbitrary subset")
    void theNewestSnapshotsAreTheOnesKept() {
        UUID document = newDocument();
        int kept = properties.persistence().snapshotsKeptPerDocument();

        long headSeq = buildWithSnapshots(document, kept + 5, 4);

        List<Long> remaining = jdbc.queryForList(
            "SELECT up_to_server_seq FROM snapshots WHERE doc_id = ? ORDER BY up_to_server_seq DESC",
            Long.class, document);

        assertThat(remaining).hasSize(kept);
        assertThat(remaining.getFirst())
            .as("""
                The newest snapshot was pruned. Loading a document would then replay more of the \
                log than it needs to, which is the exact cost snapshots exist to avoid.""")
            .isEqualTo(headSeq);
        assertThat(remaining).isSortedAccordingTo(java.util.Comparator.reverseOrder());
    }

    /**
     * Pruning must not turn history lookups into full log replays.
     *
     * <p>This is the assertion that justifies a retention above one, and it fails if someone
     * later "simplifies" the policy to keeping only the head. It asks for the state at a point in
     * the past and checks a snapshot older than the head is available to serve it.
     */
    @Test
    @DisplayName("history at a past point still has a snapshot to start from")
    void historyStillUsesASnapshotAfterPruning() {
        UUID document = newDocument();
        int kept = properties.persistence().snapshotsKeptPerDocument();
        buildWithSnapshots(document, kept + 5, 4);

        List<Long> remaining = jdbc.queryForList(
            "SELECT up_to_server_seq FROM snapshots WHERE doc_id = ? ORDER BY up_to_server_seq DESC",
            Long.class, document);
        long olderThanHead = remaining.getLast();

        assertThat(snapshots.findLatestUpTo(document, olderThanHead))
            .as("""
                No snapshot survives below the head, so every history lookup would replay the \
                whole log. Retention of one bounds the table and moves the cost somewhere else \
                rather than removing it — see the class javadoc.""")
            .isPresent();
    }

    /**
     * The property the whole caching scheme rests on, re-checked after pruning.
     *
     * <p>CP5 established that deleting every snapshot changes no result. Pruning is deletion, so
     * it has to hold afterwards too — otherwise retention would have quietly promoted the cache
     * to a source of truth, which is the failure the operation-log decision is written to prevent.
     */
    @Test
    @DisplayName("pruning changes no result — the log is still the source of truth")
    void prunedSnapshotsChangeNothing() {
        UUID document = newDocument();
        long headSeq = buildWithSnapshots(document, properties.persistence().snapshotsKeptPerDocument() + 5, 4);

        String withSnapshots = history.textAt(document, headSeq);

        snapshots.deleteAll(document);
        String fromLogAlone = history.textAt(document, headSeq);

        assertThat(fromLogAlone)
            .as("""
                The text differs depending on whether a snapshot was available, which means the \
                snapshot is no longer a cache but a second source of truth.""")
            .isEqualTo(withSnapshots);
        assertThat(fromLogAlone).isNotEmpty();
    }
}
