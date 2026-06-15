package dev.collabedit.app;

import static org.assertj.core.api.Assertions.assertThat;

import dev.collabedit.app.auth.TokenService;
import dev.collabedit.app.domain.DocumentRegistry;
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
 * Historia zmian i cofanie się w czasie (ADR-0003, CP5).
 *
 * <p>Najważniejszy test w tej klasie to {@code timeTravelIgnoresSnapshots}. ADR-0003 opiera
 * się na twierdzeniu, że snapshot jest <b>cache'em</b>, a nie źródłem prawdy — i to
 * twierdzenie musi być sprawdzane, a nie deklarowane. Bez tego testu snapshot po cichu
 * stałby się jedynym nośnikiem informacji, a dowiedzielibyśmy się o tym przy pierwszej
 * zmianie formatu serializacji, czyli w najgorszym możliwym momencie.
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
     * Czeka na powstanie snapshotu.
     *
     * <p>Potwierdzenie zapisu jest rozsyłane PRZED zrobieniem snapshotu — świadomie, bo
     * potwierdzenie zwalnia bufor klienta i nie ma powodu, żeby czekało na zapis cache'u.
     * Test, który po odebraniu potwierdzenia od razu sprawdzał liczbę snapshotów, był przez
     * to niestabilny: przechodził pojedynczo, a padał w pełnym przebiegu. Poprawka jest po
     * stronie testu, bo to jego założenie było błędne, a nie kolejność w kodzie produkcyjnym.
     */
    private void awaitSnapshot(UUID document) throws InterruptedException {
        for (int attempt = 0; attempt < 200; attempt++) {
            if (snapshots.count(document) > 0) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("nie powstał żaden snapshot dla dokumentu " + document);
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
    @DisplayName("stan na dowolny moment odtwarza się z logu operacji")
    void timeTravel() throws Exception {
        UUID document = newDocument("Podróż w czasie");

        long afterFirst;
        try (TestStompClient anna = new TestStompClient(port, tokenFor("Anna"), document, 201)) {
            anna.insert(0, "wersja pierwsza");
            anna.awaitCondition(() -> anna.lastAckSeq() > 0, PATIENCE, "pierwszy zapis");
            afterFirst = anna.lastAckSeq();

            anna.insert(15, " i druga");
            anna.awaitCondition(() -> anna.lastAckSeq() > afterFirst, PATIENCE, "drugi zapis");
        }

        assertThat(documents.textOf(document)).isEqualTo("wersja pierwsza i druga");
        assertThat(history.textAt(document, afterFirst))
            .as("cofnięcie do wcześniejszego numeru dało zły stan")
            .isEqualTo("wersja pierwsza");
    }

    @Test
    @DisplayName("skasowanie snapshotów nie zmienia wyniku podróży w czasie")
    void timeTravelIgnoresSnapshots() throws Exception {
        UUID document = newDocument("Snapshot to cache");

        long midpoint;
        try (TestStompClient anna = new TestStompClient(port, tokenFor("Anna"), document, 211)) {
            // Próg snapshotowania w testach to 20 operacji — piszemy dość, żeby powstał
            // przynajmniej jeden, i zapamiętujemy punkt PO nim.
            for (int i = 0; i < 30; i++) {
                anna.insert(i, "x");
            }
            anna.awaitCondition(() -> anna.lastAckSeq() >= 30, PATIENCE, "pierwsza część");
            midpoint = anna.lastAckSeq();

            for (int i = 0; i < 20; i++) {
                anna.insert(30 + i, "y");
            }
            anna.awaitCondition(() -> anna.lastAckSeq() >= 50, PATIENCE, "druga część");
        }

        awaitSnapshot(document);

        String withSnapshots = history.textAt(document, midpoint);
        assertThat(withSnapshots).hasSize(30);

        snapshots.deleteAll(document);
        registry.forget(document);

        assertThat(history.textAt(document, midpoint))
            .as("bez snapshotów wyszedł inny stan — cache przestał być cache'em")
            .isEqualTo(withSnapshots);
    }

    @Test
    @DisplayName("oś czasu grupuje operacje w wersje i pokazuje autora")
    void timelineGroupsOperations() throws Exception {
        UUID document = newDocument("Oś czasu");
        String userId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO users (id, display_name, color) VALUES (?, ?, ?)",
            userId, "Anna", "#C1592E");
        String token = tokens.issue(new TokenService.Principal(userId, "Anna", "#C1592E"));

        try (TestStompClient anna = new TestStompClient(port, token, document, 221)) {
            for (int i = 0; i < 12; i++) {
                anna.insert(i, "z");
            }
            anna.awaitCondition(() -> anna.lastAckSeq() >= 12, PATIENCE, "zapis");
        }

        var timeline = history.timeline(document);
        assertThat(timeline)
            .as("dwanaście operacji jednego autora w jednej chwili to JEDEN wpis historii, "
                + "nie dwanaście — oś czasu z wpisem na naciśnięcie klawisza byłaby bezużyteczna")
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
    @DisplayName("oś czasu liczy usunięcia, nie liczy znaczników jako znaków i dzieli po autorze")
    void timelineCountsDeletionsAndSeparatesAuthors() throws Exception {
        UUID document = newDocument("Oś czasu — usunięcia");

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
            anna.awaitCondition(() -> anna.lastAckSeq() >= 1, PATIENCE, "wstawienie");
            anna.delete(2, 4);
            anna.awaitCondition(() -> anna.lastAckSeq() >= 2, PATIENCE, "usunięcie");
            anna.markReturningOp(0, 3, "bold", null);
            anna.awaitCondition(() -> anna.lastAckSeq() >= 3, PATIENCE, "znacznik");
        }

        try (TestStompClient bartek = new TestStompClient(
            port, tokens.issue(new TokenService.Principal(bartekId, "Bartek", "#2E6FC1")),
            document, 242)) {
            // The test client does not synchronise on connect — it only subscribes. Bartek
            // has to ask for the existing log before he can insert at a meaningful position.
            bartek.requestSync(0);
            bartek.awaitCondition(() -> bartek.text().length() == 6, PATIENCE, "synchronizacja");
            bartek.insert(bartek.text().length(), "XY");
            bartek.awaitCondition(() -> bartek.lastAckSeq() >= 4, PATIENCE, "wstawienie Bartka");
        }

        var timeline = history.timeline(document);

        assertThat(timeline)
            .as("dwóch autorów to dwa wpisy — granica grupy biegnie po autorze")
            .hasSize(2);

        // Newest first, so Bartek's entry leads.
        var bartekEntry = timeline.getFirst();
        var annaEntry = timeline.getLast();

        assertThat(bartekEntry.displayName()).isEqualTo("Bartek");
        assertThat(bartekEntry.charactersInserted()).isEqualTo(2);

        assertThat(annaEntry.displayName()).isEqualTo("Anna");
        assertThat(annaEntry.charactersInserted())
            .as("wstawienie dziesięciu znaków")
            .isEqualTo(10);
        assertThat(annaEntry.charactersDeleted())
            .as("usunięcie czterech znaków — liczone z kolumny, nie z ładunku")
            .isEqualTo(4);
        assertThat(annaEntry.operationCount())
            .as("wstawienie, usunięcie i znacznik to trzy operacje; znacznik JEST zmianą "
                + "dokumentu i ma być widoczny na osi czasu")
            .isEqualTo(3);
    }

    @Test
    @DisplayName("przywrócenie wersji dopisuje operacje, nie obcina logu")
    void restoreIsAppendOnly() throws Exception {
        UUID document = newDocument("Przywracanie");

        long afterFirst;
        try (TestStompClient anna = new TestStompClient(port, tokenFor("Anna"), document, 231)) {
            anna.insert(0, "tekst oryginalny");
            anna.awaitCondition(() -> anna.lastAckSeq() > 0, PATIENCE, "pierwszy zapis");
            afterFirst = anna.lastAckSeq();

            anna.insert(16, " ZEPSUTY");
            anna.awaitCondition(() -> anna.lastAckSeq() > afterFirst, PATIENCE, "drugi zapis");
        }

        Integer operationsBefore = jdbc.queryForObject(
            "SELECT COUNT(*) FROM operations WHERE doc_id = ?", Integer.class, document);

        String target = history.textAt(document, afterFirst);
        var replica = registry.get(document);
        var generated = replica.withLock(model -> history.restoreOperations(model, target));
        for (var op : generated) {
            documents.submitAlreadyMerged(document, op, null);
        }
        Thread.sleep(300);

        assertThat(documents.textOf(document))
            .as("przywrócenie nie dało stanu docelowego")
            .isEqualTo("tekst oryginalny");

        Integer operationsAfter = jdbc.queryForObject(
            "SELECT COUNT(*) FROM operations WHERE doc_id = ?", Integer.class, document);

        // ADR-0003 pkt 6: log jest wyłącznie dopisywany. Obcięcie zniszczyłoby audytowalność
        // (kto i kiedy przywrócił) i rozspójniło klientów, którzy nadal mają usunięte operacje.
        assertThat(operationsAfter)
            .as("log skurczył się przy przywracaniu — to znaczy, że coś zostało obcięte")
            .isGreaterThan(operationsBefore);

        // Stan sprzed przywrócenia MUSI nadal dać się odtworzyć — historia nie znika
        // dlatego, że ktoś się z niej wycofał.
        assertThat(history.textAt(document, operationsBefore))
            .isEqualTo("tekst oryginalny ZEPSUTY");
    }
}
