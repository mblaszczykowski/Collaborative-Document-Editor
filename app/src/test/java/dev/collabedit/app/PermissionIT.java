package dev.collabedit.app;

import static org.assertj.core.api.Assertions.assertThat;

import dev.collabedit.app.auth.PermissionService;
import dev.collabedit.app.auth.Role;
import dev.collabedit.app.auth.TokenService;
import dev.collabedit.app.domain.DocumentService;
import dev.collabedit.app.persist.DocumentCatalog;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Kontrola dostępu (CP12) — <b>najważniejszy zestaw testów w tym checkpoincie</b>.
 *
 * <p>Do CP12 autoryzacji nie było: token sprawdzano raz, przy {@code CONNECT}, i na tym
 * kontrola się kończyła. Przy jednym wspólnym dokumencie było to spójne uproszczenie.
 * Od CP11 identyfikator dokumentu jest częścią adresu podawanego przez klienta, więc
 * to samo uproszczenie staje się podatnością — i to <b>dwiema drogami naraz</b>.
 *
 * <table>
 *   <caption>Test → failure mode</caption>
 *   <tr><th>Test</th><th>Co wyłapuje</th></tr>
 *   <tr><td>{@code restCannotReadForeignPrivateDocument}</td>
 *       <td>IDOR przez REST. {@code GET /api/documents/{id}} zwraca PEŁNĄ TREŚĆ — bez
 *           sprawdzenia dostępu cała autoryzacja WebSocketa jest ozdobą, bo te same dane
 *           wychodzą jednym żądaniem.</td></tr>
 *   <tr><td>{@code websocketCannotSubscribeToForeignPrivateDocument}</td>
 *       <td>IDOR przez WebSocket — podgląd cudzego dokumentu NA ŻYWO, znak po znaku.</td></tr>
 *   <tr><td>{@code viewerCannotWriteText}</td>
 *       <td>Autoryzacja per dokument zamiast per operacja. Rola tylko do odczytu, która
 *           mimo to potrafi zmienić treść, nie jest rolą.</td></tr>
 *   <tr><td>{@code revokingAccessTakesEffect}</td>
 *       <td>Uprawnienia zapamiętane na zawsze. Odebranie dostępu, które nie działa,
 *           jest gorsze niż jego brak — bo ktoś na nim polega.</td></tr>
 *   <tr><td>{@code linkVisibilityKeepsSharedDocumentsWorking}</td>
 *       <td>Migracja odbierająca dostęp do dokumentów, które wcześniej działały.
 *           Zabezpieczenie, które psuje istniejące dane, nie wejdzie na produkcję.</td></tr>
 *   <tr><td>{@code deletedDocumentIsInvisibleEvenToOwner}</td>
 *       <td>Kosz jako obejście uprawnień albo jako źródło treści usuniętego dokumentu.</td></tr>
 * </table>
 */
class PermissionIT extends IntegrationTestBase {

    private static final Duration PATIENCE = Duration.ofSeconds(5);

    @LocalServerPort
    int port;

    @Autowired
    TokenService tokens;
    @Autowired
    PermissionService permissions;
    @Autowired
    DocumentCatalog catalog;
    @Autowired
    DocumentService documents;
    @Autowired
    JdbcTemplate jdbc;

    private record User(String id, String token) {
    }

    private User newUser(String name) {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO users (id, display_name, color) VALUES (?, ?, ?) "
            + "ON CONFLICT (id) DO NOTHING", id, name, "#4C5FD5");
        return new User(id, tokens.issue(new TokenService.Principal(id, name, "#4C5FD5")));
    }

    /** Dokument prywatny, należący do wskazanego użytkownika. */
    private UUID privateDocument(User owner, String title) {
        UUID id = UUID.randomUUID();
        catalog.create(id, title, null);
        permissions.setOwner(id, owner.id());
        permissions.setVisibility(id, "private");
        return id;
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + path))
            .timeout(Duration.ofSeconds(10));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("REST nie oddaje treści cudzego dokumentu prywatnego")
    void restCannotReadForeignPrivateDocument() throws Exception {
        User anna = newUser("Anna");
        User marek = newUser("Marek");
        UUID document = privateDocument(anna, "Prywatny Anny");

        try (TestStompClient writer = new TestStompClient(port, anna.token(), document, 701)) {
            writer.insert(0, "tajna treść");
            writer.awaitCondition(() -> writer.lastAckSeq() > 0, PATIENCE, "zapis potwierdzony");
        }

        assertThat(get("/api/documents/" + document, anna.token()).body())
            .as("właściciel musi widzieć własny dokument")
            .contains("tajna treść");

        HttpResponse<String> foreign = get("/api/documents/" + document, marek.token());
        assertThat(foreign.statusCode())
            .as("obcy użytkownik dostał treść cudzego dokumentu prywatnego (IDOR)")
            .isEqualTo(404);
        assertThat(foreign.body()).doesNotContain("tajna treść");

        assertThat(get("/api/documents/" + document, null).statusCode())
            .as("żądanie bez tokenu dostało treść dokumentu prywatnego")
            .isEqualTo(404);
    }

    @Test
    @DisplayName("WebSocket nie przepuszcza subskrypcji cudzego dokumentu prywatnego")
    void websocketCannotSubscribeToForeignPrivateDocument() throws Exception {
        User anna = newUser("Anna");
        User marek = newUser("Marek");
        UUID document = privateDocument(anna, "Prywatny Anny 2");

        try (TestStompClient owner = new TestStompClient(port, anna.token(), document, 702);
             TestStompClient intruder = new TestStompClient(port, marek.token(), document, 703)) {

            owner.insert(0, "podglądany tekst");
            owner.awaitCondition(() -> owner.lastAckSeq() > 0, PATIENCE, "zapis potwierdzony");
            Thread.sleep(500);

            assertThat(intruder.text())
                .as("obcy klient zobaczył treść cudzego dokumentu prywatnego NA ŻYWO")
                .isEmpty();
        }
    }

    @Test
    @DisplayName("rola tylko do odczytu nie może zmienić treści")
    void viewerCannotWriteText() throws Exception {
        User anna = newUser("Anna");
        User obserwator = newUser("Obserwator");
        UUID document = privateDocument(anna, "Do czytania");
        permissions.grant(document, obserwator.id(), Role.VIEWER);

        try (TestStompClient owner = new TestStompClient(port, anna.token(), document, 704)) {
            owner.insert(0, "oryginał");
            owner.awaitCondition(() -> owner.lastAckSeq() > 0, PATIENCE, "zapis potwierdzony");
        }

        try (TestStompClient viewer = new TestStompClient(port, obserwator.token(), document, 705)) {
            // Klient testowy nie pobiera stanu początkowego sam — buduje go z rozgłoszeń.
            // Prośba o resynchronizację jest tu więc konieczna, a przy okazji sprawdza,
            // że dla roli tylko do odczytu ta ścieżka JEST dozwolona: obserwator ma widzieć
            // dokument, tylko nie może go zmieniać.
            viewer.requestSync(0);
            viewer.awaitCondition(() -> viewer.text().equals("oryginał"), PATIENCE,
                "obserwator widzi treść");

            viewer.insert(0, "WANDALIZM");
            Thread.sleep(500);

            assertThat(documents.textOf(document))
                .as("rola tylko do odczytu zmieniła treść dokumentu")
                .isEqualTo("oryginał");
        }
    }

    @Test
    @DisplayName("odebranie dostępu zaczyna działać")
    void revokingAccessTakesEffect() throws Exception {
        User anna = newUser("Anna");
        User marek = newUser("Marek");
        UUID document = privateDocument(anna, "Chwilowo wspólny");
        permissions.grant(document, marek.id(), Role.EDITOR);

        assertThat(permissions.canWriteText(marek.id(), document)).isTrue();

        permissions.revoke(document, marek.id());

        // Unieważnienie jest częścią revoke() — bez niego zmiana zadziałałaby dopiero
        // po wygaśnięciu wpisu w cache'u, czyli po czasie, w którym użytkownik zdążyłby
        // uznać, że odebranie dostępu nie działa.
        assertThat(permissions.canRead(marek.id(), document))
            .as("odebranie dostępu nie zadziałało — uprawnienie zostało zapamiętane")
            .isFalse();
        assertThat(get("/api/documents/" + document, marek.token()).statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("dokument z widocznością 'link' pozostaje dostępny dla każdego zalogowanego")
    void linkVisibilityKeepsSharedDocumentsWorking() throws Exception {
        User anna = newUser("Anna");
        User ktokolwiek = newUser("Ktokolwiek");

        // Domyślna widoczność — dokładnie taka, jaką dostają dokumenty utworzone przed CP12
        // i wszystkie tworzone bez jawnej zmiany ustawień.
        UUID document = UUID.randomUUID();
        catalog.create(document, "Współdzielony linkiem", null);
        permissions.setOwner(document, anna.id());

        assertThat(permissions.roleOf(ktokolwiek.id(), document))
            .as("migracja odebrała dostęp do dokumentu, który wcześniej działał")
            .contains(Role.EDITOR);
        assertThat(get("/api/documents/" + document, ktokolwiek.token()).statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("dokument w koszu jest niedostępny nawet dla właściciela, ale widoczny w koszu")
    void deletedDocumentIsInvisibleEvenToOwner() throws Exception {
        User anna = newUser("Anna");
        UUID document = privateDocument(anna, "Do kosza");

        catalog.softDelete(document);
        permissions.invalidate(document);

        assertThat(permissions.canRead(anna.id(), document))
            .as("usunięty dokument nadal daje się otworzyć zwykłą ścieżką")
            .isFalse();
        assertThat(get("/api/documents/" + document, anna.token()).statusCode()).isEqualTo(404);

        // …ale kosz musi go pokazać, inaczej nie da się go przywrócić.
        assertThat(permissions.roleOfDeleted(anna.id(), document))
            .as("właściciel nie widzi własnego dokumentu w koszu — nie da się go przywrócić")
            .contains(Role.OWNER);
    }

    @Test
    @DisplayName("obcy użytkownik nie widzi cudzego dokumentu prywatnego w koszu")
    void trashDoesNotLeakForeignDocuments() {
        User anna = newUser("Anna");
        User marek = newUser("Marek");
        UUID document = privateDocument(anna, "Prywatny w koszu");
        catalog.softDelete(document);

        assertThat(permissions.roleOfDeleted(marek.id(), document))
            .as("kosz stał się listą cudzych tytułów")
            .isEmpty();
    }
}
