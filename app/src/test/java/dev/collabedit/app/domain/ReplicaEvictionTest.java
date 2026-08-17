package dev.collabedit.app.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.collabedit.app.persist.StoredOperation;
import dev.collabedit.crdt.Op;
import dev.collabedit.crdt.RgaDocument;
import dev.collabedit.crdt.TreapBlockList;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("A replica claimed for eviction refuses further work")
class ReplicaEvictionTest {
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
    @DisplayName("an idle replica can be claimed")
    void idleReplicaCanBeEvicted() {
        assertThat(replica().tryEvict()).isTrue();
    }

    @Test
    @DisplayName("a replica holding unwritten work refuses to be claimed")
    void replicaWithUnwrittenWorkRefusesEviction() {
        DocumentReplica replica = replica();
        RgaDocument client = new RgaDocument(2, new TreapBlockList());
        replica.merge(anInsert(client), "u1", null, System.currentTimeMillis());

        assertThat(replica.tryEvict())
            .as("""
                A replica with unwritten operations allowed itself to be evicted. Those \
                operations have been accepted from a client and are not durable yet, so dropping \
                the replica discards them.""")
            .isFalse();
    }

    @Test
    @DisplayName("a replica holding only unbroadcast work also refuses")
    void replicaWithPendingBroadcastRefusesEviction() {
        DocumentReplica replica = replica();
        RgaDocument client = new RgaDocument(2, new TreapBlockList());
        replica.applyRemote(
            List.of(new StoredOperation(anInsert(client), 1, "u1", Instant.EPOCH)),
            System.currentTimeMillis());

        assertThat(replica.tryEvict())
            .as("""
                Only the write buffer was checked, so a replica still owing a broadcast was \
                evicted. Nothing is lost from the log, but the clients on this instance never see \
                the edit - which is the silent half of the same bug.""")
            .isFalse();
    }

    @Test
    @DisplayName("once claimed, a merge is refused rather than silently accepted")
    void mergingIntoAnEvictedReplicaIsRefused() {
        DocumentReplica replica = replica();
        assertThat(replica.tryEvict()).isTrue();

        RgaDocument client = new RgaDocument(2, new TreapBlockList());
        Op.Insert op = anInsert(client);

        assertThatThrownBy(() -> replica.merge(op, "u1", null, System.currentTimeMillis()))
            .as("""
                An evicted replica accepted an operation. It is no longer in the registry, so \
                nothing will ever flush or broadcast it, and no error would have been raised - \
                see DocumentReplica.tryEvict.""")
            .isInstanceOf(DocumentReplica.StaleReplicaException.class);
    }

    @Test
    @DisplayName("the other two write paths are refused as well")
    void everyWritePathIsRefusedAfterEviction() {
        RgaDocument client = new RgaDocument(2, new TreapBlockList());
        Op.Insert first = anInsert(client);
        Op.Insert second = anInsert(client);

        DocumentReplica forWrite = replica();
        assertThat(forWrite.tryEvict()).isTrue();
        assertThatThrownBy(
            () -> forWrite.enqueueForWrite(first, "u1", System.currentTimeMillis()))
            .isInstanceOf(DocumentReplica.StaleReplicaException.class);

        DocumentReplica forBroadcast = replica();
        assertThat(forBroadcast.tryEvict()).isTrue();
        assertThatThrownBy(() -> forBroadcast.applyRemote(
            List.of(new StoredOperation(second, 1, "u1", Instant.EPOCH)),
            System.currentTimeMillis()))
            .isInstanceOf(DocumentReplica.StaleReplicaException.class);
    }

    @Test
    @DisplayName("a replica with a flush in flight refuses eviction, though both buffers are empty")
    void replicaWithAFlushInFlightRefusesEviction() {
        DocumentReplica replica = replica();
        RgaDocument client = new RgaDocument(2, new TreapBlockList());
        replica.merge(anInsert(client), "u1", null, System.currentTimeMillis());

        DocumentReplica.OutboundBatch outbound = replica.drainForFlush(100);
        assertThat(outbound.writes())
            .as("the test premise: the drain has to have taken something for a flush to be in flight")
            .hasSize(1);
        assertThat(replica.unwrittenCount())
            .as("the drain empties the buffer, which is exactly why the buffer check is not enough")
            .isZero();

        assertThat(replica.tryEvict())
            .as("""
                Both buffers are empty and the write has not reached the database yet, so every \
                signal except the in-flight counter says this replica is idle. Evicting it here \
                means a failed append cannot put its operations back - they are already on every \
                client's screen and in no log.""")
            .isFalse();
    }

    @Test
    @DisplayName("once the flush completes, the replica can be claimed again")
    void completingTheFlushAllowsEviction() {
        DocumentReplica replica = replica();
        RgaDocument client = new RgaDocument(2, new TreapBlockList());
        replica.merge(anInsert(client), "u1", null, System.currentTimeMillis());
        replica.drainForFlush(100);

        replica.flushCompleted(1);

        assertThat(replica.tryEvict())
            .as("the write finished, so the replica holds nothing that eviction could lose")
            .isTrue();
    }

    @Test
    @DisplayName("reads still work on an evicted replica, on purpose")
    void readingAnEvictedReplicaStillWorks() {
        DocumentReplica replica = replica();
        RgaDocument client = new RgaDocument(2, new TreapBlockList());
        replica.merge(anInsert(client), "u1", null, System.currentTimeMillis());
        replica.drainForFlush(100);
        replica.flushCompleted(1);

        assertThat(replica.tryEvict()).isTrue();
        assertThat(replica.withLock(RgaDocument::text)).isEqualTo("x");
    }
}
