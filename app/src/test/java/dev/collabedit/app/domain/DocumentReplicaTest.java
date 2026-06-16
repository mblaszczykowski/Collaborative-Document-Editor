package dev.collabedit.app.domain;

import static org.assertj.core.api.Assertions.assertThat;

import dev.collabedit.app.persist.PendingOperation;
import dev.collabedit.crdt.Op;
import dev.collabedit.crdt.RgaDocument;
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
 *           order, and ADR-0004 bases detection of lost messages on its CONTINUITY — reordering
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
            .as("server_seq is assigned in drain order — reordering breaks ADR-0004")
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
}
