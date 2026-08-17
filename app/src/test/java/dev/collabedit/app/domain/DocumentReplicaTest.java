package dev.collabedit.app.domain;

import static org.assertj.core.api.Assertions.assertThat;

import dev.collabedit.app.persist.PendingOperation;
import dev.collabedit.crdt.Op;
import dev.collabedit.crdt.RgaDocument;
import dev.collabedit.crdt.VersionVector;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DocumentReplicaTest {
    private static final int LIMIT = 3;

    private DocumentReplica replicaWithPendingOperations(int count) {
        DocumentReplica replica = new DocumentReplica(
            UUID.randomUUID(), new RgaDocument(-1), 0, 0);
        RgaDocument browser = new RgaDocument(1);
        for (int i = 0; i < count; i++) {
            Op op = browser.insert(i, String.valueOf((char) ('a' + i)));
            assertThat(replica.merge(op, "tester", null, 0))
                .as("operation %d should be new to the server replica", i)
                .isTrue();
        }
        assertThat(replica.unwrittenCount()).isEqualTo(count);
        return replica;
    }

    @Test
    @DisplayName("a batch does not exceed the given limit, and the rest waits for the next window")
    void drainRespectsLimit() {
        DocumentReplica replica = replicaWithPendingOperations(10);

        List<PendingOperation> first = replica.drainUnwritten(LIMIT);

        assertThat(first).hasSize(LIMIT);
        assertThat(replica.unwrittenCount())
            .as("the rest has to stay in the buffer rather than disappear")
            .isEqualTo(7);
    }

    @Test
    @DisplayName("operation order survives being split across batches")
    void drainPreservesOrderAcrossBatches() {
        DocumentReplica replica = replicaWithPendingOperations(10);

        StringBuilder drained = new StringBuilder();
        List<PendingOperation> batch;
        while (!(batch = replica.drainUnwritten(LIMIT)).isEmpty()) {
            assertThat(batch).hasSizeLessThanOrEqualTo(LIMIT);
            for (PendingOperation pending : batch) {
                drained.append(((Op.Insert) pending.op()).text());
            }
        }

        assertThat(drained.toString())
            .as("server_seq is assigned in drain order - reordering breaks the cluster design")
            .isEqualTo("abcdefghij");
    }

    @Test
    @DisplayName("draining repeatedly takes everything, leaving nothing in the buffer")
    void drainLeavesNothingBehind() {
        DocumentReplica replica = replicaWithPendingOperations(10);

        int total = 0;
        List<PendingOperation> batch;
        while (!(batch = replica.drainUnwritten(LIMIT)).isEmpty()) {
            total += batch.size();
        }

        assertThat(total).isEqualTo(10);
        assertThat(replica.unwrittenCount()).isZero();
    }

    @Test
    @DisplayName("a batch smaller than the limit comes out whole")
    void drainReturnsEverythingBelowLimit() {
        DocumentReplica replica = replicaWithPendingOperations(2);

        assertThat(replica.drainUnwritten(LIMIT)).hasSize(2);
        assertThat(replica.drainUnwritten(LIMIT)).isEmpty();
    }

    @Test
    @DisplayName("a progress report keeps the replica alive without making the document look edited")
    void progressReportIsActivityButNotAnEdit() {
        DocumentReplica replica = new DocumentReplica(
            UUID.randomUUID(), new RgaDocument(-1), 0, 0);

        replica.reportProgress(10_000);

        assertThat(replica.lastActivityMillis())
            .as("a connected client reporting progress must postpone eviction")
            .isEqualTo(10_000);
        assertThat(replica.lastEditMillis())
            .as("a progress report is not an edit; counting it as one makes the snapshot sweep's "
                + "quiet threshold unreachable for as long as anybody keeps the document open")
            .isZero();
    }

    @Test
    @DisplayName("an edit advances both clocks")
    void anEditIsBothActivityAndAnEdit() {
        DocumentReplica replica = new DocumentReplica(
            UUID.randomUUID(), new RgaDocument(-1), 0, 0);
        RgaDocument browser = new RgaDocument(1);

        replica.merge(browser.insert(0, "a"), "tester", null, 5_000);

        assertThat(replica.lastActivityMillis()).isEqualTo(5_000);
        assertThat(replica.lastEditMillis()).isEqualTo(5_000);
    }
}
