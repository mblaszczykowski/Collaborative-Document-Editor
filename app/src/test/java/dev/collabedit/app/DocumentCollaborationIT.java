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

/**
 * Integration tests for the whole path: browser -> WebSocket -> CRDT merge -> write -> broadcast to
 * the other clients.
 *
 * <p>Each of them answers a question a <b>unit test cannot</b> — because it concerns layers
 * cooperating, the ordering of steps from the cluster design, or the behaviour of a real database.
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
    @Autowired
    io.micrometer.core.instrument.MeterRegistry meters;


    /**
     * Waits for a snapshot to appear.
     *
     * <p>The write acknowledgement is broadcast BEFORE the snapshot is taken — deliberately,
     * because the acknowledgement releases the client's buffer and there is no reason for it to
     * wait on a cache write. A test that checked the snapshot count immediately after receiving the
     * acknowledgement was flaky because of that: it passed alone and failed in a full run. The fix
     * belongs to the test, because it was the test's assumption that was wrong, not the ordering in
     * production code.
     */
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

            // Both people insert at the same place, neither having seen the other's change yet.
            anna.insert(1, "hello");
            marek.insert(1, "world");

            anna.awaitCondition(() -> anna.text().equals(marek.text()) && anna.text().length() == 12,
                PATIENCE, "both hold the same text of length 12");

            assertThat(anna.text()).isEqualTo(marek.text());
            assertThat(anna.text())
                .as("both changes have to survive — that is the whole project's thesis")
                .contains("hello")
                .contains("world");

            // The server replica is a third participant and has to agree too.
            assertThat(documents.textOf(document)).isEqualTo(anna.text());
        }
    }

    @Test
    @DisplayName("state survives losing the in-memory replica — rebuilt from the operation log")
    void stateSurvivesReplicaLoss() throws Exception {
        UUID document = newDocument("Replay");

        try (TestStompClient anna = new TestStompClient(port, tokenFor("Anna"), document, 111)) {
            anna.insert(0, "Inkline");
            anna.insert(7, " works");
            // BOTH operations, not just one. Acknowledgements follow the write, so `> 0` proved
            // only that the FIRST had reached the log — and the two are sent back to back with a
            // 20 ms batch window, so a flush tick landing between them left the second unwritten
            // and the replay produced "Inkline". That is a flake in the test, not in the system.
            anna.awaitCondition(() -> anna.lastAckSeq() >= 2, PATIENCE, "both writes acknowledged");
        }

        String beforeEviction = documents.textOf(document);

        // Simulating an instance crash: the replica disappears from memory. The state has to be
        // rebuildable from the SOURCE OF TRUTH, that is, the operation log.
        RegistryTestAccess.forget(registry, document);

        assertThat(documents.textOf(document))
            .as("replaying the log gave different text from the state before the replica was lost")
            .isEqualTo(beforeEviction)
            .isEqualTo("Inkline works");
    }

    @Test
    @DisplayName("deleting every snapshot changes nothing but the read time")
    void snapshotsAreOnlyACache() throws Exception {
        UUID document = newDocument("Snapshots");

        try (TestStompClient anna = new TestStompClient(port, tokenFor("Anna"), document, 121)) {
            // The snapshot threshold in tests is 20 operations — we write enough for at least one
            // snapshot to be certain.
            for (int i = 0; i < 40; i++) {
                anna.insert(i, "x");
            }
            anna.awaitCondition(() -> anna.lastAckSeq() >= 40, PATIENCE, "everything written");
        }

        awaitSnapshot(document);

        String withSnapshots = documents.textOf(document);

        // the operation-log decision: a snapshot is a cache and may be deleted at any moment. Without this test the
        // cache would quietly become the source of truth — and we would find out only at a failure
        // or at a change to the serialisation format.
        snapshots.deleteAll(document);
        RegistryTestAccess.forget(registry, document);

        assertThat(documents.textOf(document))
            .as("replaying without snapshots gave a different result — the cache stopped being a cache")
            .isEqualTo(withSnapshots);
    }

    @Test
    @DisplayName("a repeated operation duplicates neither the text nor a row in the log")
    void duplicateOperationIsHarmless() throws Exception {
        UUID document = newDocument("Idempotence");

        try (TestStompClient anna = new TestStompClient(port, tokenFor("Anna"), document, 131)) {
            anna.insert(0, "once");
            anna.awaitCondition(() -> anna.lastAckSeq() > 0, PATIENCE, "write acknowledged");

            // Retransmitting the same operation — exactly what a client does after reconnecting
            // when it never saw an acknowledgement.
            int before = anna.syncResponseCount();
            anna.requestSync(0);
            // Waits for the RESPONSE, not for a fixed interval. The previous condition counted
            // operations received on the ops topic and asked whether that count was >= 0, which is
            // true on the first iteration — a size is never negative — so the real synchronisation
            // was the sleep below, and a negative assertion guarded by a fixed sleep passes
            // whenever the machine is slower than the sleep. (That counter has since been removed
            // from TestStompClient; nothing else was using it either.)
            anna.awaitCondition(() -> anna.syncResponseCount() > before, PATIENCE,
                "the resynchronisation response");

            assertThat(documents.textOf(document))
                .as("the retransmission duplicated the text")
                .isEqualTo("once");

            Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM operations WHERE doc_id = ?", Integer.class, document);
            assertThat(rows)
                .as("the retransmission created a second row in the log — the primary key is not protecting")
                .isEqualTo(1);
        }
    }

    @Test
    @DisplayName("the server_seq numbering is contiguous — gap detection rests on it")
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

            // the cluster design bases detection of lost Redis messages on the CONTINUITY of this
            // numbering. A gap would mean an instance asking the database forever for an operation
            // that was never there.
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
            // FIVE operations against a threshold of twenty (IntegrationTestBase). The "snapshot
            // every N operations" path cannot fire here — if a snapshot appears, it is solely
            // because idleness was detected.
            for (int i = 0; i < 5; i++) {
                anna.insert(i, "x");
            }
            anna.awaitCondition(() -> anna.lastAckSeq() >= 5, PATIENCE, "write acknowledged");
        }

        // Without this mechanism a document edited in short sessions NEVER gets a snapshot: each
        // session leaves a few operations, the threshold is never crossed, and the log tail to
        // replay grows with every open. The symptom is insidious, because correctness is intact —
        // only the loading time degrades, and gradually at that.
        awaitSnapshot(document);

        assertThat(snapshots.count(document))
            .as("the document was quiet for longer than snapshot-after-idle-ms and no snapshot appeared")
            .isPositive();
    }

    @Test
    @DisplayName("a session ending reaches the metric with a reason — the listener is wired up")
    void sessionCloseIsRecordedWithReason() throws Exception {
        UUID document = newDocument("Disconnect metric");
        double before = closedSessions();

        try (TestStompClient anna = new TestStompClient(port, tokenFor("Anna"), document, 161)) {
            anna.insert(0, "x");
            anna.awaitCondition(() -> anna.lastAckSeq() > 0, PATIENCE, "the session is alive and writing");
        }

        // The code-to-tag mapping itself is covered by a unit test. This test answers a different
        // question: whether the @EventListener is called at all. A metric that never rises looks
        // like proof there is no problem, and is proof there is no measurement.
        for (int attempt = 0; attempt < 200 && closedSessions() <= before; attempt++) {
            Thread.sleep(20);
        }

        assertThat(closedSessions())
            .as("a client disconnect did not increase ws.sessions.closed.total — the listener is not wired in")
            .isGreaterThan(before);
    }

    private double closedSessions() {
        return meters.find("ws.sessions.closed.total").counters().stream()
            .mapToDouble(io.micrometer.core.instrument.Counter::count)
            .sum();
    }
}
