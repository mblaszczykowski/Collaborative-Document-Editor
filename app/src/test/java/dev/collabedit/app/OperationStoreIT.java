package dev.collabedit.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.collabedit.app.domain.DocumentService;
import dev.collabedit.app.persist.OperationStore;
import dev.collabedit.app.persist.PendingOperation;
import dev.collabedit.app.persist.StoredOperation;
import dev.collabedit.crdt.Op;
import dev.collabedit.crdt.RgaDocument;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Batched writes to the operation log (the cluster design).
 *
 * <p>The tests target two properties the rest of the system rests on and that cannot be checked
 * without a real database: <b>write idempotence</b> (which follows from the primary key) and the
 * <b>continuity of {@code server_seq}</b> (which detection of lost Redis messages is based on).
 */
class OperationStoreIT extends IntegrationTestBase {

    @Autowired
    OperationStore operations;
    @Autowired
    DocumentService documents;
    @Autowired
    JdbcTemplate jdbc;

    private UUID newDocument() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO documents (id, title) VALUES (?, ?)", id, "store-test");
        return id;
    }

    private static List<PendingOperation> pending(List<Op> ops) {
        List<PendingOperation> batch = new ArrayList<>(ops.size());
        for (Op op : ops) {
            batch.add(new PendingOperation(op, "u1", null));
        }
        return batch;
    }

    @Test
    @DisplayName("a batch with duplicates mixed in stores only the new operations")
    void batchWithMixedDuplicates() {
        UUID document = newDocument();
        RgaDocument client = new RgaDocument(1);

        List<Op> first = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            first.add(client.insert(i, "a"));
        }
        assertThat(operations.append(document, pending(first))).hasSize(30);

        List<Op> second = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            second.add(client.insert(client.visibleLength(), "b"));
        }

        // A batch with OLD and NEW operations interleaved — exactly what a client retransmitting
        // its buffer after reconnecting sends. The filtering has to be selective: store the new
        // ones, skip the known ones, and leave no gap in the numbering.
        List<Op> mixed = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            mixed.add(first.get(i));
            mixed.add(second.get(i));
        }

        List<StoredOperation> appended = operations.append(document, pending(mixed));

        assertThat(appended)
            .as("the wrong operations were stored — duplicate filtering misbehaved")
            .hasSize(30);
        assertThat(appended.stream().map(stored -> stored.op().id()).toList())
            .containsExactlyElementsOf(second.stream().map(Op::id).toList());

        Integer rows = jdbc.queryForObject(
            "SELECT COUNT(*) FROM operations WHERE doc_id = ?", Integer.class, document);
        assertThat(rows).isEqualTo(60);

        assertNoGapsInSequence(document, 60);
    }

    @Test
    @DisplayName("repeating a WHOLE batch stores nothing and consumes no sequence numbers")
    void fullyDuplicateBatchConsumesNoSequenceNumbers() {
        UUID document = newDocument();
        RgaDocument client = new RgaDocument(2);

        List<Op> batch = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            batch.add(client.insert(i, "x"));
        }
        operations.append(document, pending(batch));

        assertThat(operations.append(document, pending(batch)))
            .as("the repeated batch was stored a second time")
            .isEmpty();

        // The numbers must be untouched: spending them on a rejected batch would create exactly
        // the gap in the numbering that filtering-before-allocation guards against.
        Long nextSeq = jdbc.queryForObject(
            "SELECT next_server_seq FROM documents WHERE id = ?", Long.class, document);
        assertThat(nextSeq).isEqualTo(11L);
        assertNoGapsInSequence(document, 10);
    }

    @Test
    @DisplayName("a large batch — the filtering query has fixed text regardless of size")
    void largeBatch() {
        UUID document = newDocument();
        RgaDocument client = new RgaDocument(3);

        // 500 operations, five times the production op-batch-max-size. The previous
        // implementation built SQL with five hundred tuples here; the current one sends two
        // arrays, and the query text is the same as for a single operation.
        List<Op> batch = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            batch.add(client.insert(i, "y"));
        }

        assertThat(operations.append(document, pending(batch))).hasSize(500);
        assertNoGapsInSequence(document, 500);
    }

    // ------------------------------------------------------------------
    // Replica identifiers belong to somebody (CP12, completed)
    // ------------------------------------------------------------------

    private String newUser(String name) {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO users (id, display_name, color) VALUES (?, ?, ?)",
            id, name, "#4C5FD5");
        return id;
    }



    private void assertNoGapsInSequence(UUID document, int expectedCount) {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM operations WHERE doc_id = ?", Integer.class, document);
        Long max = jdbc.queryForObject(
            "SELECT MAX(server_seq) FROM operations WHERE doc_id = ?", Long.class, document);
        assertThat(count).isEqualTo(expectedCount);
        assertThat(max)
            .as("the server_seq numbering has a gap: %d operations, highest number %d", count, max)
            .isEqualTo(Long.valueOf(expectedCount));
    }
}
