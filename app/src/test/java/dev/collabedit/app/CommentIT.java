package dev.collabedit.app;

import static org.assertj.core.api.Assertions.assertThat;

import dev.collabedit.app.auth.PermissionService;
import dev.collabedit.app.auth.Role;
import dev.collabedit.app.auth.TokenService;
import dev.collabedit.app.comments.CommentService;
import dev.collabedit.app.domain.DocumentRegistry;
import dev.collabedit.app.domain.DocumentService;
import dev.collabedit.app.history.HistoryService;
import dev.collabedit.app.persist.DocumentCatalog;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Komentarze (CP14).
 *
 * <table>
 *   <caption>Test → failure mode</caption>
 *   <tr><th>Test</th><th>Co wyłapuje</th></tr>
 *   <tr><td>{@code commenterCannotChangeText}</td>
 *       <td><b>Rola „komentujący" znacząca w praktyce „redagujący".</b> Komentarze idą tym
 *           samym kanałem operacji co edycja, więc bez sprawdzenia TYPU operacji autoryzacja
 *           per destynacja przepuszcza wszystko albo nic. To jest cały powód, dla którego
 *           ADR-0009 zszedł z autoryzacją do poziomu operacji.</td></tr>
 *   <tr><td>{@code commenterCanAnchorComment}</td>
 *       <td>Zbyt szerokie zaostrzenie: komentujący, który nie może postawić kotwicy,
 *           nie może komentować niczego konkretnego.</td></tr>
 *   <tr><td>{@code threadSurvivesDeletionOfAnchoredText}</td>
 *       <td>Skasowanie zakotwiczonego tekstu gubi ROZMOWĘ. Wątek ma zostać jako odłączony,
 *           z zacytowanym fragmentem — kasowanie rozmowy w reakcji na edycję tekstu jest
 *           zachowaniem, którego nikt się nie spodziewa.</td></tr>
 *   <tr><td>{@code resolvingKeepsTheConversation}</td>
 *       <td>Rozwiązanie wątku jako usunięcie. Kontekst decyzji jest zwykle jedyną rzeczą,
 *           dla której ktoś wraca do komentarzy po miesiącach.</td></tr>
 *   <tr><td>{@code viewerCannotComment}</td>
 *       <td>Rola tylko do odczytu pisząca w dokumencie — inną drogą niż tekst.</td></tr>
 * </table>
 */
class CommentIT extends IntegrationTestBase {

    private static final Duration PATIENCE = Duration.ofSeconds(5);

    @LocalServerPort
    int port;

    @Autowired
    TokenService tokens;
    @Autowired
    PermissionService permissions;
    @Autowired
    CommentService comments;
    @Autowired
    DocumentCatalog catalog;
    @Autowired
    DocumentService documents;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    HistoryService history;
    @Autowired
    DocumentRegistry registry;

    private record User(String id, String token) {
    }

    private User newUser(String name) {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO users (id, display_name, color) VALUES (?, ?, ?) "
            + "ON CONFLICT (id) DO NOTHING", id, name, "#4C5FD5");
        return new User(id, tokens.issue(new TokenService.Principal(id, name, "#4C5FD5")));
    }

    private UUID privateDocument(User owner) {
        UUID id = UUID.randomUUID();
        catalog.create(id, "Z komentarzami", null);
        permissions.setOwner(id, owner.id());
        permissions.setVisibility(id, "private");
        return id;
    }

    @Test
    @DisplayName("komentujący NIE może zmienić tekstu, choć używa tego samego kanału")
    void commenterCannotChangeText() throws Exception {
        User ania = newUser("Ania");
        User krytyk = newUser("Krytyk");
        UUID document = privateDocument(ania);
        permissions.grant(document, krytyk.id(), Role.COMMENTER);

        try (TestStompClient owner = new TestStompClient(port, ania.token(), document, 801)) {
            owner.insert(0, "oryginalny tekst");
            owner.awaitCondition(() -> owner.lastAckSeq() > 0, PATIENCE, "zapis potwierdzony");
        }

        try (TestStompClient commenter = new TestStompClient(port, krytyk.token(), document, 802)) {
            commenter.requestSync(0);
            commenter.awaitCondition(() -> commenter.text().equals("oryginalny tekst"),
                PATIENCE, "komentujący widzi tekst");

            commenter.insert(0, "WANDALIZM");
            Thread.sleep(500);

            assertThat(documents.textOf(document))
                .as("rola komentującego zmieniła treść dokumentu")
                .isEqualTo("oryginalny tekst");
        }
    }

    @Test
    @DisplayName("komentujący MOŻE postawić kotwicę komentarza")
    void commenterCanAnchorComment() throws Exception {
        User ania = newUser("Ania");
        User krytyk = newUser("Krytyk");
        UUID document = privateDocument(ania);
        permissions.grant(document, krytyk.id(), Role.COMMENTER);

        try (TestStompClient owner = new TestStompClient(port, ania.token(), document, 803)) {
            owner.insert(0, "zdanie do omówienia");
            owner.awaitCondition(() -> owner.lastAckSeq() > 0, PATIENCE, "zapis potwierdzony");
        }

        UUID threadId = comments.openThread(document, krytyk.id(), "zdanie", "Nie zgadzam się.");

        try (TestStompClient commenter = new TestStompClient(port, krytyk.token(), document, 804)) {
            commenter.requestSync(0);
            commenter.awaitCondition(() -> commenter.text().length() == 19, PATIENCE, "widzi tekst");

            // Kotwica to znacznik CRDT typu `comment` z identyfikatorem wątku jako wartością.
            commenter.markReturningOp(0, 6, "comment", threadId.toString());

            commenter.awaitCondition(
                () -> documents.markCountOf(document) > 0, PATIENCE,
                "kotwica komentarza nie dotarła — komentujący nie może zakotwiczyć rozmowy");
        }
    }

    @Test
    @DisplayName("rola tylko do odczytu nie może komentować")
    void viewerCannotComment() {
        User ania = newUser("Ania");
        User widz = newUser("Widz");
        UUID document = privateDocument(ania);
        permissions.grant(document, widz.id(), Role.VIEWER);

        assertThat(permissions.roleOf(widz.id(), document).filter(Role::canComment))
            .as("obserwator może komentować — rola tylko do odczytu pisze w dokumencie")
            .isEmpty();
    }

    @Test
    @DisplayName("wątek przeżywa skasowanie zakotwiczonego tekstu, z zacytowanym fragmentem")
    void threadSurvivesDeletionOfAnchoredText() throws Exception {
        User ania = newUser("Ania");
        UUID document = privateDocument(ania);

        try (TestStompClient owner = new TestStompClient(port, ania.token(), document, 805)) {
            owner.insert(0, "fragment do usunięcia");
            owner.awaitCondition(() -> owner.lastAckSeq() > 0, PATIENCE, "zapis potwierdzony");

            UUID threadId = comments.openThread(document, ania.id(), "fragment", "Czy to zostaje?");
            assertThat(threadId).isNotNull();

            owner.delete(0, 8);
            owner.awaitCondition(() -> owner.text().startsWith(" do"), PATIENCE, "tekst skasowany");
            Thread.sleep(300);
        }

        var threads = comments.threadsOf(document, false);
        assertThat(threads)
            .as("skasowanie zakotwiczonego tekstu usunęło ROZMOWĘ")
            .hasSize(1);
        assertThat(threads.getFirst().quotedText())
            .as("wątek odłączony bez zacytowanego fragmentu — nie wiadomo, czego dotyczył")
            .isEqualTo("fragment");
    }

    @Test
    @DisplayName("rozwiązanie wątku zachowuje rozmowę")
    void resolvingKeepsTheConversation() {
        User ania = newUser("Ania");
        UUID document = privateDocument(ania);

        UUID threadId = comments.openThread(document, ania.id(), "cytat", "Pierwsza uwaga");
        comments.addMessage(threadId, ania.id(), "Druga uwaga");

        assertThat(comments.resolve(threadId, ania.id())).isTrue();

        assertThat(comments.threadsOf(document, false))
            .as("rozwiązany wątek nadal jest na liście domyślnej")
            .isEmpty();

        var withResolved = comments.threadsOf(document, true);
        assertThat(withResolved).hasSize(1);
        assertThat(withResolved.getFirst().messages())
            .as("rozwiązanie wątku skasowało rozmowę zamiast ją schować")
            .hasSize(2);

        assertThat(comments.reopen(threadId)).isTrue();
        assertThat(comments.threadsOf(document, false)).hasSize(1);
    }

    @Test
    @DisplayName("przywrócenie wcześniejszej wersji nie kasuje komentarzy dodanych później")
    void threadSurvivesDocumentRestore() throws Exception {
        User ania = newUser("Ania");
        UUID document = privateDocument(ania);

        try (TestStompClient owner = new TestStompClient(port, ania.token(), document, 806)) {
            owner.insert(0, "pierwsza wersja");
            owner.awaitCondition(() -> owner.lastAckSeq() > 0, PATIENCE, "pierwszy zapis");
            long checkpoint = owner.lastAckSeq();

            owner.insert(15, " i dopisek");
            owner.awaitCondition(() -> owner.lastAckSeq() > checkpoint, PATIENCE, "drugi zapis");

            // Komentarz powstaje PO punkcie, do którego zaraz cofniemy dokument.
            UUID threadId = comments.openThread(document, ania.id(), "dopisek", "Uwaga do dopisku");
            assertThat(threadId).isNotNull();

            // Przywrócenie jest NOWĄ zmianą, a nie cofnięciem logu (ADR-0003) — więc nie ma
            // powodu, żeby cokolwiek kasowało wątki. Test pilnuje, żeby tak zostało: gdyby
            // przywracanie kiedykolwiek zaczęło czyścić stan „spoza przywracanej wersji",
            // rozmowy zniknęłyby razem z tekstem, o którym mówiły.
            var restored = history.stateAt(document, checkpoint);
            var replica = registry.get(document);
            var generated = replica.withLock(model -> history.restoreOperations(model, restored));
            for (var op : generated) {
                documents.submitAlreadyMerged(document, op, null);
            }
            Thread.sleep(300);
        }

        var threads = comments.threadsOf(document, true);
        assertThat(threads)
            .as("przywrócenie wersji skasowało rozmowę dodaną później")
            .hasSize(1);
        assertThat(threads.getFirst().messages().getFirst().body())
            .isEqualTo("Uwaga do dopisku");

        // Kotwica wskazuje tekst, którego już nie ma, więc wątek ma być ODŁĄCZONY —
        // a nie usunięty i nie udający, że nadal coś obejmuje.
        assertThat(threads.getFirst().quotedText())
            .as("odłączony wątek stracił zacytowany fragment")
            .isEqualTo("dopisek");
    }

    @Test
    @DisplayName("wątek zawsze ma co najmniej jedną wiadomość")
    void threadAlwaysHasAtLeastOneMessage() {
        User ania = newUser("Ania");
        UUID document = privateDocument(ania);

        comments.openThread(document, ania.id(), "cytat", "Jedyna wiadomość");

        assertThat(comments.threadsOf(document, true).getFirst().messages())
            .as("wątek bez wiadomości to stan, którego interfejs nie umie pokazać")
            .isNotEmpty();
    }
}
