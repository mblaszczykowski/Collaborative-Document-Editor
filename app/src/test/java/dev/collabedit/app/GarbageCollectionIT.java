package dev.collabedit.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.collabedit.app.domain.DocumentRegistry;
import dev.collabedit.app.domain.RegistryTestAccess;
import dev.collabedit.app.domain.DocumentService;
import dev.collabedit.crdt.Op;
import dev.collabedit.crdt.RgaDocument;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Tombstone garbage collection and its consequence for very late clients.
 *
 * <p>This test checks the most uncomfortable promise in the whole project: that when a merge
 * <b>cannot</b> be performed honestly, the system <b>refuses explicitly</b>. The two alternatives —
 * gluing the text into a random place and silently ignoring the operation — are easier to implement
 * and both are worse, because the user learns their work was lost too late or never.
 */
class GarbageCollectionIT extends IntegrationTestBase {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    DocumentService documents;
    @Autowired
    DocumentRegistry registry;
    @Autowired
    dev.collabedit.app.persist.SnapshotStore snapshots;

    private UUID newDocument(String title) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO documents (id, title) VALUES (?, ?)", id, title);
        return id;
    }

    @Test
    @DisplayName("a late client's operation anchored to a collected tombstone is refused explicitly")
    void staleOperationIsRejectedExplicitly() throws Exception {
        UUID document = newDocument("GC");

        // Replica 301 writes some text and sends it to the server.
        RgaDocument active = new RgaDocument(301);
        documents.submit(document, active.insert(0, "abcdef"), "u1");

        // Replica 302 saw the same text and — being offline — appends something AT THE END,
        // that is, anchors its operation to the last character "f".
        //
        // Anchoring to the end rather than the middle of the text matters here and follows from a
        // real limitation of GC: a tombstone in the middle of a document is the ORIGIN of the next
        // block, so it must not be physically removed regardless of causal stability. Only
        // tombstones nothing references are collectable — in practice, the document's tail. See
        // the tombstone-collection design, the section on GC's actual reach.
        RgaDocument late = new RgaDocument(302);
        late.applyAll(java.util.List.of(lastOperationOf(document)));
        Op staleOperation = late.insert(6, "XYZ");

        // Meanwhile "f" is deleted by somebody else.
        documents.submit(document, active.delete(5, 1), "u1");

        // Replica 301 reports progress — without it causal stability has nothing to be computed
        // from and GC never happens.
        var replica = registry.get(document);
        replica.reportProgress(301, active.version(), System.currentTimeMillis());

        // Enough operations to cross the snapshot threshold (20 in tests) — GC happens at
        // snapshot time, because that rewrites the whole structure anyway.
        for (int i = 0; i < 25; i++) {
            documents.submit(document, active.insert(active.visibleLength(), "z"), "u1");
            replica.reportProgress(301, active.version(), System.currentTimeMillis());
        }
        awaitGc(document);

        assertThat(replica.gcWatermark().isEmpty())
            .as("GC did not run, so the test is not checking what it was meant to")
            .isFalse();

        // And only now does the late replica come back with its operation.
        assertThatThrownBy(() -> documents.submit(document, staleOperation, "u2"))
            .as("an operation with no anchor point was accepted — the text would land in a "
                + "random place or vanish without a trace")
            .isInstanceOf(DocumentService.ResyncRequiredException.class)
            .hasMessageContaining("garbage collection");

        // THE REPLICA IS DROPPED FROM MEMORY, and the same operation comes back.
        //
        // This is the case the whole mechanism actually meets in production and the case this test
        // did not cover: it held one replica for its whole duration, so the boundary never had to
        // survive anything. The boundary used to live in a field of DocumentReplica, reset to
        // empty by every load — and a replica is evicted after five minutes of quiet, while the
        // window it is compared against is seven days. So in practice the refusal below did not
        // happen; the operation was buffered for ever, written to the log, and acknowledged.
        // awaitGc returns as soon as the boundary exists IN MEMORY — gc sets it inside
        // withConsistentState, and the snapshot is written just afterwards. Forgetting the replica
        // inside that window reloads from the previous snapshot, which has no boundary, and the
        // assertion below fails for a reason that is about timing rather than about the mechanism.
        awaitPersistedGcWatermark(document);
        RegistryTestAccess.forget(registry, document);

        assertThat(registry.get(document).gcWatermark().isEmpty())
            .as("the GC boundary did not survive the reload, so the refusal below is judged "
                + "against nothing")
            .isFalse();

        assertThatThrownBy(() -> documents.submit(document, staleOperation, "u2"))
            .as("after a reload the same operation was accepted — the refusal only ever worked "
                + "while the replica happened to still be in memory")
            .isInstanceOf(DocumentService.ResyncRequiredException.class)
            .hasMessageContaining("garbage collection");
    }

    @Test
    @DisplayName("an operation that merely ARRIVED BEFORE its cause is NOT rejected")
    void operationAheadOfItsCauseIsNotRejected() {
        UUID document = newDocument("Out of order");

        RgaDocument first = new RgaDocument(311);
        Op insert = first.insert(0, "abcdef");

        RgaDocument second = new RgaDocument(312);
        second.apply(insert);
        Op dependent = second.insert(3, "XYZ");

        // A dependent operation arrives BEFORE its cause. That is the normal course of events on
        // a network and has to be buffered, not rejected — the refusal condition is deliberately
        // narrow (no origin AND a position below the GC boundary), because too broad a condition
        // would lose correct operations in the name of protecting against losing them.
        documents.submit(document, dependent, "u2");
        assertThat(documents.textOf(document))
            .as("an operation without its cause should not change anything yet")
            .isEmpty();

        documents.submit(document, insert, "u1");

        assertThat(documents.textOf(document))
            .as("once the cause arrives, the buffered operation has to be applied")
            .isEqualTo("abcXYZdef");
    }

    /** The document's first operation — needed so the second replica sees the same state. */
    private Op lastOperationOf(UUID document) throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            var stored = documents.operationsSince(document, 0, 10);
            if (!stored.isEmpty()) {
                return stored.getFirst().op();
            }
            Thread.sleep(20);
        }
        throw new AssertionError("the operation was never stored");
    }

    /**
     * Waits for a snapshot that actually CARRIES the boundary.
     *
     * <p>Distinct from {@link #awaitGc}, which watches the in-memory replica: the sweep sets the
     * boundary and writes the snapshot in that order, so there is a window in which the first is
     * true and the second is not. Everything about surviving a reload depends on the second.
     */
    private void awaitPersistedGcWatermark(UUID document) throws InterruptedException {
        for (int attempt = 0; attempt < 200; attempt++) {
            boolean stored = snapshots.findLatestUpTo(document, Long.MAX_VALUE)
                .map(loaded -> !loaded.snapshot().gcWatermark().isEmpty())
                .orElse(false);
            if (stored) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError(
            "no snapshot carrying a GC boundary was written for document " + document);
    }

    private void awaitGc(UUID document) throws InterruptedException {
        for (int attempt = 0; attempt < 150; attempt++) {
            if (!registry.get(document).gcWatermark().isEmpty()) {
                return;
            }
            Thread.sleep(20);
        }
    }
}
