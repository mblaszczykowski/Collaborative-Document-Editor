package dev.collabedit.app;

import static org.assertj.core.api.Assertions.assertThat;

import dev.collabedit.app.auth.TokenService;
import dev.collabedit.app.persist.DocumentCatalog;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Katalog dokumentów (CP11) — lista, wyszukiwanie, miękkie usuwanie, podgląd.
 *
 * <table>
 *   <caption>Test → failure mode</caption>
 *   <tr><th>Test</th><th>Co wyłapuje</th></tr>
 *   <tr><td>{@code softDeleteKeepsOperationLog}</td>
 *       <td><b>Nieodwracalne zniszczenie źródła prawdy.</b> {@code operations} ma
 *           {@code ON DELETE CASCADE} na {@code doc_id}, więc jedno {@code DELETE} na
 *           dokumencie kasuje całą jego historię — tabelę, która wg ADR-0003 jest wyłącznie
 *           dopisywana. Nie da się tego cofnąć i nie widać tego, dopóki ktoś nie poprosi
 *           o przywrócenie.</td></tr>
 *   <tr><td>{@code deletedDocumentDisappearsFromList}</td>
 *       <td>Usunięcie, które niczego nie chowa. Bez tego „usuń" nie robi nic widocznego.</td></tr>
 *   <tr><td>{@code restoreBringsDocumentBack}</td>
 *       <td>Miękkie usuwanie bez drogi powrotnej — czyli twarde usuwanie z dodatkowym
 *           kosztem miejsca w bazie i bez żadnej z jego zalet.</td></tr>
 *   <tr><td>{@code previewIsMaterialisedAtSnapshot}</td>
 *       <td>Lista dokumentów bez podglądu musiałaby ładować replikę CRDT dla każdej
 *           pozycji. Poprawność cała, koszt otwarcia listy rośnie z liczbą dokumentów
 *           razy ich rozmiar.</td></tr>
 *   <tr><td>{@code searchFindsByTitleAndContent}</td>
 *       <td>Indeks pełnotekstowy nieobejmujący treści — wyszukiwarka znajdująca wyłącznie
 *           to, co użytkownik sam nazwał.</td></tr>
 * </table>
 */
class DocumentCatalogIT extends IntegrationTestBase {

    private static final java.time.Duration PATIENCE = java.time.Duration.ofSeconds(10);

    @LocalServerPort
    int port;

    @Autowired
    DocumentCatalog catalog;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TokenService tokens;

    private UUID newDocument(String title) {
        UUID id = UUID.randomUUID();
        catalog.create(id, title, null);
        return id;
    }

    private int operationRows(UUID documentId) {
        Integer rows = jdbc.queryForObject(
            "SELECT COUNT(*) FROM operations WHERE doc_id = ?", Integer.class, documentId);
        return rows == null ? 0 : rows;
    }

    @Test
    @DisplayName("miękkie usunięcie NIE tyka logu operacji")
    void softDeleteKeepsOperationLog() throws Exception {
        UUID document = newDocument("Do usunięcia");

        try (TestStompClient anna = new TestStompClient(
            port, tokens.issue(new TokenService.Principal(
                UUID.randomUUID().toString(), "Anna", "#C1592E")), document, 901)) {
            anna.insert(0, "treść, której nie wolno stracić");
            anna.awaitCondition(() -> anna.lastAckSeq() > 0, PATIENCE, "zapis potwierdzony");
        }

        int before = operationRows(document);
        assertThat(before).isPositive();

        assertThat(catalog.softDelete(document)).isTrue();

        assertThat(operationRows(document))
            .as("usunięcie dokumentu skasowało jego log operacji — to jest nieodwracalne")
            .isEqualTo(before);
    }

    @Test
    @DisplayName("usunięty dokument znika z listy i pojawia się w koszu")
    void deletedDocumentDisappearsFromList() {
        UUID document = newDocument("Znikający");
        assertThat(catalog.list(null, 500)).extracting(DocumentCatalog.DocumentSummary::id)
            .contains(document);

        catalog.softDelete(document);

        assertThat(catalog.list(null, 500)).extracting(DocumentCatalog.DocumentSummary::id)
            .doesNotContain(document);
        assertThat(catalog.listDeleted(500)).extracting(DocumentCatalog.DocumentSummary::id)
            .contains(document);
        assertThat(catalog.find(document)).isEmpty();
    }

    @Test
    @DisplayName("przywrócenie wraca dokument na listę")
    void restoreBringsDocumentBack() {
        UUID document = newDocument("Wracający");
        catalog.softDelete(document);

        assertThat(catalog.restore(document)).isTrue();
        assertThat(catalog.find(document)).isPresent();
        // Powtórzone przywrócenie nie ma czego przywracać i mówi to wprost.
        assertThat(catalog.restore(document)).isFalse();
    }

    @Test
    @DisplayName("powtórzone usunięcie nie przesuwa daty usunięcia")
    void repeatedDeleteIsIdempotent() {
        UUID document = newDocument("Dwa razy");

        assertThat(catalog.softDelete(document)).isTrue();
        assertThat(catalog.softDelete(document))
            .as("drugie usunięcie zgłosiło sukces — czyli przestawiło datę na dzisiaj")
            .isFalse();
    }

    @Test
    @DisplayName("podgląd treści powstaje przy snapshotowaniu, bez ładowania repliki przez listę")
    void previewIsMaterialisedAtSnapshot() throws Exception {
        UUID document = newDocument("Z podglądem");

        try (TestStompClient anna = new TestStompClient(
            port, tokens.issue(new TokenService.Principal(
                UUID.randomUUID().toString(), "Anna", "#C1592E")), document, 902)) {
            // Próg snapshotowania w testach to 20 operacji.
            for (int i = 0; i < 25; i++) {
                anna.insert(i, "x");
            }
            anna.awaitCondition(() -> anna.lastAckSeq() >= 25, PATIENCE, "zapis potwierdzony");
        }

        for (int attempt = 0; attempt < 200; attempt++) {
            if (!catalog.find(document).orElseThrow().preview().isEmpty()) {
                break;
            }
            Thread.sleep(20);
        }

        assertThat(catalog.find(document).orElseThrow().preview())
            .as("podgląd nie powstał — lista dokumentów musiałaby ładować repliki CRDT")
            .isEqualTo("x".repeat(25));
    }

    @Test
    @DisplayName("wyszukiwanie znajduje po tytule i po treści")
    void searchFindsByTitleAndContent() throws Exception {
        String marker = "kwantowy" + System.nanoTime();
        UUID byTitle = newDocument("Raport " + marker);

        assertThat(catalog.search(marker, 50))
            .as("wyszukiwanie nie znajduje po tytule")
            .extracting(DocumentCatalog.DocumentSummary::id)
            .contains(byTitle);

        // Treść wchodzi do indeksu przez preview_text, czyli dopiero po snapshocie.
        UUID byContent = newDocument("Bez znacznika w tytule");
        String contentMarker = "fotoniczny" + System.nanoTime();
        try (TestStompClient anna = new TestStompClient(
            port, tokens.issue(new TokenService.Principal(
                UUID.randomUUID().toString(), "Anna", "#C1592E")), byContent, 903)) {
            for (int i = 0; i < 25; i++) {
                anna.insert(anna.text().length(), i == 0 ? contentMarker : " ");
            }
            anna.awaitCondition(() -> anna.lastAckSeq() >= 25, PATIENCE, "zapis potwierdzony");
        }

        boolean found = false;
        for (int attempt = 0; attempt < 200 && !found; attempt++) {
            found = catalog.search(contentMarker, 50).stream()
                .anyMatch(d -> d.id().equals(byContent));
            if (!found) {
                Thread.sleep(20);
            }
        }
        assertThat(found)
            .as("wyszukiwanie nie obejmuje treści — znajduje tylko to, co użytkownik sam nazwał")
            .isTrue();
    }
}
