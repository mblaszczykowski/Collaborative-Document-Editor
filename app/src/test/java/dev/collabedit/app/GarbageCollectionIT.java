package dev.collabedit.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.collabedit.app.domain.DocumentRegistry;
import dev.collabedit.app.domain.DocumentService;
import dev.collabedit.crdt.Op;
import dev.collabedit.crdt.RgaDocument;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Garbage collection tombstone'ów i jego konsekwencja dla bardzo spóźnionych klientów
 * (ADR-0005).
 *
 * <p>Ten test sprawdza najbardziej niewygodną obietnicę całego projektu: że gdy scalenia
 * <b>nie da się</b> wykonać uczciwie, system <b>odmawia jawnie</b>. Dwie alternatywy —
 * doklejenie tekstu w przypadkowe miejsce i ciche zignorowanie operacji — są łatwiejsze
 * do zaimplementowania i obie są gorsze, bo użytkownik dowiaduje się o utracie pracy
 * za późno albo wcale.
 */
class GarbageCollectionIT extends IntegrationTestBase {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    DocumentService documents;
    @Autowired
    DocumentRegistry registry;

    private UUID newDocument(String title) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO documents (id, title) VALUES (?, ?)", id, title);
        return id;
    }

    @Test
    @DisplayName("operacja spóźnionego klienta, zaczepiona o skasowany tombstone, jest odrzucana jawnie")
    void staleOperationIsRejectedExplicitly() throws Exception {
        UUID document = newDocument("GC");

        // Replika 301 pisze tekst i wysyła go do serwera.
        RgaDocument active = new RgaDocument(301);
        documents.submit(document, active.insert(0, "abcdef"), "u1");

        // Replika 302 widziała ten sam tekst i — będąc offline — dopisuje coś NA KOŃCU,
        // czyli zaczepia swoją operację o ostatni znak „f”.
        //
        // Zaczepienie o koniec, a nie o środek tekstu, jest tu istotne i wynika z realnego
        // ograniczenia GC: tombstone leżący w środku dokumentu jest ORIGINEM następnego
        // bloku, więc nie wolno go fizycznie usunąć niezależnie od stabilności przyczynowej.
        // Kolekcjonowalne są wyłącznie tombstone'y, do których nic nie referuje — w praktyce
        // ogon dokumentu. Patrz ADR-0005, sekcja o faktycznym zasięgu GC.
        RgaDocument late = new RgaDocument(302);
        late.applyAll(java.util.List.of(lastOperationOf(document)));
        Op staleOperation = late.insert(6, "XYZ");

        // W tym czasie „f” zostaje usunięte przez kogoś innego.
        documents.submit(document, active.delete(5, 1), "u1");

        // Replika 301 melduje postęp — bez tego stabilność przyczynowa nie ma z czego
        // powstać i GC nigdy się nie odbędzie.
        var replica = registry.get(document);
        replica.reportProgress(301, active.version(), System.currentTimeMillis());

        // Dosyć operacji, żeby przekroczyć próg snapshotowania (w testach 20) — GC dzieje się
        // przy snapshotowaniu, bo ono i tak przepisuje całą strukturę.
        for (int i = 0; i < 25; i++) {
            documents.submit(document, active.insert(active.visibleLength(), "z"), "u1");
            replica.reportProgress(301, active.version(), System.currentTimeMillis());
        }
        awaitGc(document);

        assertThat(replica.gcWatermark().isEmpty())
            .as("GC się nie odbył, więc test nie sprawdza tego, co miał")
            .isFalse();

        // I dopiero teraz spóźniona replika wraca ze swoją operacją.
        assertThatThrownBy(() -> documents.submit(document, staleOperation, "u2"))
            .as("operacja bez punktu zaczepienia została przyjęta — tekst wylądowałby "
                + "w przypadkowym miejscu albo zniknął bez śladu")
            .isInstanceOf(DocumentService.ResyncRequiredException.class)
            .hasMessageContaining("garbage collection");
    }

    @Test
    @DisplayName("operacja, która tylko WYPRZEDZIŁA swoją przyczynę, NIE jest odrzucana")
    void operationAheadOfItsCauseIsNotRejected() {
        UUID document = newDocument("Wyprzedzenie");

        RgaDocument first = new RgaDocument(311);
        Op insert = first.insert(0, "abcdef");

        RgaDocument second = new RgaDocument(312);
        second.apply(insert);
        Op dependent = second.insert(3, "XYZ");

        // Operacja zależna dociera PRZED swoją przyczyną. To jest normalna kolej rzeczy
        // w sieci i musi zostać zbuforowana, a nie odrzucona — warunek odmowy jest celowo
        // wąski (brak originu ORAZ pozycja poniżej granicy GC), bo zbyt szeroki gubiłby
        // poprawne operacje w imię ochrony przed ich gubieniem.
        documents.submit(document, dependent, "u2");
        assertThat(documents.textOf(document))
            .as("operacja bez przyczyny nie powinna jeszcze nic zmienić")
            .isEmpty();

        documents.submit(document, insert, "u1");

        assertThat(documents.textOf(document))
            .as("po dotarciu przyczyny zbuforowana operacja musi zostać zastosowana")
            .isEqualTo("abcXYZdef");
    }

    /** Pierwsza operacja dokumentu — potrzebna, żeby druga replika zobaczyła ten sam stan. */
    private Op lastOperationOf(UUID document) throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            var stored = documents.operationsSince(document, 0);
            if (!stored.isEmpty()) {
                return stored.getFirst().op();
            }
            Thread.sleep(20);
        }
        throw new AssertionError("operacja nie została zapisana");
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
