package dev.collabedit.app;

import dev.collabedit.app.auth.TokenService;
import dev.collabedit.crdt.Op;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Harness obciążeniowy (CP8) — źródło liczb do sekcji „Wydajność" w README.
 *
 * <p>Uruchamiany osobno, nie w normalnym przebiegu testów:
 * <pre>./mvnw -pl app test -Dtest=LoadHarnessIT -Dbenchmark=true</pre>
 *
 * <p>Wyłączenie przez {@code @EnabledIfSystemProperty}, a nie przez tagi JUnit i
 * {@code excludedGroups} w Surefire: przy tagach {@code -Dgroups=benchmark} NIE nadpisuje
 * {@code excludedGroups} z konfiguracji, więc build kończy się sukcesem, uruchamiając zero
 * testów — bez żadnego ostrzeżenia. Warunek na właściwości systemowej jest widoczny
 * w kodzie i nie da się go przypadkiem obejść.
 *
 * <h2>Dlaczego własny harness zamiast Gatlinga</h2>
 * Plan zakładał Gatlinga i ta decyzja została zmieniona świadomie. Gatling świetnie mierzy
 * <b>czas odpowiedzi na żądanie</b>, a interesująca wielkość w tym systemie to
 * <b>czas propagacji zmiany od jednego uczestnika do drugiego</b> — czyli odstęp między
 * zdarzeniami u DWÓCH różnych klientów. Zmierzenie tego wymaga, żeby oba końce dzieliły
 * jeden zegar; harness w jednym procesie to zapewnia, a dwa procesy Gatlinga nie.
 *
 * <h2>Metodyka — czytać przed cytowaniem liczb</h2>
 * <ul>
 *   <li><b>Latencja propagacji</b> = {@code nanoTime} odbioru u odbiorcy minus {@code nanoTime}
 *       wysłania u nadawcy, oba w tym samym procesie i z tego samego zegara monotonicznego.
 *       Jest to wielkość <b>bez</b> narzutu przeglądarki i renderowania — czyli mierzy system,
 *       a nie interfejs.</li>
 *   <li><b>Klienci i serwer działają na tej samej maszynie.</b> Latencja sieci wynosi więc
 *       praktycznie zero. Liczby mówią o kosztach systemu, nie o tym, czego doświadczy
 *       użytkownik przez internet — te trzeba by powiększyć o RTT łącza.</li>
 *   <li><b>Pomijamy pierwsze 20% pomiarów</b> jako rozgrzewkę JIT-u. Bez tego p99 opisuje
 *       głównie kompilację, a nie działanie.</li>
 *   <li>Specyfikacja maszyny jest wypisywana razem z wynikiem, bo liczba bez maszyny
 *       jest bezużyteczna.</li>
 * </ul>
 */
@EnabledIfSystemProperty(named = "benchmark", matches = "true")
class LoadHarnessIT extends IntegrationTestBase {

    @LocalServerPort
    int port;

    @Autowired
    TokenService tokens;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    dev.collabedit.app.domain.DocumentService documentService;

    private dev.collabedit.app.domain.DocumentService documentsService() {
        return documentService;
    }

    /** Ilu klientów jednocześnie w jednym dokumencie. */
    private static final int[] CLIENT_COUNTS = {2, 10, 25, 50};

    /** Ile znaków ma dokument przed rozpoczęciem pomiaru. */
    private static final int[] DOCUMENT_SIZES = {0, 10_000, 100_000};

    private static final int OPERATIONS_PER_CLIENT = 40;

    @Test
    @DisplayName("przepustowość i latencja propagacji w funkcji liczby klientów i rozmiaru dokumentu")
    void measure() throws Exception {
        System.out.println();
        System.out.println("=== HARNESS OBCIĄŻENIOWY ===");
        System.out.printf("maszyna: %s, %d rdzeni, Java %s, pamięć JVM %d MB%n",
            System.getProperty("os.name") + " " + System.getProperty("os.arch"),
            Runtime.getRuntime().availableProcessors(),
            System.getProperty("java.version"),
            Runtime.getRuntime().maxMemory() / 1024 / 1024);
        System.out.println();
        // „przyjęcia/s" to tempo, w jakim serwer PRZYJMUJE operacje — nie to samo co tempo
        // propagacji. Rozdzielone jawnie, bo mieszanie tych dwóch liczb jest najczęstszym
        // sposobem, w jaki benchmarki systemów czasu rzeczywistego wychodzą lepiej,
        // niż systemy są.
        System.out.printf("%-10s %-10s %10s %10s %10s %10s %12s %10s %14s%n",
            "znaki", "klienci", "przyjęcia/s", "p50 ms", "p95 ms", "p99 ms", "zbieżność",
            "dogon. ms", "min.kl./serw.");

        for (int documentSize : DOCUMENT_SIZES) {
            for (int clients : CLIENT_COUNTS) {
                runScenario(documentSize, clients);
            }
        }
        System.out.println();
    }

    private void runScenario(int documentSize, int clientCount) throws Exception {
        UUID document = UUID.randomUUID();
        jdbc.update("INSERT INTO documents (id, title) VALUES (?, ?)",
            document, "bench-" + documentSize + "-" + clientCount);

        List<TestStompClient> clients = new ArrayList<>(clientCount);
        // Czas wysłania operacji, wspólny dla wszystkich klientów — jeden zegar, jeden proces.
        Map<String, Long> sentAt = new ConcurrentHashMap<>();
        List<Long> latencies = Collections.synchronizedList(new ArrayList<>());
        AtomicLong receivedCount = new AtomicLong();

        try {
            for (int i = 0; i < clientCount; i++) {
                TestStompClient client = new TestStompClient(
                    port, tokenFor("bench-" + i), document, 10_000 + i);
                client.onOperationReceived((op, receivedAtNanos) -> {
                    Long sent = sentAt.get(key(op));
                    if (sent != null) {
                        latencies.add(receivedAtNanos - sent);
                        receivedCount.incrementAndGet();
                    }
                });
                clients.add(client);
            }

            if (documentSize > 0) {
                seedDocument(clients.getFirst(), documentSize);
                awaitConvergence(clients, documentSize, 60_000);
            }

            long startedAt = System.nanoTime();
            CountDownLatch done = new CountDownLatch(clientCount);
            for (TestStompClient client : clients) {
                Thread.ofVirtual().start(() -> {
                    try {
                        for (int i = 0; i < OPERATIONS_PER_CLIENT; i++) {
                            // Wstawianie na POCZĄTEK, nie na koniec: pisanie ciągiem
                            // kompresuje się do jednego bloku i nie dotyka ścieżki, która
                            // faktycznie skaluje się z rozmiarem dokumentu. Wstawianie
                            // na początek zmusza strukturę do pracy.
                            Op op = client.insertReturningOp(0, "x");
                            sentAt.put(key(op), System.nanoTime());

                            // Ruch obecności — co drugie naciśnięcie klawisza, czyli tempo
                            // ograniczone tak samo, jak robi to prawdziwy klient (100 ms).
                            //
                            // Do CP15 harness go NIE generował, przez co liczby opisywały
                            // system lżejszy niż ten, którego używa przeglądarka. Kursor
                            // jest najczęstszym zdarzeniem w całym systemie i pomijanie go
                            // czyniło pomiar optymistycznym w sposób niemożliwy do zauważenia.
                            if (i % 2 == 0) {
                                client.sendPresence();
                            }

                            // Operacja formatowania co dziesiątą edycję (CP13). Proporcja
                            // dobrana z obserwacji: ludzie formatują znacznie rzadziej,
                            // niż piszą, ale nie na tyle rzadko, żeby dało się to pominąć.
                            if (i % 10 == 9 && client.text().length() >= 2) {
                                Op mark = client.markReturningOp(0, 2, "bold", null);
                                sentAt.put(key(mark), System.nanoTime());
                            }
                        }
                    } finally {
                        done.countDown();
                    }
                });
            }
            done.await(120, TimeUnit.SECONDS);
            // Przepustowość mierzona do momentu WYSŁANIA wszystkich operacji, nie do
            // osiągnięcia zbieżności. Wliczenie oczekiwania na zbieżność sprawiłoby, że
            // scenariusz, który się NIE zbiegł, raportowałby przepustowość równą liczbie
            // operacji podzielonej przez limit czasu — czyli liczbę bez znaczenia,
            // wyglądającą jak pomiar.
            long elapsedNanos = System.nanoTime() - startedAt;

            int expectedLength = documentSize + clientCount * OPERATIONS_PER_CLIENT;
            long convergenceStart = System.nanoTime();
            boolean converged = awaitConvergence(clients, expectedLength, 150_000);
            long convergenceNanos = System.nanoTime() - convergenceStart;

            long totalOperations = (long) clientCount * OPERATIONS_PER_CLIENT;
            double opsPerSecond = totalOperations / (elapsedNanos / 1_000_000_000.0);

            List<Long> measured = new ArrayList<>(latencies);
            Collections.sort(measured);
            // Odcięcie rozgrzewki: pierwsze 20% pomiarów opisuje głównie kompilację JIT.
            List<Long> steady = measured.subList(measured.size() / 5, measured.size());

            // Rozróżnienie „operacje zginęły" od „operacje nie zdążyły" jest kluczowe:
            // pierwsze to błąd poprawności, drugie to ograniczenie przepustowości harnessu.
            // Bez tej diagnostyki obie sytuacje wyglądają identycznie („brak zbieżności")
            // i łatwo przypisać systemowi winę za ograniczenie stanowiska pomiarowego.
            int serverLength = documentsService().textOf(document).length();
            int minClient = clients.stream().mapToInt(c -> c.text().length()).min().orElse(0);

            System.out.printf("%-10d %-10d %10.1f %10.2f %10.2f %10.2f %12s %10s %14s%n",
                documentSize, clientCount, opsPerSecond,
                percentile(steady, 0.50) / 1e6,
                percentile(steady, 0.95) / 1e6,
                percentile(steady, 0.99) / 1e6,
                converged ? "tak" : "NIE",
                converged ? String.format("%.0f", convergenceNanos / 1e6) : "—",
                minClient + "/" + serverLength);
        } finally {
            for (TestStompClient client : clients) {
                client.close();
            }
        }
    }

    private void seedDocument(TestStompClient client, int characters) {
        // Wstawiane porcjami: jedna operacja na 100 tys. znaków przekroczyłaby limit
        // rozmiaru wiadomości z ADR-0002 (64 KiB).
        String chunk = "x".repeat(500);
        for (int written = 0; written < characters; written += chunk.length()) {
            client.insert(client.text().length(), chunk);
        }
    }

    private boolean awaitConvergence(List<TestStompClient> clients, int expectedLength,
                                     long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            boolean all = clients.stream().allMatch(c -> c.text().length() == expectedLength);
            if (all) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }

    private static double percentile(List<Long> sorted, double quantile) {
        if (sorted.isEmpty()) {
            return Double.NaN;
        }
        int index = (int) Math.ceil(quantile * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    private static String key(Op op) {
        return op.id().replicaId() + "@" + op.id().lamport();
    }

    private String tokenFor(String name) {
        return tokens.issue(new TokenService.Principal(UUID.randomUUID().toString(), name, "#4C5FD5"));
    }
}
