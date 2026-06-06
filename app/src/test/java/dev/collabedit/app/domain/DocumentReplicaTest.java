package dev.collabedit.app.domain;

import static org.assertj.core.api.Assertions.assertThat;

import dev.collabedit.app.persist.PendingOperation;
import dev.collabedit.crdt.Op;
import dev.collabedit.crdt.RgaDocument;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Bufor zapisu repliki. Bez Springa i bez bazy — to jest zachowanie struktury danych,
 * a nie współpracy warstw.
 *
 * <table>
 *   <caption>Test → failure mode</caption>
 *   <tr><th>Test</th><th>Co wyłapuje</th></tr>
 *   <tr><td>{@code drainRespectsLimit}</td>
 *       <td>Limit rozmiaru wsadu nie działa. Objaw nie jest widoczny funkcjonalnie:
 *           zapis nadal jest poprawny, tylko jedna transakcja trzyma blokadę wiersza
 *           dokumentu dowolnie długo, blokując wszystkich innych piszących.</td></tr>
 *   <tr><td>{@code drainPreservesOrderAcrossBatches}</td>
 *       <td>Podział na wsady miesza kolejność. {@code server_seq} nadawany jest w kolejności
 *           wyjmowania z bufora, a ADR-0004 opiera na jego CIĄGŁOŚCI wykrywanie zgubionych
 *           wiadomości — przestawienie operacji psuje kursor resynchronizacji.</td></tr>
 *   <tr><td>{@code drainLeavesNothingBehind}</td>
 *       <td>Operacja zostaje w buforze na zawsze i nigdy nie trafia do logu.
 *           Klient nie dostaje potwierdzenia, więc retransmituje ją w nieskończoność.</td></tr>
 * </table>
 */
class DocumentReplicaTest {

    private static final int LIMIT = 3;

    /**
     * Replika serwera z {@code count} operacjami czekającymi na zapis.
     *
     * <p>Operacje powstają na OSOBNEJ replice („przeglądarka") i dopiero są scalane do repliki
     * serwera. To nie jest ozdobnik: gdyby generować je przez {@code replica.withLock(insert)},
     * byłyby już zastosowane, więc {@code merge} rozpoznałby je jako duplikaty, zwrócił
     * {@code false} i <b>nie dopisał niczego do bufora</b> — test mierzyłby pusty bufor
     * i przechodził niezależnie od tego, co robi limit.
     */
    private DocumentReplica replicaWithPendingOperations(int count) {
        DocumentReplica replica = new DocumentReplica(
            UUID.randomUUID(), new RgaDocument(-1), 0, 0);
        RgaDocument browser = new RgaDocument(1);
        for (int i = 0; i < count; i++) {
            Op op = browser.insert(i, String.valueOf((char) ('a' + i)));
            assertThat(replica.merge(op, "tester", null, 0))
                .as("operacja %d powinna być nowością dla repliki serwera", i)
                .isTrue();
        }
        assertThat(replica.unwrittenCount()).isEqualTo(count);
        return replica;
    }

    @Test
    @DisplayName("wsad nie przekracza zadanego limitu, a reszta zostaje na następne okno")
    void drainRespectsLimit() {
        DocumentReplica replica = replicaWithPendingOperations(10);

        List<PendingOperation> first = replica.drainUnwritten(LIMIT);

        assertThat(first).hasSize(LIMIT);
        assertThat(replica.unwrittenCount())
            .as("reszta musi zostać w buforze, a nie zniknąć")
            .isEqualTo(7);
    }

    @Test
    @DisplayName("kolejność operacji przeżywa podział na wsady")
    void drainPreservesOrderAcrossBatches() {
        DocumentReplica replica = replicaWithPendingOperations(10);

        StringBuilder drained = new StringBuilder();
        List<PendingOperation> batch;
        while (!(batch = replica.drainUnwritten(LIMIT)).isEmpty()) {
            assertThat(batch).hasSizeLessThanOrEqualTo(LIMIT);
            for (PendingOperation pending : batch) {
                drained.append(((Op.Insert) pending.op()).text());
            }
        }

        assertThat(drained.toString())
            .as("server_seq nadawany jest w kolejności wyjmowania — przestawienie psuje ADR-0004")
            .isEqualTo("abcdefghij");
    }

    @Test
    @DisplayName("wielokrotne opróżnianie zabiera wszystko, nic nie zostaje w buforze")
    void drainLeavesNothingBehind() {
        DocumentReplica replica = replicaWithPendingOperations(10);

        int total = 0;
        List<PendingOperation> batch;
        while (!(batch = replica.drainUnwritten(LIMIT)).isEmpty()) {
            total += batch.size();
        }

        assertThat(total).isEqualTo(10);
        assertThat(replica.unwrittenCount()).isZero();
    }

    @Test
    @DisplayName("wsad mniejszy od limitu wychodzi w całości")
    void drainReturnsEverythingBelowLimit() {
        DocumentReplica replica = replicaWithPendingOperations(2);

        assertThat(replica.drainUnwritten(LIMIT)).hasSize(2);
        assertThat(replica.drainUnwritten(LIMIT)).isEmpty();
    }
}
