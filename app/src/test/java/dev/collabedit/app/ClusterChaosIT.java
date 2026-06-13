package dev.collabedit.app;

import static org.assertj.core.api.Assertions.assertThat;

import dev.collabedit.app.auth.TokenService;
import dev.collabedit.app.domain.DocumentService;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Test chaosu dla klastra (CP8) — weryfikacja najmocniejszej tezy z ADR-0004.
 *
 * <p>Teza brzmi: <b>zgubiona wiadomość Redisa nie jest problemem poprawności, tylko latencji
 * naprawy.</b> To jest ładne zdanie i dopóki nie zostanie sprawdzone, pozostaje ładnym zdaniem.
 *
 * <p>Test uruchamia <b>dwie prawdziwe instancje backendu</b> w jednym procesie, dzielące
 * tego samego Postgresa i tego samego Redisa. Klienci są podłączeni do różnych instancji,
 * więc jedyną drogą propagacji między nimi jest ścieżka klastrowa. Potem Redis jest
 * <b>zatrzymywany w trakcie edycji</b>.
 *
 * <p>Dwie instancje w jednym procesie zamiast dwóch kontenerów: kontenery wymagałyby zbudowania
 * obrazu przy każdej zmianie kodu, co zamieniłoby test trwający kilkanaście sekund w test
 * trwający minuty. Współdzielona maszyna wirtualna nie zmienia tego, co jest sprawdzane, bo
 * instancje i tak komunikują się wyłącznie przez Redisa i Postgresa.
 */
class ClusterChaosIT extends IntegrationTestBase {

    private static final Duration PATIENCE = Duration.ofSeconds(30);

    @LocalServerPort
    int firstInstancePort;

    @Autowired
    TokenService tokens;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    DocumentService documents;

    private static ConfigurableApplicationContext secondInstance;
    private static int secondInstancePort;

    private static synchronized void startSecondInstance() {
        if (secondInstance != null) {
            return;
        }
        // ARGUMENTY uruchomienia, nie `.properties()`. To nie jest kosmetyka: `.properties()`
        // trafia do źródła o niższym priorytecie niż `application.yml`, więc `server.port=0`
        // byłby po cichu przykryty przez `server.port: 8080` z konfiguracji — a druga
        // instancja próbowałaby wstać na zajętym porcie.
        secondInstance = new SpringApplicationBuilder(CollabEditApplication.class)
            .run(
                "--server.port=0",
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--spring.data.redis.host=" + REDIS.getHost(),
                "--spring.data.redis.port=" + REDIS.getMappedPort(6379),
                // Migracje wykonała już pierwsza instancja. Druga tylko sprawdza, czy schemat
                // się zgadza — dokładnie tak, jak zachowuje się druga instancja na produkcji.
                "--spring.flyway.enabled=true",
                "--collabedit.persistence.op-batch-window-ms=20",
                // Przegląd anti-entropy co sekundę zamiast co 30 s. To jest jedyna rzecz
                // przyspieszona na potrzeby testu: mechanizm jest ten sam, tylko nie czekamy
                // pół minuty na jego uruchomienie.
                "--collabedit.cluster.anti-entropy-interval-ms=1000",
                "--otel.sdk.disabled=true",
                // Not optional since CP16: TokenService has no default secret and a context
                // that cannot build it fails as "Unable to start web server", which points
                // nowhere near the cause.
                "--collabedit.auth.secret=" + TEST_SECRET);
        secondInstancePort = secondInstance.getEnvironment()
            .getProperty("local.server.port", Integer.class, 0);
    }

    @AfterAll
    static void stopSecondInstance() {
        if (secondInstance != null) {
            secondInstance.close();
            secondInstance = null;
        }
    }

    private UUID newDocument(String title) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO documents (id, title) VALUES (?, ?)", id, title);
        return id;
    }

    private String tokenFor(String name) {
        return tokens.issue(new TokenService.Principal(UUID.randomUUID().toString(), name, "#4C5FD5"));
    }

    @Test
    @DisplayName("klienci podłączeni do RÓŻNYCH instancji widzą swoje zmiany")
    void editsPropagateAcrossInstances() throws Exception {
        startSecondInstance();
        UUID document = newDocument("Klaster");

        try (TestStompClient onFirst = new TestStompClient(
                firstInstancePort, tokenFor("Anna"), document, 401);
             TestStompClient onSecond = new TestStompClient(
                 secondInstancePort, tokenFor("Marek"), document, 402)) {

            onFirst.insert(0, "z instancji pierwszej");
            onSecond.awaitCondition(() -> onSecond.text().contains("pierwszej"),
                PATIENCE, "druga instancja dostaje zmianę");

            onSecond.insert(onSecond.text().length(), " i drugiej");
            onFirst.awaitCondition(() -> onFirst.text().contains("drugiej"),
                PATIENCE, "pierwsza instancja dostaje zmianę");

            assertThat(onFirst.text()).isEqualTo(onSecond.text());
        }
    }

    @Test
    @DisplayName("zatrzymanie Redisa w trakcie edycji nie gubi zmian — naprawia je anti-entropy")
    void redisOutageIsRepairedByAntiEntropy() throws Exception {
        startSecondInstance();
        UUID document = newDocument("Awaria Redisa");

        try (TestStompClient onFirst = new TestStompClient(
                firstInstancePort, tokenFor("Anna"), document, 411);
             TestStompClient onSecond = new TestStompClient(
                 secondInstancePort, tokenFor("Marek"), document, 412)) {

            onFirst.insert(0, "przed awaria. ");
            onSecond.awaitCondition(() -> onSecond.text().contains("przed awaria"),
                PATIENCE, "propagacja przed awarią");

            // Punkt odniesienia SPRZED awarii. Bez niego warunek „lastAckSeq > 0" byłby
            // spełniony już przez poprzednią operację i test przechodziłby, nie czekając
            // na nic — czyli sprawdzałby, że potrafi nie czekać.
            long acknowledgedBeforeOutage = onFirst.lastAckSeq();

            // AWARIA: Redis znika w trakcie pracy. Ścieżka szybka przestaje istnieć.
            REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
            try {
                // Edycja MUSI działać dalej — Redis nie jest na ścieżce krytycznej zapisu.
                onFirst.insert(onFirst.text().length(), "w trakcie awarii. ");

                // Zapis do logu operacji, czyli do ŹRÓDŁA PRAWDY, nie zależy od Redisa.
                onFirst.awaitCondition(() -> onFirst.lastAckSeq() > acknowledgedBeforeOutage,
                    PATIENCE, "zapis mimo braku Redisa");

                assertThat(documents.textOf(document))
                    .as("edycja przestała działać, gdy padła ŚCIEŻKA SZYBKA — to znaczy, "
                        + "że nie była szybka, tylko krytyczna")
                    .contains("w trakcie awarii");
            } finally {
                REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
            }

            // Po powrocie Redisa druga instancja nie dostanie zgubionych wiadomości — one
            // przepadły bezpowrotnie. Naprawić ma je PRZEGLĄD ANTI-ENTROPY, dociągając
            // brakujące operacje z logu (ADR-0004). To jest cała teza tej architektury.
            onSecond.awaitCondition(() -> onSecond.text().contains("w trakcie awarii"),
                PATIENCE, "anti-entropy naprawia rozjazd po awarii Redisa");

            assertThat(onSecond.text())
                .as("instancje nie zbiegły się po awarii ścieżki szybkiej")
                .isEqualTo(onFirst.text());
        }
    }
}
