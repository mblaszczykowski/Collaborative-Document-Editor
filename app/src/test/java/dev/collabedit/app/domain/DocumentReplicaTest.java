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

/**
 * A replica's write buffer. No Spring and no database — this is the behaviour of a data structure,
 * not of layers cooperating.
 *
 * <table>
 *   <caption>Test -> failure mode</caption>
 *   <tr><th>Test</th><th>What it catches</th></tr>
 *   <tr><td>{@code drainRespectsLimit}</td>
 *       <td>The batch size limit not working. The symptom is functionally invisible: the write is
 *           still correct, it is just that one transaction holds the document's row lock for
 *           arbitrarily long, blocking every other writer.</td></tr>
 *   <tr><td>{@code drainPreservesOrderAcrossBatches}</td>
 *       <td>Splitting into batches scrambling the order. {@code server_seq} is assigned in drain
 *           order, and the cluster design bases detection of lost messages on its CONTINUITY — reordering
 *           operations breaks the resynchronisation cursor.</td></tr>
 *   <tr><td>{@code drainLeavesNothingBehind}</td>
 *       <td>An operation staying in the buffer forever and never reaching the log. The client gets
 *           no acknowledgement, so it retransmits it indefinitely.</td></tr>
 * </table>
 */
class DocumentReplicaTest {

    private static final int LIMIT = 3;

    /**
     * A server replica with {@code count} operations waiting to be written.
     *
     * <p>The operations are created on a SEPARATE replica (the "browser") and only then merged into
     * the server replica. That is not decoration: generating them via
     * {@code replica.withLock(insert)} would leave them already applied, so {@code merge} would
     * recognise them as duplicates, return {@code false} and <b>add nothing to the buffer</b> — the
     * test would measure an empty buffer and pass regardless of what the limit does.
     */
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
            .as("server_seq is assigned in drain order — reordering breaks the cluster design")
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

    /**
     * The two clocks answer two different questions, and conflating them stops snapshots.
     *
     * <p>Eviction asks "is anybody here" — a progress report is a yes, and a client sends one every
     * 15 seconds. The snapshot sweep's quiet branch asks "has this document been STILL for 30
     * seconds", which is about edits. While one field served both, an open tab made the quiet
     * threshold unreachable: a document edited a little and left open was never snapshotted, so its
     * search vector and list preview never updated and its log tail grew without bound.
     *
     * <p>A unit test rather than an integration one, deliberately. The rig reports progress every
     * 500 ms against a 500 ms test threshold, so an integration test of this is a coin flip — which
     * is exactly why nothing caught the regression.
     */
    @Test
    @DisplayName("a progress report keeps the replica alive without making the document look edited")
    void progressReportIsActivityButNotAnEdit() {
        DocumentReplica replica = new DocumentReplica(
            UUID.randomUUID(), new RgaDocument(-1), 0, 0);

        replica.reportProgress(7, VersionVector.empty(), 10_000);

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
