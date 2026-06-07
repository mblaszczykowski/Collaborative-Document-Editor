package dev.collabedit.app;

import static org.assertj.core.api.Assertions.assertThat;

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
 * Zapis wsadowy do logu operacji (ADR-0003, ADR-0004).
 *
 * <p>Testy celują w dwie własności, na których stoi reszta systemu i których nie da się
 * sprawdzić bez prawdziwej bazy: <b>idempotencję zapisu</b> (wynikającą z klucza głównego)
 * oraz <b>ciągłość {@code server_seq}</b> (na której opiera się wykrywanie zgubionych
 * wiadomości Redisa).
 */
class OperationStoreIT extends IntegrationTestBase {

    @Autowired
    OperationStore operations;
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
    @DisplayName("wsad z wymieszanymi duplikatami zapisuje wyłącznie nowe operacje")
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

        // Wsad, w którym STARE i NOWE operacje są przemieszane — dokładnie to, co przysyła
        // klient retransmitujący bufor po ponownym połączeniu. Odsiew musi zadziałać
        // wybiórczo: zapisać nowe, pominąć znane, i nie zostawić dziury w numeracji.
        List<Op> mixed = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            mixed.add(first.get(i));
            mixed.add(second.get(i));
        }

        List<StoredOperation> appended = operations.append(document, pending(mixed));

        assertThat(appended)
            .as("zapisano nie te operacje, co trzeba — odsiew duplikatów zadziałał źle")
            .hasSize(30);
        assertThat(appended.stream().map(stored -> stored.op().id()).toList())
            .containsExactlyElementsOf(second.stream().map(Op::id).toList());

        Integer rows = jdbc.queryForObject(
            "SELECT COUNT(*) FROM operations WHERE doc_id = ?", Integer.class, document);
        assertThat(rows).isEqualTo(60);

        assertNoGapsInSequence(document, 60);
    }

    @Test
    @DisplayName("powtórzenie CAŁEGO wsadu nie zapisuje niczego i nie zużywa numerów")
    void fullyDuplicateBatchConsumesNoSequenceNumbers() {
        UUID document = newDocument();
        RgaDocument client = new RgaDocument(2);

        List<Op> batch = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            batch.add(client.insert(i, "x"));
        }
        operations.append(document, pending(batch));

        assertThat(operations.append(document, pending(batch)))
            .as("powtórzony wsad został zapisany drugi raz")
            .isEmpty();

        // Numery muszą zostać nietknięte: zużycie ich na odrzucony wsad zrobiłoby dokładnie
        // tę dziurę w numeracji, przed którą broni odsiew przed przydziałem (ADR-0004).
        Long nextSeq = jdbc.queryForObject(
            "SELECT next_server_seq FROM documents WHERE id = ?", Long.class, document);
        assertThat(nextSeq).isEqualTo(11L);
        assertNoGapsInSequence(document, 10);
    }

    @Test
    @DisplayName("duży wsad — zapytanie odsiewające ma stałą treść niezależnie od rozmiaru")
    void largeBatch() {
        UUID document = newDocument();
        RgaDocument client = new RgaDocument(3);

        // 500 operacji, czyli pięciokrotność produkcyjnego op-batch-max-size. Poprzednia
        // implementacja budowała tu SQL z pięciuset krotkami; obecna przesyła dwie tablice,
        // a tekst zapytania jest ten sam co przy jednej operacji.
        List<Op> batch = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            batch.add(client.insert(i, "y"));
        }

        assertThat(operations.append(document, pending(batch))).hasSize(500);
        assertNoGapsInSequence(document, 500);
    }

    private void assertNoGapsInSequence(UUID document, int expectedCount) {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM operations WHERE doc_id = ?", Integer.class, document);
        Long max = jdbc.queryForObject(
            "SELECT MAX(server_seq) FROM operations WHERE doc_id = ?", Long.class, document);
        assertThat(count).isEqualTo(expectedCount);
        assertThat(max)
            .as("numeracja server_seq ma dziurę: %d operacji, najwyższy numer %d", count, max)
            .isEqualTo(Long.valueOf(expectedCount));
    }
}
