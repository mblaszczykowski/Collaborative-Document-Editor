package dev.collabedit.app.domain;

import static org.assertj.core.api.Assertions.assertThat;

import dev.collabedit.app.persist.StoredOperation;
import dev.collabedit.crdt.Op;
import dev.collabedit.crdt.RgaDocument;
import dev.collabedit.crdt.TreapBlockList;
import java.time.Instant;
import java.util.List;
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

    /**
     * The other way a replica could hold work above its own label — through the CLUSTER path.
     *
     * <h2>What was wrong</h2>
     * {@code DocumentService.applyRemote} applied incoming operations one at a time through
     * {@code withLock} and advanced {@code appliedServerSeq} only after the whole loop. Between the
     * first {@code apply} and that advance the replica held operations from ABOVE its label while
     * reporting itself consistent — {@code withConsistentState} guards {@code flushesInFlight} and
     * {@code unwritten}, and that path touches neither.
     *
     * <p>Not a narrow window either: the snapshot sweep runs on the maintenance pool, cluster
     * messages arrive on a four-to-eight thread listener pool, and anti-entropy runs alongside
     * both. A snapshot taken inside it is stored with a number LOWER than the state it holds, and
     * {@code HistoryService.textAt} trusts that number to mean "exactly the operations up to here"
     * — so time travel shows a state from the future, and {@code /history/restore} builds its
     * target from it.
     *
     * <p>The fix is structural rather than a wider guard: applying and labelling happen under ONE
     * lock acquisition, so the inconsistent state has no instant to exist in. This test asserts
     * what that buys — after applying, the label already covers what was applied.
     */
    @Test
    @DisplayName("remote operations and their label move together, so no snapshot sees them unlabelled")
    void remoteOperationsAreLabelledAsTheyAreApplied() {
        DocumentReplica replica = replica();
        RgaDocument client = new RgaDocument(2, new TreapBlockList());

        List<StoredOperation> incoming = List.of(
            new StoredOperation(anInsert(client), 41, "u1", Instant.EPOCH),
            new StoredOperation(anInsert(client), 42, "u1", Instant.EPOCH));

        assertThat(replica.applyRemote(incoming, System.currentTimeMillis()))
            .as("both operations were news to this replica")
            .hasSize(2);

        assertThat(replica.appliedServerSeq())
            .as("""
                The operations are in the document and the label does not cover them. A snapshot \
                taken now is stored under a number lower than its own contents, and \
                HistoryService.textAt returns a state from the future for every point after it.""")
            .isEqualTo(42);
    }

    @Test
    @DisplayName("a duplicate from the cluster still advances the label")
    void duplicatesStillAdvanceTheLabel() {
        DocumentReplica replica = replica();
        RgaDocument client = new RgaDocument(2, new TreapBlockList());
        StoredOperation stored =
            new StoredOperation(anInsert(client), 9, "u1", Instant.EPOCH);

        replica.applyRemote(List.of(stored), System.currentTimeMillis());
        replica.applyRemote(List.of(stored), System.currentTimeMillis());

        assertThat(replica.appliedServerSeq())
            .as("""
                The label says how far the LOG has been seen, not how much of it changed anything. \
                An operation this replica already held has still been seen, and treating a \
                duplicate as unseen would make anti-entropy pull the same range forever.""")
            .isEqualTo(9);
    }

    @Test
    @DisplayName("remote operations are queued for broadcast but not for writing")
    void remoteOperationsAreNotWrittenAgain() {
        DocumentReplica replica = replica();
        RgaDocument client = new RgaDocument(2, new TreapBlockList());
        replica.applyRemote(
            List.of(new StoredOperation(anInsert(client), 3, "u1", Instant.EPOCH)),
            System.currentTimeMillis());

        assertThat(replica.unwrittenCount())
            .as("""
                An operation that arrived from the cluster is already in the log. Writing it again \
                is a duplicate the append would have to filter, and it would make this replica's \
                own flush responsible for numbering something another instance has numbered.""")
            .isZero();
        assertThat(replica.hasPendingBroadcast())
            .as("the clients connected HERE have not seen it yet, which is the whole reason to "
                + "merge a remote operation at all")
            .isTrue();
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
