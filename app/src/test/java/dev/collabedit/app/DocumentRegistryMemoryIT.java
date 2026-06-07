package dev.collabedit.app;

import static org.assertj.core.api.Assertions.assertThat;

import dev.collabedit.app.domain.DocumentRegistry;
import dev.collabedit.crdt.RgaDocument;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Górna granica liczby replik trzymanych w pamięci (CP11).
 *
 * <table>
 *   <caption>Test → failure mode</caption>
 *   <tr><th>Test</th><th>Co wyłapuje</th></tr>
 *   <tr><td>{@code limitIsEnforcedOnLoad}</td>
 *       <td>Brak granicy POJEMNOŚCI. Sam próg bezczynności nie ogranicza niczego, gdy
 *           dokumenty są otwierane szybciej, niż wygasają — pamięć rośnie do OutOfMemory,
 *           a każda replika z osobna wygląda na potrzebną.</td></tr>
 *   <tr><td>{@code replicaWithUnwrittenOperationsIsNeverEvicted}</td>
 *       <td><b>Utrata danych.</b> Zwolnienie repliki z niepustym buforem kasuje operacje
 *           przyjęte od klienta i jeszcze niezapisane. Klient dostał już potwierdzenie
 *           scalenia, więc nie retransmituje — zmiana znika bez śladu.</td></tr>
 *   <tr><td>{@code leastRecentlyUsedGoesFirst}</td>
 *       <td>Zwalnianie w przypadkowej kolejności. Poprawność cała, ale dokument właśnie
 *           edytowany bywa wyrzucany przed takim, którego nikt nie tknął — czyli koszt
 *           ponownego ładowania płaci się dokładnie tam, gdzie boli najbardziej.</td></tr>
 * </table>
 */
@SpringBootTest
class DocumentRegistryMemoryIT {

    private static final int LIMIT = 3;

    /**
     * Własny zestaw właściwości zamiast dziedziczenia po {@code IntegrationTestBase}.
     *
     * <p>Powód jest konkretny i kosztował jeden fałszywy alarm: {@code @DynamicPropertySource}
     * ma <b>wyższy priorytet</b> niż {@code @TestPropertySource}, więc nadpisanie okna
     * wsadowania w klasie potomnej było po cichu ignorowane. Bufor niezapisanych operacji
     * był opróżniany co 20 ms i test „repliki z pracą w toku nie wolno zwolnić" mierzył
     * wyścig z harmonogramem zamiast reguły, którą sprawdza — padał, mimo że reguła działa.
     *
     * <p>Kontenery są współdzielone z klasą bazową (są statyczne), więc osobny kontekst
     * kosztuje sekundy, a nie kolejnego Postgresa.
     */
    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", IntegrationTestBase.POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", IntegrationTestBase.POSTGRES::getUsername);
        registry.add("spring.datasource.password", IntegrationTestBase.POSTGRES::getPassword);
        registry.add("spring.data.redis.host", IntegrationTestBase.REDIS::getHost);
        registry.add("spring.data.redis.port", () -> IntegrationTestBase.REDIS.getMappedPort(6379));
        registry.add("otel.sdk.disabled", () -> "true");

        registry.add("collabedit.cluster.max-loaded-documents", () -> LIMIT);
        // Okno wsadowania praktycznie wyłączone — patrz komentarz powyżej.
        registry.add("collabedit.persistence.op-batch-window-ms", () -> 600_000);
    }

    @Autowired
    DocumentRegistry registry;
    @Autowired
    JdbcTemplate jdbc;

    private UUID newDocument(String title) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO documents (id, title) VALUES (?, ?)", id, title);
        return id;
    }

    @Test
    @DisplayName("liczba replik w pamięci nie przekracza limitu")
    void limitIsEnforcedOnLoad() {
        for (int i = 0; i < LIMIT * 4; i++) {
            registry.get(newDocument("pamiec-" + i));
        }

        assertThat(registry.loaded())
            .as("rejestr rośnie bez ograniczeń — próg bezczynności nie jest granicą pamięci")
            .hasSizeLessThanOrEqualTo(LIMIT);
    }

    @Test
    @DisplayName("replika z niezapisanymi operacjami nie jest zwalniana nawet ponad limitem")
    void replicaWithUnwrittenOperationsIsNeverEvicted() {
        UUID busy = newDocument("zajety");
        var replica = registry.get(busy);

        // Operacja przyjęta, ale jeszcze niezapisana — dokładnie ten stan, w którym
        // zwolnienie repliki oznacza cichą utratę pracy użytkownika.
        RgaDocument browser = new RgaDocument(4242);
        replica.merge(browser.insert(0, "praca w toku"), "tester", null, System.currentTimeMillis());
        assertThat(replica.unwrittenCount()).isPositive();

        for (int i = 0; i < LIMIT * 4; i++) {
            registry.get(newDocument("napor-" + i));
        }

        assertThat(registry.isLoaded(busy))
            .as("zwolniono replikę z niezapisanymi operacjami — to jest utrata danych")
            .isTrue();
    }

    @Test
    @DisplayName("zwalniane są najdawniej używane, nie przypadkowe")
    void leastRecentlyUsedGoesFirst() throws Exception {
        List<UUID> documents = new ArrayList<>();
        for (int i = 0; i < LIMIT; i++) {
            UUID id = newDocument("kolejnosc-" + i);
            documents.add(id);
            registry.get(id);
            // Odstęp, żeby znaczniki czasu aktywności były rozróżnialne — bez tego
            // porządkowanie po lastActivityMillis testuje rozdzielczość zegara,
            // a nie logikę wyboru.
            Thread.sleep(5);
        }

        // Dotknięcie NAJSTARSZEGO odświeża jego aktywność, więc ofiarą powinien paść drugi.
        UUID refreshed = documents.getFirst();
        registry.get(refreshed).merge(
            new RgaDocument(777).insert(0, "x"), "tester", null, System.currentTimeMillis());
        registry.get(refreshed).drainUnwritten(100);
        Thread.sleep(5);

        registry.get(newDocument("nowy"));

        assertThat(registry.isLoaded(refreshed))
            .as("zwolniono dokument, którego użyto ostatnio — porządek LRU nie działa")
            .isTrue();
    }
}
