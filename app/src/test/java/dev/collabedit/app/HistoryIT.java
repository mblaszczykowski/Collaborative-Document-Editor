package dev.collabedit.app;

import static org.assertj.core.api.Assertions.assertThat;

import dev.collabedit.app.auth.TokenService;
import dev.collabedit.app.domain.DocumentRegistry;
import dev.collabedit.app.domain.RegistryTestAccess;
import dev.collabedit.app.domain.DocumentService;
import dev.collabedit.app.history.HistoryService;
import dev.collabedit.app.persist.SnapshotStore;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Change history and travelling back in time (CP5).
 *
 * <p>The most important test in this class is {@code timeTravelIgnoresSnapshots}. The operation-log decision rests on
 * the claim that a snapshot is a <b>cache</b> and not the source of truth — and that claim has to
 * be checked, not declared. Without this test a snapshot would quietly become the only carrier of
 * the information, and we would find out at the first change to the serialisation format, that is,
 * at the worst possible moment.
 */
class HistoryIT extends IntegrationTestBase {

    private static final Duration PATIENCE = Duration.ofSeconds(10);

    @LocalServerPort
    int port;

    @Autowired
    TokenService tokens;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    HistoryService history;
    @Autowired
    DocumentService documents;
    @Autowired
    DocumentRegistry registry;
    @Autowired
    SnapshotStore snapshots;


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
        return tokens.issue(new TokenService.Principal(UUID.randomUUID().toString(), name, "#4C5FD5"));
    }

    @Test
    @DisplayName("the state at any moment is rebuilt from the operation log")
    void timeTravel() throws Exception {
        UUID document = newDocument("Time travel");

        long afterFirst;
        try (TestStompClient anna = new TestStompClient(port, tokenFor("Anna"), document, 201)) {
            anna.insert(0, "the first version");
            anna.awaitCondition(() -> anna.lastAckSeq() > 0, PATIENCE, "first write");
            afterFirst = anna.lastAckSeq();

            anna.insert(17, " and the second");
            anna.awaitCondition(() -> anna.lastAckSeq() > afterFirst, PATIENCE, "second write");
        }

        assertThat(documents.textOf(document)).isEqualTo("the first version and the second");
        assertThat(history.textAt(document, afterFirst))
            .as("rewinding to an earlier sequence number gave the wrong state")
            .isEqualTo("the first version");
    }

    @Test
    @DisplayName("deleting the snapshots does not change the result of time travel")
    void timeTravelIgnoresSnapshots() throws Exception {
        UUID document = newDocument("A snapshot is a cache");

        long midpoint;
        try (TestStompClient anna = new TestStompClient(port, tokenFor("Anna"), document, 211)) {
            // The snapshot threshold in tests is 20 operations — we write enough for at least one
            // to exist, and remember the point AFTER it.
            for (int i = 0; i < 30; i++) {
                anna.insert(i, "x");
            }
            anna.awaitCondition(() -> anna.lastAckSeq() >= 30, PATIENCE, "the first part");
            midpoint = anna.lastAckSeq();

            for (int i = 0; i < 20; i++) {
                anna.insert(30 + i, "y");
            }
            anna.awaitCondition(() -> anna.lastAckSeq() >= 50, PATIENCE, "the second part");
        }

        awaitSnapshot(document);

        String withSnapshots = history.textAt(document, midpoint);
        assertThat(withSnapshots).hasSize(30);

        snapshots.deleteAll(document);
        RegistryTestAccess.forget(registry, document);

        assertThat(history.textAt(document, midpoint))
            .as("without snapshots a different state came out — the cache stopped being a cache")
            .isEqualTo(withSnapshots);
    }

    @Test
    @DisplayName("the timeline groups operations into versions and shows the author")
    void timelineGroupsOperations() throws Exception {
        UUID document = newDocument("Timeline");
        String userId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO users (id, display_name, color) VALUES (?, ?, ?)",
            userId, "Anna", "#C1592E");
        String token = tokens.issue(new TokenService.Principal(userId, "Anna", "#C1592E"));

        try (TestStompClient anna = new TestStompClient(port, token, document, 221)) {
            for (int i = 0; i < 12; i++) {
                anna.insert(i, "z");
            }
            anna.awaitCondition(() -> anna.lastAckSeq() >= 12, PATIENCE, "write");
        }

        var timeline = history.timeline(document);
        assertThat(timeline)
            .as("twelve operations by one author in one moment are ONE history entry, not twelve "
                + "— a timeline with an entry per keystroke would be useless")
            .hasSize(1);
        assertThat(timeline.getFirst().displayName()).isEqualTo("Anna");
        assertThat(timeline.getFirst().operationCount()).isEqualTo(12);
        assertThat(timeline.getFirst().charactersInserted()).isEqualTo(12);
    }

    /**
     * The parts of the timeline the SQL rewrite touched and the existing test did not reach.
     *
     * <p>Grouping and character counting moved from Java into a window function so that the
     * timeline stops fetching every {@code payload} in the document (V5). The insert path was
     * already covered by {@link #timelineGroupsOperations()}; these three were not, and each of
     * them is a place where the old Java version had an explicit branch that the SQL expresses
     * differently:
     *
     * <ul>
     *   <li><b>deletions</b> — summed from ranges in the payload before, from a column now;</li>
     *   <li><b>marks</b> — counted as operations but contributing no characters, which the old
     *       code did with a {@code case "m", "x" -> { }} and the new one does by defaulting the
     *       columns to zero. A mark reported as "−0" is right; a mark reported as a deletion of
     *       the anchored range would not be, and that is the bug the old branch existed to
     *       prevent;</li>
     *   <li><b>two authors</b> — the group boundary, which is now {@code LAG(user_id) IS DISTINCT
     *       FROM user_id}. {@code IS DISTINCT FROM} rather than {@code <>} on purpose: the first
     *       row's {@code LAG} is null, and {@code <>} would yield null there and start no group.
     *   </li>
     * </ul>
     */
    @Test
    @DisplayName("the timeline counts deletions, counts marks as no characters, and splits by author")
    void timelineCountsDeletionsAndSeparatesAuthors() throws Exception {
        UUID document = newDocument("Timeline — deletions");

        String annaId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO users (id, display_name, color) VALUES (?, ?, ?)",
            annaId, "Anna", "#C1592E");
        String bartekId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO users (id, display_name, color) VALUES (?, ?, ?)",
            bartekId, "Bartek", "#2E6FC1");

        try (TestStompClient anna = new TestStompClient(
            port, tokens.issue(new TokenService.Principal(annaId, "Anna", "#C1592E")),
            document, 241)) {
            anna.insert(0, "abcdefghij");
            anna.awaitCondition(() -> anna.lastAckSeq() >= 1, PATIENCE, "insertion");
            anna.delete(2, 4);
            anna.awaitCondition(() -> anna.lastAckSeq() >= 2, PATIENCE, "deletion");
            anna.markReturningOp(0, 3, "bold", null);
            anna.awaitCondition(() -> anna.lastAckSeq() >= 3, PATIENCE, "mark");
        }

        try (TestStompClient bartek = new TestStompClient(
            port, tokens.issue(new TokenService.Principal(bartekId, "Bartek", "#2E6FC1")),
            document, 242)) {
            // The test client does not synchronise on connect — it only subscribes. Bartek
            // has to ask for the existing log before he can insert at a meaningful position.
            bartek.requestSync(0);
            bartek.awaitCondition(() -> bartek.text().length() == 6, PATIENCE, "synchronisation");
            bartek.insert(bartek.text().length(), "XY");
            bartek.awaitCondition(() -> bartek.lastAckSeq() >= 4, PATIENCE, "Bartek's insertion");
        }

        var timeline = history.timeline(document);

        assertThat(timeline)
            .as("two authors mean two entries — the group boundary runs along the author")
            .hasSize(2);

        // Newest first, so Bartek's entry leads.
        var bartekEntry = timeline.getFirst();
        var annaEntry = timeline.getLast();

        assertThat(bartekEntry.displayName()).isEqualTo("Bartek");
        assertThat(bartekEntry.charactersInserted()).isEqualTo(2);

        assertThat(annaEntry.displayName()).isEqualTo("Anna");
        assertThat(annaEntry.charactersInserted())
            .as("an insertion of ten characters")
            .isEqualTo(10);
        assertThat(annaEntry.charactersDeleted())
            .as("a deletion of four characters — counted from a column, not from the payload")
            .isEqualTo(4);
        assertThat(annaEntry.operationCount())
            .as("an insertion, a deletion and a mark are three operations; a mark IS a change to "
                + "the document and has to be visible on the timeline")
            .isEqualTo(3);
    }

    @Test
    @DisplayName("restoring a version appends operations rather than truncating the log")
    void restoreIsAppendOnly() throws Exception {
        UUID document = newDocument("Restoring");

        long afterFirst;
        try (TestStompClient anna = new TestStompClient(port, tokenFor("Anna"), document, 231)) {
            anna.insert(0, "the original text");
            anna.awaitCondition(() -> anna.lastAckSeq() > 0, PATIENCE, "first write");
            afterFirst = anna.lastAckSeq();

            anna.insert(17, " BROKEN");
            anna.awaitCondition(() -> anna.lastAckSeq() > afterFirst, PATIENCE, "second write");
        }

        Integer operationsBefore = jdbc.queryForObject(
            "SELECT COUNT(*) FROM operations WHERE doc_id = ?", Integer.class, document);

        // Through generateAndSubmit, which is what HistoryController calls. Generating under
        // withLock and then submitting in a loop — which this did — is the shape that call exists
        // to replace, so the test was pinning a path the product had stopped taking.
        var target = history.stateAt(document, afterFirst);
        documents.generateAndSubmit(document, model -> history.restoreOperations(model, target),
            null);
        Thread.sleep(300);

        assertThat(documents.textOf(document))
            .as("the restore did not produce the target state")
            .isEqualTo("the original text");

        Integer operationsAfter = jdbc.queryForObject(
            "SELECT COUNT(*) FROM operations WHERE doc_id = ?", Integer.class, document);

        // the operation-log decision, point 6: the log is append-only. Truncating it would destroy auditability
        // (who restored, and when) and diverge the clients that still hold the removed operations.
        assertThat(operationsAfter)
            .as("the log shrank during the restore — meaning something was truncated")
            .isGreaterThan(operationsBefore);

        // The state from before the restore MUST still be reproducible — history does not vanish
        // because somebody backed out of it.
        assertThat(history.textAt(document, operationsBefore))
            .isEqualTo("the original text BROKEN");
    }
}
