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

@DisplayName("A snapshot is refused unless its label describes the state")
class SnapshotConsistencyTest {
    private static DocumentReplica replica() {
        return replica(0);
    }

    private static DocumentReplica replica(long through) {
        return new DocumentReplica(
            UUID.randomUUID(),
            new RgaDocument(1, new TreapBlockList()),
            through,
            System.currentTimeMillis());
    }

    private static Op.Insert anInsert(RgaDocument on) {
        return on.insert(on.visibleLength(), "x");
    }

    @Test
    @DisplayName("an idle replica may be snapshotted, at its current sequence number")
    void idleReplicaIsConsistent() {
        DocumentReplica replica = replica(6);
        RgaDocument client = new RgaDocument(2, new TreapBlockList());
        replica.applyRemote(
            List.of(new StoredOperation(anInsert(client), 7, "u1", Instant.EPOCH)),
            System.currentTimeMillis());

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
                describes this state - but a snapshot was allowed anyway.""")
            .isEmpty();
    }

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
                says this state is consistent - and it is not: the drained operation is in the \
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

        replica.flushCompleted(1);

        assertThat(replica.withConsistentState((document, seq) -> seq))
            .as("the flush finished, so the state and the number agree again")
            .contains(1L);
    }

    @Test
    @DisplayName("a flush that wrote nothing still ends, so snapshots resume")
    void aFailedFlushStillEnds() {
        DocumentReplica replica = replica();
        RgaDocument client = new RgaDocument(2, new TreapBlockList());
        replica.merge(anInsert(client), "u1", null, System.currentTimeMillis());
        var batch = replica.drainForFlush(100);
        replica.requeueUnwritten(batch.writes());
        replica.flushCompleted(0);

        assertThat(replica.withConsistentState((document, seq) -> seq))
            .as("""
                A failed flush must preserve its speculative writes for retry; a snapshot cannot \
                label them as committed.""")
            .isEmpty();
    }

    @Test
    @DisplayName("remote operations and their label move together, so no snapshot sees them unlabelled")
    void remoteOperationsAreLabelledAsTheyAreApplied() {
        DocumentReplica replica = replica(40);
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
        DocumentReplica replica = replica(8);
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
        DocumentReplica replica = replica(9);
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

    @Test
    void localFlushCannotSkipMissingRemoteSequence() {
        DocumentReplica replica = replica();
        RgaDocument local = new RgaDocument(2);
        replica.merge(local.insert(0, "B"), "u", null, 0);
        replica.drainForFlush(100);
        replica.flushCompleted(2);
        assertThat(replica.appliedServerSeq()).isZero();
        assertThat(replica.withConsistentState((document, seq) -> document.snapshot())).isEmpty();

        RgaDocument remote = new RgaDocument(3);
        replica.applyRemote(List.of(new StoredOperation(remote.insert(0, "A"), 1,
            "other", Instant.EPOCH)), 0);
        assertThat(replica.appliedServerSeq()).isEqualTo(2);
        assertThat(replica.withConsistentState((document, seq) -> document.text())).isPresent();
        assertThat(replica.withLock(RgaDocument::text)).contains("A", "B");
    }

    @Test
    void unresolvedRemoteOperationCannotBeSnapshotted() {
        DocumentReplica replica = replica();
        var waiting = new Op.Insert(new dev.collabedit.crdt.Id(2, 1),
            new dev.collabedit.crdt.Id(2, 0), "B");
        replica.applyRemote(List.of(new StoredOperation(waiting, 1, "u", Instant.EPOCH)), 0);
        assertThat(replica.withConsistentState((document, seq) -> document.snapshot())).isEmpty();
    }

    @Test
    void oldDeleteReplayAfterRestoreDoesNotRebroadcastOrChangeTheSnapshot() {
        RgaDocument author = new RgaDocument(2);
        var inserted = author.insert(0, "abc");
        var deleted = author.delete(0, 3);
        RgaDocument restored = RgaDocument.restore(-1, new TreapBlockList(), author.snapshot());
        var replica = new DocumentReplica(UUID.randomUUID(), restored, 2, 0);
        assertThat(replica.applyRemote(List.of(new StoredOperation(deleted, 2, "u", Instant.EPOCH)), 0))
            .isEmpty();
        assertThat(replica.hasPendingBroadcast()).isFalse();
        assertThat(replica.appliedServerSeq()).isEqualTo(2);
    }
}
