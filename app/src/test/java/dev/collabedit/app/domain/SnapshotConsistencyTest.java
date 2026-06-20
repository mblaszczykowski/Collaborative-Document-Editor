package dev.collabedit.app.domain;

import static org.assertj.core.api.Assertions.assertThat;

import dev.collabedit.crdt.Op;
import dev.collabedit.crdt.RgaDocument;
import dev.collabedit.crdt.TreapBlockList;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A snapshot may only be labelled with a sequence number that actually describes it.
 *
 * <h2>The window this closes</h2>
 * A flush drains operations under the document lock, appends them to the log <b>outside</b> it,
 * and only then advances {@code appliedServerSeq}. In between, the document holds work that is in
 * neither the unwritten buffer nor the sequence number. A snapshot taken there gets a label lower
 * than the state it contains.
 *
 * <p>Document loading survives that: it re-applies the tail from the log and merging is
 * idempotent. {@code HistoryService.textAt} does not — it asks for the newest snapshot no later
 * than a past point and then replays only up to that point, so a snapshot holding operations from
 * beyond its label makes time travel show a state from slightly in the future.
 *
 * <h2>Why it became worth closing now</h2>
 * The window existed before the scheduler split as well: a merge between reading the number and
 * taking the snapshot did the same thing. But snapshots ran on the same thread as flushes, so a
 * whole flush — including a database round trip — could never fit inside it. Moving snapshots to
 * their own pool made it wide enough to matter. A regression introduced by a fix, which is the
 * kind worth writing a test for rather than a note.
 */
@DisplayName("A snapshot is refused unless its label describes the state")
class SnapshotConsistencyTest {

    private static DocumentReplica replica() {
        return new DocumentReplica(
            UUID.randomUUID(),
            new RgaDocument(1, new TreapBlockList()),
            0,
            System.currentTimeMillis());
    }

    private static Op.Insert anInsert(RgaDocument on) {
        return on.insert(on.visibleLength(), "x");
    }

    @Test
    @DisplayName("an idle replica may be snapshotted, at its current sequence number")
    void idleReplicaIsConsistent() {
        DocumentReplica replica = replica();
        replica.advanceServerSeq(7);

        var captured = replica.withConsistentState((document, seq) -> seq);

        assertThat(captured).contains(7L);
    }

    @Test
    @DisplayName("a replica with operations waiting to be written is refused")
    void unwrittenOperationsBlockASnapshot() {
        DocumentReplica replica = replica();
        RgaDocument client = new RgaDocument(2, new TreapBlockList());
        replica.merge(anInsert(client), "u1", null, System.currentTimeMillis());

        assertThat(replica.withConsistentState((document, seq) -> seq))
            .as("""
                The document holds an operation that is not in the log, so no sequence number \
                describes this state — but a snapshot was allowed anyway.""")
            .isEmpty();
    }

    /**
     * The case the counter exists for, and the one no other check catches.
     *
     * <p>After {@code drainForFlush} the unwritten buffer is EMPTY and the sequence number has not
     * moved yet — so every signal available before this change said "consistent", while the
     * document held an operation whose {@code server_seq} was still being assigned.
     */
    @Test
    @DisplayName("a flush in flight is refused, even though nothing is left in the buffer")
    void aFlushInFlightBlocksASnapshot() {
        DocumentReplica replica = replica();
        RgaDocument client = new RgaDocument(2, new TreapBlockList());
        replica.merge(anInsert(client), "u1", null, System.currentTimeMillis());

        DocumentReplica.OutboundBatch outbound = replica.drainForFlush(100);
        assertThat(outbound.writes()).hasSize(1);
        assertThat(replica.unwrittenCount())
            .as("the drain is supposed to empty the buffer; the test premise is wrong otherwise")
            .isZero();

        assertThat(replica.withConsistentState((document, seq) -> seq))
            .as("""
                The buffer is empty and the sequence number has not moved, so every other signal \
                says this state is consistent — and it is not: the drained operation is in the \
                document and its server_seq is still being assigned. A snapshot here is labelled \
                with a number lower than what it contains.""")
            .isEmpty();
    }

    @Test
    @DisplayName("once the flush completes, the snapshot is allowed at the new number")
    void completingTheFlushRestoresConsistency() {
        DocumentReplica replica = replica();
        RgaDocument client = new RgaDocument(2, new TreapBlockList());
        replica.merge(anInsert(client), "u1", null, System.currentTimeMillis());
        replica.drainForFlush(100);

        replica.flushCompleted(42);

        assertThat(replica.withConsistentState((document, seq) -> seq))
            .as("the flush finished, so the state and the number agree again")
            .contains(42L);
    }

    /**
     * A failed write must still end the flush.
     *
     * <p>Otherwise the counter never returns to zero and the document stops snapshotting for the
     * lifetime of the process — a leak that shows up as "this one document loads slowly" long
     * after the write that failed.
     */
    @Test
    @DisplayName("a flush that wrote nothing still ends, so snapshots resume")
    void aFailedFlushStillEnds() {
        DocumentReplica replica = replica();
        RgaDocument client = new RgaDocument(2, new TreapBlockList());
        replica.merge(anInsert(client), "u1", null, System.currentTimeMillis());
        replica.drainForFlush(100);

        replica.flushCompleted(0);

        assertThat(replica.withConsistentState((document, seq) -> seq))
            .as("""
                A flush that appended nothing left the counter raised, so this replica will never \
                be snapshotted again.""")
            .isPresent();
    }

    @Test
    @DisplayName("concurrent flushes are counted, not toggled")
    void overlappingFlushesEachHaveToEnd() {
        DocumentReplica replica = replica();
        RgaDocument client = new RgaDocument(2, new TreapBlockList());
        replica.merge(anInsert(client), "u1", null, System.currentTimeMillis());
        replica.drainForFlush(100);
        replica.merge(anInsert(client), "u1", null, System.currentTimeMillis());
        replica.drainForFlush(100);

        replica.flushCompleted(10);
        assertThat(replica.withConsistentState((document, seq) -> seq))
            .as("one of two flushes finished, so the state is still unsettled")
            .isEmpty();

        replica.flushCompleted(11);
        assertThat(replica.withConsistentState((document, seq) -> seq)).contains(11L);
    }
}
