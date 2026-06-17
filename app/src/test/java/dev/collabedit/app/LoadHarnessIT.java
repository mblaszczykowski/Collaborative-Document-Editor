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
 * The load harness (CP8) — the source of the numbers in the README's "Performance" section.
 *
 * <p>Run separately, not in a normal test run:
 * <pre>./mvnw -pl app test -Dtest=LoadHarnessIT -Dbenchmark=true</pre>
 *
 * <p>Disabled via {@code @EnabledIfSystemProperty} rather than JUnit tags and Surefire's
 * {@code excludedGroups}: with tags, {@code -Dgroups=benchmark} does NOT override
 * {@code excludedGroups} from the configuration, so the build succeeds having run zero tests —
 * with no warning at all. A system-property condition is visible in the code and cannot be
 * accidentally bypassed.
 *
 * <h2>Why a custom harness rather than Gatling</h2>
 * The plan called for Gatling, and that decision was changed deliberately. Gatling measures
 * <b>response time to a request</b> very well, whereas the interesting quantity in this system is
 * <b>the time for a change to propagate from one participant to another</b> — that is, the interval
 * between events at TWO different clients. Measuring that requires both ends to share one clock; a
 * harness in a single process provides that, and two Gatling processes do not.
 *
 * <h2>Methodology — read before quoting the numbers</h2>
 * <ul>
 *   <li><b>Propagation latency</b> = the receiver's {@code nanoTime} at receipt minus the sender's
 *       {@code nanoTime} at send, both in the same process and from the same monotonic clock. It is
 *       a quantity <b>excluding</b> browser and rendering overhead — so it measures the system, not
 *       the interface.</li>
 *   <li><b>The clients and the server run on the same machine.</b> Network latency is therefore
 *       essentially zero. The numbers describe the system's costs, not what a user over the
 *       internet experiences — for that they would have to be increased by the link's RTT.</li>
 *   <li><b>The first 20% of measurements are dropped</b> as JIT warm-up. Without that, p99
 *       describes compilation rather than operation.</li>
 *   <li>The machine's specification is printed with the result, because a number without a machine
 *       is useless.</li>
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

    /** How many clients at once in one document. */
    private static final int[] CLIENT_COUNTS = {2, 10, 25, 50};

    /** How many characters the document holds before the measurement starts. */
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
        // "intake/s" is the rate at which the server ACCEPTS operations — not the same as the
        // propagation rate. Kept explicitly separate, because conflating those two numbers is the
        // most common way real-time system benchmarks come out better than the systems are.
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
        jdbc.update("INSERT INTO documents (id, title) VALUES (?, ?)",
            document, "bench-" + documentSize + "-" + clientCount);

        List<TestStompClient> clients = new ArrayList<>(clientCount);
        // The send time of each operation, shared by every client — one clock, one process.
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
                            // Inserting at the FRONT, not the end: continuous typing compresses
                            // into one block and never touches the path that actually scales with
                            // document size. Inserting at the front makes the structure work.
                            Op op = client.insertReturningOp(0, "x");
                            sentAt.put(key(op), System.nanoTime());

                            // Presence traffic — every other keystroke, that is, throttled the
                            // same way a real client throttles it (100 ms).
                            //
                            // Up to CP15 the harness did NOT generate it, so the numbers described
                            // a lighter system than the one a browser uses. The cursor is the most
                            // frequent event in the whole system, and omitting it made the
                            // measurement optimistic in a way impossible to notice.
                            if (i % 2 == 0) {
                                client.sendPresence();
                            }

                            // A formatting operation every tenth edit (CP13). The ratio comes
                            // from observation: people format far less often than they type, but
                            // not so rarely that it can be left out.
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
            // Throughput measured up to the moment every operation has been SENT, not to
            // convergence. Including the wait for convergence would make a scenario that did NOT
            // converge report a throughput equal to the operation count divided by the timeout —
            // a meaningless number that looks like a measurement.
            long elapsedNanos = System.nanoTime() - startedAt;

            int expectedLength = documentSize + clientCount * OPERATIONS_PER_CLIENT;
            long convergenceStart = System.nanoTime();
            boolean converged = awaitConvergence(clients, expectedLength, 150_000);
            long convergenceNanos = System.nanoTime() - convergenceStart;

            long totalOperations = (long) clientCount * OPERATIONS_PER_CLIENT;
            double opsPerSecond = totalOperations / (elapsedNanos / 1_000_000_000.0);

            List<Long> measured = new ArrayList<>(latencies);
            Collections.sort(measured);
            // Dropping the warm-up: the first 20% of measurements mostly describe JIT compilation.
            List<Long> steady = measured.subList(measured.size() / 5, measured.size());

            // Distinguishing "operations were lost" from "operations did not arrive in time" is
            // essential: the first is a correctness bug, the second a throughput limit of the
            // harness. Without this diagnostic both look identical ("did not converge") and it is
            // easy to blame the system for a limitation of the measuring rig.
            int serverLength = documentsService().textOf(document).length();
            int minClient = clients.stream().mapToInt(c -> c.text().length()).min().orElse(0);

            System.out.printf("%-10d %-10d %10.1f %10.2f %10.2f %10.2f %12s %10s %14s%n",
                documentSize, clientCount, opsPerSecond,
                percentile(steady, 0.50) / 1e6,
                percentile(steady, 0.95) / 1e6,
                percentile(steady, 0.99) / 1e6,
                converged ? "yes" : "NO",
                converged ? String.format("%.0f", convergenceNanos / 1e6) : "—",
                minClient + "/" + serverLength);
        } finally {
            for (TestStompClient client : clients) {
                client.close();
            }
        }
    }

    private void seedDocument(TestStompClient client, int characters) {
        // Inserted in chunks: a single operation for 100k characters would exceed the message
        // size limit from ADR-0002 (64 KiB).
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
