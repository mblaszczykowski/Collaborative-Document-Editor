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

/**
 * Eviction is a claim, not a suggestion.
 *
 * <h2>The failure this pins</h2>
 * The registry used to decide whether a replica could be released in two steps: ask
 * {@code unwrittenCount() > 0}, then remove it from the map. Between those steps another thread
 * could take the replica from the registry and merge an operation into it. The operation then sat
 * in a replica <b>nothing iterates</b> — {@code flushAll} and {@code broadcastAll} both walk
 * {@code registry.loaded()} — so it was never written and never broadcast.
 *
 * <p>The reason it is worth closing rather than documenting is how quiet it is. No exception, no
 * log line, and no permanent data loss either: the client never gets an acknowledgement, so it
 * keeps the operation in its offline buffer and resends on reconnect. Until then the author's
 * collaborators simply do not see the edit, and nothing anywhere says why.
 *
 * <p>These are unit tests rather than integration tests on purpose. The race needs a specific
 * interleaving, and reproducing it through a live registry under a real memory limit would be a
 * test that passes for timing reasons rather than structural ones — the kind
 * {@code ClusterChaosIT} already demonstrates the limits of. What is asserted here is the
 * invariant that makes the interleaving impossible.
 */
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
        // Arrived from the cluster: already durable, so nothing to write — but the clients
        // connected here have not seen it yet. Through applyRemote, which is the path that actually
        // produces this state; the single-operation enqueue it used to call had no production
        // caller, so the test was reaching for a door nothing else opens.
        replica.applyRemote(
            List.of(new StoredOperation(anInsert(client), 1, "u1", Instant.EPOCH)),
            System.currentTimeMillis());

        assertThat(replica.tryEvict())
            .as("""
                Only the write buffer was checked, so a replica still owing a broadcast was \
                evicted. Nothing is lost from the log, but the clients on this instance never see \
                the edit — which is the silent half of the same bug.""")
            .isFalse();
    }

    /**
     * The assertion that actually closes the window.
     *
     * <p>Claiming the replica has to make later writes <b>fail</b>, not merely be unlikely. If
     * this throws nothing, the two-step check is back and the race is back with it — the eviction
     * simply happens to win more often.
     */
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
                nothing will ever flush or broadcast it, and no error would have been raised — \
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

    /**
     * A replica in the middle of writing to the log refuses to be claimed.
     *
     * <h2>The window this closes</h2>
     * {@code drainForFlush} empties BOTH buffers and only then raises the in-flight counter, and
     * the append that follows runs outside the lock — it is a JDBC round trip holding the
     * document's row lock. For that whole duration the two emptiness checks said "idle", so a
     * replica writing up to {@code op-batch-max-size} operations was a legal eviction candidate.
     *
     * <p>What that costs, when the append then throws: {@code requeueUnwritten} hits
     * {@code refuseIfEvicted}, and up to a hundred operations are visible on every connected client
     * and absent from the source of truth. Anti-entropy cannot see it — the log's head is not ahead
     * of {@code appliedServerSeq}, so no gap exists to detect — and the text silently rolls back at
     * the next restart.
     */
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
                means a failed append cannot put its operations back — they are already on every \
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

    /**
     * Reading is deliberately still allowed.
     *
     * <p>An evicted replica holds correct state — it is simply no longer the canonical one — and
     * a caller that already has the text in hand should not have that fail. Refusing reads too
     * would turn a narrow write-ordering rule into a broad lifecycle rule, and every read path
     * would need a retry for no benefit.
     */
    @Test
    @DisplayName("reads still work on an evicted replica, on purpose")
    void readingAnEvictedReplicaStillWorks() {
        DocumentReplica replica = replica();
        RgaDocument client = new RgaDocument(2, new TreapBlockList());
        replica.merge(anInsert(client), "u1", null, System.currentTimeMillis());
        replica.drainForFlush(100);
        // flushCompleted, and this line is the fix rather than setup noise. This test used to
        // drain and then assert tryEvict() == true — that is, it PINNED the defect above as
        // intended behaviour, which is how a bug survives a test suite. Ending the flush is what
        // the production path does on every route out, including failure.
        replica.flushCompleted(1);

        assertThat(replica.tryEvict()).isTrue();
        assertThat(replica.withLock(RgaDocument::text)).isEqualTo("x");
    }
}
