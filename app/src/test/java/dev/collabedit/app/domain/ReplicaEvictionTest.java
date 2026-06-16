package dev.collabedit.app.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.collabedit.crdt.Op;
import dev.collabedit.crdt.RgaDocument;
import dev.collabedit.crdt.TreapBlockList;
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
        // connected here have not seen it yet.
        replica.enqueueForBroadcast(anInsert(client), System.currentTimeMillis());

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
        assertThatThrownBy(
            () -> forBroadcast.enqueueForBroadcast(second, System.currentTimeMillis()))
            .isInstanceOf(DocumentReplica.StaleReplicaException.class);
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

        assertThat(replica.tryEvict()).isTrue();
        assertThat(replica.withLock(RgaDocument::text)).isEqualTo("x");
    }
}
