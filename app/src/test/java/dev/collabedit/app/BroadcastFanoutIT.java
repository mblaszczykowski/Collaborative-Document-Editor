package dev.collabedit.app;

import dev.collabedit.app.auth.TokenService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Narzędzie diagnostyczne do porównania „przed / po" dla sklejania rozgłoszeń (CP10).
 *
 * <p>Odpowiada na jedno pytanie: <b>ilu klientów w jednym dokumencie system obsługuje,
 * zanim przestaje zbiegać</b> — powtarzalnie, w kilkudziesięciu sekundach zamiast
 * w dziesięciu minutach pełnego harnessu.
 *
 * <p>Świadomie NIE jest testem poprawności i nie chodzi w normalnym przebiegu: mierzy
 * przepustowość stanowiska tak samo jak systemu, więc jego wynik zależy od obciążenia
 * maszyny. Uruchamiane jawnie:
 * <pre>./mvnw -pl app test -Dtest=BroadcastFanoutIT -Dfanout=true</pre>
 */
@EnabledIfSystemProperty(named = "fanout", matches = "true")
class BroadcastFanoutIT extends IntegrationTestBase {

    private static final int OPERATIONS_PER_CLIENT = 40;
    private static final int[] CLIENT_COUNTS = {2, 10, 25, 50};
    private static final int ROUNDS = 3;
    private static final long CONVERGENCE_TIMEOUT_MS = 30_000;

    @LocalServerPort
    int port;

    @Autowired
    TokenService tokens;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    dev.collabedit.app.domain.DocumentService documents;

    @Test
    @DisplayName("ilu klientów zbiega się powtarzalnie")
    void convergenceByClientCount() throws Exception {
        System.out.println();
        System.out.println("=== FAN-OUT: zbieżność wg liczby klientów ===");
        System.out.printf("%-10s %-12s %-12s %s%n", "klienci", "zbiegło", "czas ms", "min.klient/serwer");

        for (int clients : CLIENT_COUNTS) {
            int converged = 0;
            List<String> details = new ArrayList<>();
            for (int round = 0; round < ROUNDS; round++) {
                Result result = runRound(clients);
                if (result.converged()) {
                    converged++;
                }
                details.add(result.detail());
            }
            System.out.printf("%-10d %-12s %-12s %s%n",
                clients, converged + "/" + ROUNDS, "", String.join("  ", details));
        }
        System.out.println();
    }

    private record Result(boolean converged, String detail) { }

    private Result runRound(int clientCount) throws Exception {
        UUID document = UUID.randomUUID();
        jdbc.update("INSERT INTO documents (id, title) VALUES (?, ?)", document, "fanout-" + clientCount);

        List<TestStompClient> clients = new ArrayList<>(clientCount);
        try {
            for (int i = 0; i < clientCount; i++) {
                clients.add(new TestStompClient(port, tokenFor("fanout-" + i), document, 20_000 + i));
            }

            CountDownLatch done = new CountDownLatch(clientCount);
            for (TestStompClient client : clients) {
                Thread.ofVirtual().start(() -> {
                    try {
                        for (int i = 0; i < OPERATIONS_PER_CLIENT; i++) {
                            client.insert(0, "x");
                        }
                    } finally {
                        done.countDown();
                    }
                });
            }
            done.await(60, TimeUnit.SECONDS);

            int expected = clientCount * OPERATIONS_PER_CLIENT;
            long started = System.currentTimeMillis();
            boolean converged = false;
            while (System.currentTimeMillis() - started < CONVERGENCE_TIMEOUT_MS) {
                if (clients.stream().allMatch(c -> c.text().length() == expected)) {
                    converged = true;
                    break;
                }
                Thread.sleep(50);
            }
            long elapsed = System.currentTimeMillis() - started;

            int minClient = clients.stream().mapToInt(c -> c.text().length()).min().orElse(0);
            int server = documents.textOf(document).length();
            return new Result(converged,
                (converged ? "OK/" + elapsed + "ms" : "NIE/" + minClient + "z" + server)
                    + "(s=" + server + ")");
        } finally {
            for (TestStompClient client : clients) {
                client.close();
            }
        }
    }

    private String tokenFor(String name) {
        return tokens.issue(new TokenService.Principal(UUID.randomUUID().toString(), name, "#4C5FD5"));
    }
}
