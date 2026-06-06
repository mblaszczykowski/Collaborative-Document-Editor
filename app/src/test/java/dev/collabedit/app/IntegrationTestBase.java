package dev.collabedit.app;

import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Baza testów integracyjnych — <b>prawdziwy Postgres i prawdziwy Redis</b>, nie atrapy
 * (CLAUDE.md §3.2).
 *
 * <p>Powód jest konkretny, nie ideologiczny. Najważniejsze zachowania warstwy persystencji
 * w tym projekcie są <b>własnościami bazy</b>, których żadna atrapa nie odtworzy:
 * <ul>
 *   <li>idempotencja zapisu wynika z klucza głównego {@code (doc_id, replica_id, lamport)} —
 *       mock po prostu nie odrzuci duplikatu,</li>
 *   <li>ciągłość {@code server_seq} zależy od blokady wiersza {@code FOR UPDATE}
 *       i zachowania transakcji,</li>
 *   <li>{@code JSONB}, {@code BYTEA} i sortowanie po {@code created_at} to zachowania
 *       sterownika i silnika, nie interfejsu.</li>
 * </ul>
 * Test tych rzeczy na atrapie sprawdzałby, czy atrapa zgadza się sama ze sobą.
 *
 * <p>Kontenery są <b>statyczne</b>, więc wstają raz na całą klasę zamiast raz na test.
 * Start Postgresa to kilka sekund; przy teście na metodę czas testów rósłby liniowo,
 * a jedynym zyskiem byłaby izolacja, którą i tak zapewnia osobny dokument w każdym teście.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Tag("integration")
public abstract class IntegrationTestBase {

    static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"))
            .withDatabaseName("collabedit")
            .withUsername("collabedit")
            .withPassword("collabedit");

    static final GenericContainer<?> REDIS =
        new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    static {
        POSTGRES.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));

        // Krótsze okno wsadowania niż produkcyjne 50 ms: testy czekają na potwierdzenie
        // zapisu, a każde 50 ms mnożone przez liczbę asercji to sekundy w przebiegu CI.
        registry.add("collabedit.persistence.op-batch-window-ms", () -> 20);
        // Snapshot co 20 operacji zamiast co 500 — chcemy, żeby testy DOTYKAŁY ścieżki
        // snapshotowania i GC, a nie omijały jej dlatego, że nie generują dość operacji.
        registry.add("collabedit.persistence.snapshot-every-n-ops", () -> 20);
        // Cisza rozpoznawana po 500 ms zamiast po 30 s. Wartość produkcyjna oznaczałaby
        // test trwający pół minuty na samym czekaniu; skrócenie nie zmienia sprawdzanego
        // zachowania, bo próg jest porównaniem, a nie zależnością czasową.
        registry.add("collabedit.persistence.snapshot-after-idle-ms", () -> 500);

        // Bez tego każdy test próbuje wysyłać trace'y do nieistniejącego kolektora
        // i zaśmieca wyjście ostrzeżeniami o nieudanym eksporcie. Obserwowalność nie
        // jest przedmiotem tych testów, a jej hałas utrudnia czytanie tych, które są.
        registry.add("otel.sdk.disabled", () -> "true");
    }
}
