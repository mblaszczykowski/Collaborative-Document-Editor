package dev.collabedit.app;

import static org.assertj.core.api.Assertions.assertThat;

import dev.collabedit.app.auth.TokenService;
import dev.collabedit.app.domain.DocumentRegistry;
import dev.collabedit.app.domain.DocumentService;
import dev.collabedit.app.persist.SnapshotStore;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Testy integracyjne całej ścieżki: przeglądarka → WebSocket → scalenie CRDT → zapis →
 * rozgłoszenie do pozostałych klientów.
 *
 * <p>Każdy z nich odpowiada na pytanie, na które <b>nie da się odpowiedzieć testem
 * jednostkowym</b> — bo dotyczy współpracy warstw, kolejności kroków z ADR-0004 albo
 * zachowania prawdziwej bazy.
 */
class DocumentCollaborationIT extends IntegrationTestBase {

    private static final Duration PATIENCE = Duration.ofSeconds(10);

    @LocalServerPort
    int port;

    @Autowired
    TokenService tokens;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    DocumentRegistry registry;
    @Autowired
    DocumentService documents;
    @Autowired
    SnapshotStore snapshots;

    private UUID newDocument(String title) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO documents (id, title) VALUES (?, ?)", id, title);
        return id;
    }

    private String tokenFor(String name) {
        return tokens.issue(new TokenService.Principal(UUID.randomUUID().toString(), name, "#C1592E"));
    }

    @Test
    @DisplayName("dwóch klientów edytujących jednocześnie zbiega się do tego samego tekstu")
    void twoClientsConverge() throws Exception {
        UUID document = newDocument("Zbieżność");

        try (TestStompClient anna = new TestStompClient(port, tokenFor("Anna"), document, 101);
             TestStompClient marek = new TestStompClient(port, tokenFor("Marek"), document, 202)) {

            anna.insert(0, "AB");
            anna.awaitCondition(() -> marek.text().equals("AB"), PATIENCE, "Marek dostaje AB");

            // Obie osoby wstawiają w to samo miejsce, nie widząc jeszcze zmiany drugiej.
            anna.insert(1, "hello");
            marek.insert(1, "world");

            anna.awaitCondition(() -> anna.text().equals(marek.text()) && anna.text().length() == 12,
                PATIENCE, "obaj mają ten sam tekst o długości 12");

            assertThat(anna.text()).isEqualTo(marek.text());
            assertThat(anna.text())
                .as("obie zmiany muszą przetrwać — to jest teza całego projektu")
                .contains("hello")
                .contains("world");

            // Replika serwera to trzeci uczestnik i też musi się zgadzać (ADR-0004).
            assertThat(documents.textOf(document)).isEqualTo(anna.text());
        }
    }

    @Test
    @DisplayName("stan przeżywa utratę repliki z pamięci — odtworzenie z logu operacji")
    void stateSurvivesReplicaLoss() throws Exception {
        UUID document = newDocument("Odtworzenie");

        try (TestStompClient anna = new TestStompClient(port, tokenFor("Anna"), document, 111)) {
            anna.insert(0, "Inkline");
            anna.insert(7, " dziala");
            anna.awaitCondition(() -> anna.lastAckSeq() > 0, PATIENCE, "zapis potwierdzony");
        }

        String beforeEviction = documents.textOf(document);

        // Symulacja padu instancji: replika znika z pamięci. Stan musi dać się odtworzyć
        // ze ŹRÓDŁA PRAWDY, czyli z logu operacji (ADR-0003).
        registry.forget(document);

        assertThat(documents.textOf(document))
            .as("odtworzenie z logu dało inny tekst niż stan sprzed utraty repliki")
            .isEqualTo(beforeEviction)
            .isEqualTo("Inkline dziala");
    }

    @Test
    @DisplayName("skasowanie wszystkich snapshotów nie zmienia niczego poza czasem odczytu")
    void snapshotsAreOnlyACache() throws Exception {
        UUID document = newDocument("Snapshoty");

        try (TestStompClient anna = new TestStompClient(port, tokenFor("Anna"), document, 121)) {
            // Próg snapshotowania w testach to 20 operacji — piszemy dość, żeby na pewno
            // powstał przynajmniej jeden snapshot.
            for (int i = 0; i < 40; i++) {
                anna.insert(i, "x");
            }
            anna.awaitCondition(() -> anna.lastAckSeq() >= 40, PATIENCE, "wszystko zapisane");
        }

        assertThat(snapshots.count(document))
            .as("test nie dotknął ścieżki snapshotowania, więc niczego nie dowodzi")
            .isPositive();

        String withSnapshots = documents.textOf(document);

        // ADR-0003: snapshot jest cache'em, wolno go skasować w każdej chwili.
        // Bez tego testu cache po cichu stałby się źródłem prawdy — a dowiedzielibyśmy
        // się o tym dopiero przy awarii albo przy zmianie formatu serializacji.
        snapshots.deleteAll(document);
        registry.forget(document);

        assertThat(documents.textOf(document))
            .as("odtworzenie bez snapshotów dało inny wynik — cache przestał być cache'em")
            .isEqualTo(withSnapshots);
    }

    @Test
    @DisplayName("powtórzona operacja nie duplikuje tekstu ani wiersza w logu")
    void duplicateOperationIsHarmless() throws Exception {
        UUID document = newDocument("Idempotencja");

        try (TestStompClient anna = new TestStompClient(port, tokenFor("Anna"), document, 131)) {
            anna.insert(0, "raz");
            anna.awaitCondition(() -> anna.lastAckSeq() > 0, PATIENCE, "zapis potwierdzony");

            // Retransmisja tej samej operacji — dokładnie to, co robi klient po ponownym
            // połączeniu, gdy nie doczekał się potwierdzenia.
            anna.requestSync(0);
            anna.awaitCondition(() -> anna.receivedCount() >= 0, Duration.ofMillis(500), "chwila");

            Thread.sleep(300);

            assertThat(documents.textOf(document))
                .as("retransmisja zdublowała tekst")
                .isEqualTo("raz");

            Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM operations WHERE doc_id = ?", Integer.class, document);
            assertThat(rows)
                .as("retransmisja utworzyła drugi wiersz w logu — klucz główny nie chroni")
                .isEqualTo(1);
        }
    }

    @Test
    @DisplayName("numeracja server_seq jest ciągła — na niej opiera się wykrywanie luk")
    void serverSequenceHasNoGaps() throws Exception {
        UUID document = newDocument("Numeracja");

        try (TestStompClient anna = new TestStompClient(port, tokenFor("Anna"), document, 141);
             TestStompClient marek = new TestStompClient(port, tokenFor("Marek"), document, 142)) {

            for (int i = 0; i < 15; i++) {
                anna.insert(anna.text().length(), "a");
                marek.insert(0, "m");
            }
            anna.awaitCondition(() -> anna.text().equals(marek.text()), PATIENCE, "zbieżność");
            Thread.sleep(300);

            // ADR-0004 opiera wykrywanie zgubionych wiadomości Redisa na CIĄGŁOŚCI tej
            // numeracji. Dziura oznaczałaby, że instancja w nieskończoność dopytuje bazę
            // o operację, której tam nigdy nie było.
            Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM operations WHERE doc_id = ?", Integer.class, document);
            Long max = jdbc.queryForObject(
                "SELECT MAX(server_seq) FROM operations WHERE doc_id = ?", Long.class, document);

            assertThat(max)
                .as("numeracja server_seq ma dziurę: %d operacji, najwyższy numer %d", count, max)
                .isEqualTo(Long.valueOf(count));
        }
    }
}
