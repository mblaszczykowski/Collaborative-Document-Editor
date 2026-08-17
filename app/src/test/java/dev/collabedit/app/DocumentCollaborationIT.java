package dev.collabedit.app;

import static org.assertj.core.api.Assertions.assertThat;

import dev.collabedit.app.auth.TokenService;
import dev.collabedit.app.domain.DocumentRegistry;
import dev.collabedit.app.domain.RegistryTestAccess;
import dev.collabedit.app.domain.DocumentService;
import dev.collabedit.app.persist.SnapshotStore;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

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
    @Autowired
    io.micrometer.core.instrument.MeterRegistry meters;

    private void awaitSnapshot(UUID document) throws InterruptedException {
        for (int attempt = 0; attempt < 200; attempt++) {
            if (snapshots.count(document) > 0) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("no snapshot was created for document " + document);
    }

    private UUID newDocument(String title) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO documents (id, title, visibility) VALUES (?, ?, 'link')", id, title);
        return id;
    }

    private String tokenFor(String name) {
        return tokens.issue(new TokenService.Principal(UUID.randomUUID().toString(), name, "#C1592E"));
    }

    @Test
    @DisplayName("two clients editing at once converge to the same text")
    void twoClientsConverge() throws Exception {
        UUID document = newDocument("Convergence");

        try (TestStompClient anna = new TestStompClient(port, tokenFor("Anna"), document, 101);
             TestStompClient marek = new TestStompClient(port, tokenFor("Marek"), document, 202)) {
            anna.insert(0, "AB");
            anna.awaitCondition(() -> marek.text().equals("AB"), PATIENCE, "Marek receives AB");

            anna.insert(1, "hello");
            marek.insert(1, "world");

            anna.awaitCondition(() -> anna.text().equals(marek.text()) && anna.text().length() == 12,
                PATIENCE, "both hold the same text of length 12");

            assertThat(anna.text()).isEqualTo(marek.text());
            assertThat(anna.text())
                .as("both changes have to survive - that is the whole project's thesis")
                .contains("hello")
                .contains("world");

            assertThat(documents.textOf(document)).isEqualTo(anna.text());
        }
    }

    @Test
    @DisplayName("state survives losing the in-memory replica - rebuilt from the operation log")
    void stateSurvivesReplicaLoss() throws Exception {
        UUID document = newDocument("Replay");

        String typed = "Collabedit";

        try (TestStompClient anna = new TestStompClient(port, tokenFor("Anna"), document, 111)) {
            anna.insert(0, typed);
            anna.insert(typed.length(), " works");
            anna.awaitCondition(() -> anna.lastAckSeq() >= 2, PATIENCE, "both writes acknowledged");
        }

        String beforeEviction = documents.textOf(document);

        RegistryTestAccess.forget(registry, document);

        assertThat(documents.textOf(document))
            .as("replaying the log gave different text from the state before the replica was lost")
            .isEqualTo(beforeEviction)
            .isEqualTo(typed + " works");
    }

    @Test
    @DisplayName("deleting every snapshot changes nothing but the read time")
    void snapshotsAreOnlyACache() throws Exception {
        UUID document = newDocument("Snapshots");

        try (TestStompClient anna = new TestStompClient(port, tokenFor("Anna"), document, 121)) {
            for (int i = 0; i < 40; i++) {
                anna.insert(i, "x");
            }
            anna.awaitCondition(() -> anna.lastAckSeq() >= 40, PATIENCE, "everything written");
        }

        awaitSnapshot(document);

        String withSnapshots = documents.textOf(document);

        snapshots.deleteAll(document);
        RegistryTestAccess.forget(registry, document);

        assertThat(documents.textOf(document))
            .as("replaying without snapshots gave a different result - the cache stopped being a cache")
            .isEqualTo(withSnapshots);
    }

    @Test
    @DisplayName("a repeated operation duplicates neither the text nor a row in the log")
    void duplicateOperationIsHarmless() throws Exception {
        UUID document = newDocument("Idempotence");

        try (TestStompClient anna = new TestStompClient(port, tokenFor("Anna"), document, 131)) {
            anna.insert(0, "once");
            anna.awaitCondition(() -> anna.lastAckSeq() > 0, PATIENCE, "write acknowledged");

            int before = anna.syncResponseCount();
            anna.requestSync(0);
            anna.awaitCondition(() -> anna.syncResponseCount() > before, PATIENCE,
                "the resynchronisation response");

            assertThat(documents.textOf(document))
                .as("the retransmission duplicated the text")
                .isEqualTo("once");

            Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM operations WHERE doc_id = ?", Integer.class, document);
            assertThat(rows)
                .as("the retransmission created a second row in the log - the primary key is not protecting")
                .isEqualTo(1);
        }
    }

    @Test
    @DisplayName("the server_seq numbering is contiguous - gap detection rests on it")
    void serverSequenceHasNoGaps() throws Exception {
        UUID document = newDocument("Numbering");

        try (TestStompClient anna = new TestStompClient(port, tokenFor("Anna"), document, 141);
             TestStompClient marek = new TestStompClient(port, tokenFor("Marek"), document, 142)) {
            for (int i = 0; i < 15; i++) {
                anna.insert(anna.text().length(), "a");
                marek.insert(0, "m");
            }
            anna.awaitCondition(() -> anna.text().equals(marek.text()), PATIENCE, "convergence");
            Thread.sleep(300);

            Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM operations WHERE doc_id = ?", Integer.class, document);
            Long max = jdbc.queryForObject(
                "SELECT MAX(server_seq) FROM operations WHERE doc_id = ?", Long.class, document);

            assertThat(max)
                .as("the server_seq numbering has a gap: %d operations, highest number %d", count, max)
                .isEqualTo(Long.valueOf(count));
        }
    }

    @Test
    @DisplayName("a document that has gone quiet gets a snapshot despite missing the operation threshold")
    void quietDocumentIsSnapshottedBelowOperationThreshold() throws Exception {
        UUID document = newDocument("Quiet");

        try (TestStompClient anna = new TestStompClient(port, tokenFor("Anna"), document, 151)) {
            for (int i = 0; i < 5; i++) {
                anna.insert(i, "x");
            }
            anna.awaitCondition(() -> anna.lastAckSeq() >= 5, PATIENCE, "write acknowledged");
        }

        awaitSnapshot(document);

        assertThat(snapshots.count(document))
            .as("the document was quiet for longer than snapshot-after-idle-ms and no snapshot appeared")
            .isPositive();
    }

    @Test
    @DisplayName("a session ending reaches the metric with a reason - the listener is wired up")
    void sessionCloseIsRecordedWithReason() throws Exception {
        UUID document = newDocument("Disconnect metric");
        double before = closedSessions();

        try (TestStompClient anna = new TestStompClient(port, tokenFor("Anna"), document, 161)) {
            anna.insert(0, "x");
            anna.awaitCondition(() -> anna.lastAckSeq() > 0, PATIENCE, "the session is alive and writing");
        }

        for (int attempt = 0; attempt < 200 && closedSessions() <= before; attempt++) {
            Thread.sleep(20);
        }

        assertThat(closedSessions())
            .as("a client disconnect did not increase ws.sessions.closed.total - the listener is not wired in")
            .isGreaterThan(before);
    }

    private double closedSessions() {
        return meters.find("ws.sessions.closed.total").counters().stream()
            .mapToDouble(io.micrometer.core.instrument.Counter::count)
            .sum();
    }
}
