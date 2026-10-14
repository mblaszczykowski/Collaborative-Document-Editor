package dev.collabedit.app;

import static org.assertj.core.api.Assertions.assertThat;

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
        jdbc.update("INSERT INTO documents (id, title, visibility) VALUES (?, ?, 'link')", id, title);
        return id;
    }

    @Test
    @DisplayName("a late insertion still resolves after tombstone payload GC and snapshot reload")
    void lateInsertionSurvivesGcAndReload() throws Exception {
        UUID document = newDocument("GC");

        RgaDocument active = new RgaDocument(301);
        documents.submit(document, active.insert(0, "abcdef"), "u1");

        RgaDocument late = new RgaDocument(302);
        late.applyAll(java.util.List.of(lastOperationOf(document)));
        Op staleOperation = late.insert(6, "XYZ");

        documents.submit(document, active.delete(5, 1), "u1");

        var replica = registry.get(document);
        documents.recordProgress(document, 301, active.version());

        for (int i = 0; i < 25; i++) {
            documents.submit(document, active.insert(active.visibleLength(), "z"), "u1");
            documents.recordProgress(document, 301, active.version());
        }
        awaitGc(document);

        assertThat(replica.gcWatermark().isEmpty())
            .as("GC did not run, so the test is not checking what it was meant to")
            .isFalse();

        String before = documents.textOf(document);
        documents.submit(document, staleOperation, "u2");
        assertThat(documents.textOf(document))
            .as("the retained identity locates the late insertion without resurrecting deleted text")
            .isEqualTo(before + "XYZ");
        documents.flushAll();

        awaitPersistedGcWatermark(document);
        RegistryTestAccess.forget(registry, document);

        assertThat(registry.get(document).gcWatermark().isEmpty())
            .as("the GC boundary did not survive the reload, so the refusal below is judged "
                + "against nothing")
            .isFalse();

        assertThat(documents.textOf(document)).isEqualTo(before + "XYZ");
        documents.submit(document, staleOperation, "u2");
        assertThat(documents.textOf(document))
            .as("retry after reload recognizes the canonical operation and cannot duplicate its text")
            .isEqualTo(before + "XYZ");
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

        documents.submit(document, dependent, "u2");
        assertThat(documents.textOf(document))
            .as("an operation without its cause should not change anything yet")
            .isEmpty();

        documents.submit(document, insert, "u1");

        assertThat(documents.textOf(document))
            .as("once the cause arrives, the buffered operation has to be applied")
            .isEqualTo("abcXYZdef");
    }

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
