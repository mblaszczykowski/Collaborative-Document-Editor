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

    private static final int[] CLIENT_COUNTS = {2, 10, 25, 50};

    private static final int[] DOCUMENT_SIZES = {0, 10_000, 100_000};

    private static final int OPERATIONS_PER_CLIENT = 40;

    @Test
    @DisplayName("throughput and propagation latency as a function of client count and document size")
    void measure() throws Exception {
        System.out.println();
        System.out.println("=== LOAD HARNESS ===");
        System.out.printf("machine: %s, %d cores, Java %s, JVM memory %d MB%n",
            System.getProperty("os.name") + " " + System.getProperty("os.arch"),
            Runtime.getRuntime().availableProcessors(),
            System.getProperty("java.version"),
            Runtime.getRuntime().maxMemory() / 1024 / 1024);
        System.out.println();
        System.out.printf("%-10s %-10s %10s %10s %10s %10s %12s %10s %14s%n",
            "chars", "clients", "intake/s", "p50 ms", "p95 ms", "p99 ms", "converged",
            "catchup ms", "min.cli./srv.");

        for (int documentSize : DOCUMENT_SIZES) {
            for (int clients : CLIENT_COUNTS) {
                runScenario(documentSize, clients);
            }
        }
        System.out.println();
    }

    private void runScenario(int documentSize, int clientCount) throws Exception {
        UUID document = UUID.randomUUID();
        jdbc.update("INSERT INTO documents (id, title, visibility) VALUES (?, ?, 'link')",
            document, "bench-" + documentSize + "-" + clientCount);

        List<TestStompClient> clients = new ArrayList<>(clientCount);
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
                            Op op = client.insertReturningOp(0, "x");
                            sentAt.put(key(op), System.nanoTime());

                            if (i % 2 == 0) {
                                client.sendPresence();
                            }

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
            long elapsedNanos = System.nanoTime() - startedAt;

            int expectedLength = documentSize + clientCount * OPERATIONS_PER_CLIENT;
            long convergenceStart = System.nanoTime();
            boolean converged = awaitConvergence(clients, expectedLength, 150_000);
            long convergenceNanos = System.nanoTime() - convergenceStart;

            long totalOperations = (long) clientCount * OPERATIONS_PER_CLIENT;
            double opsPerSecond = totalOperations / (elapsedNanos / 1_000_000_000.0);

            List<Long> measured = new ArrayList<>(latencies);
            Collections.sort(measured);
            List<Long> steady = measured.subList(measured.size() / 5, measured.size());

            int serverLength = documentsService().textOf(document).length();
            int minClient = clients.stream().mapToInt(c -> c.text().length()).min().orElse(0);

            System.out.printf("%-10d %-10d %10.1f %10.2f %10.2f %10.2f %12s %10s %14s%n",
                documentSize, clientCount, opsPerSecond,
                percentile(steady, 0.50) / 1e6,
                percentile(steady, 0.95) / 1e6,
                percentile(steady, 0.99) / 1e6,
                converged ? "yes" : "NO",
                converged ? String.format("%.0f", convergenceNanos / 1e6) : "-",
                minClient + "/" + serverLength);
        } finally {
            for (TestStompClient client : clients) {
                client.close();
            }
        }
    }

    private void seedDocument(TestStompClient client, int characters) {
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
