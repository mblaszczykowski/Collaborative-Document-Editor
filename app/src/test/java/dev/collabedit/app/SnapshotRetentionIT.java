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
        jdbc.update("INSERT INTO documents (id, title, visibility) VALUES (?, ?, 'link')", id, "retention-test");
        return id;
    }

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
                the deployment - see SnapshotStore.prune.""")
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

    @Test
    @DisplayName("history at a past point still has a snapshot to start from")
    void historyStillUsesASnapshotAfterPruning() {
        UUID document = newDocument();
        int kept = properties.persistence().snapshotsKeptPerDocument();
        buildWithSnapshots(document, kept + 5, 4);

        List<Long> remaining = jdbc.queryForList(
            "SELECT up_to_server_seq FROM snapshots WHERE doc_id = ? ORDER BY up_to_server_seq DESC",
            Long.class, document);
        long head = remaining.getFirst();

        long justBelowHead = head - 1;

        assertThat(snapshots.findLatestUpTo(document, justBelowHead))
            .as("""
                No snapshot survives below the head, so every history lookup would replay the \
                whole log. Retention of one bounds the table and moves the cost somewhere else \
                rather than removing it - see the class javadoc.""")
            .isPresent()
            .get()
            .satisfies(found -> assertThat(found.upToServerSeq())
                .as("the snapshot serving a point below the head IS the head, so retention kept "
                    + "only one and every history lookup before it replays the whole log")
                .isLessThan(head));
    }

    @Test
    @DisplayName("pruning changes no result - the log is still the source of truth")
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
