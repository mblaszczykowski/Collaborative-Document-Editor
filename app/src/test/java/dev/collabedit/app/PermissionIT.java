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

    private HttpResponse<String> post(String path, String token, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + path))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(10))
            .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
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
    @DisplayName("rola tylko do odczytu nie może też SFORMATOWAĆ tekstu")
    void viewerCannotFormatText() throws Exception {
        User anna = newUser("Anna");
        User obserwator = newUser("Obserwator");
        UUID document = privateDocument(anna, "Do czytania, nie do pogrubiania");
        permissions.grant(document, obserwator.id(), Role.VIEWER);

        try (TestStompClient owner = new TestStompClient(port, anna.token(), document, 706)) {
            owner.insert(0, "oryginał");
            owner.awaitCondition(() -> owner.lastAckSeq() > 0, PATIENCE, "zapis potwierdzony");
        }

        try (TestStompClient viewer = new TestStompClient(port, obserwator.token(), document, 707)) {
            viewer.requestSync(0);
            viewer.awaitCondition(() -> viewer.text().equals("oryginał"), PATIENCE, "stan pobrany");

            // Formatowanie idzie TYM SAMYM kanałem co tekst i jest tak samo zmianą dokumentu.
            // Osobny test, bo w interfejsie prowadzi do niego osobne wejście — skrót
            // klawiszowy, którego `EditorState.readOnly` nie blokuje.
            viewer.markReturningOp(0, 5, "bold", null);
            Thread.sleep(500);
        }

        try (TestStompClient check = new TestStompClient(port, anna.token(), document, 708)) {
            check.requestSync(0);
            check.awaitCondition(() -> check.text().equals("oryginał"), PATIENCE, "stan pobrany");
            assertThat(check.markCount())
                .as("czytelnik nadał formatowanie — rola tylko do odczytu zmieniła dokument")
                .isZero();
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
    @DisplayName("stan początkowy niesie rolę pytającego, a nie rolę właściciela")
    void openedDocumentCarriesCallersRole() throws Exception {
        User anna = newUser("Anna");
        User czytelnik = newUser("Czytelnik");
        User komentator = newUser("Komentator");
        UUID document = privateDocument(anna, "Z rolami");
        permissions.grant(document, czytelnik.id(), Role.VIEWER);
        permissions.grant(document, komentator.id(), Role.COMMENTER);

        // Rola jedzie razem ze stanem początkowym z tego samego powodu, dla którego odmowa
        // z powodu tempa jest głośna: egzekwowanie po stronie serwera BEZ powiedzenia o tym
        // klientowi zamienia odmowę w cichy rozjazd. Czytelnik, który nie wie, że jest
        // czytelnikiem, pisze u siebie, widzi swój tekst, a serwer go odrzuca — i lokalna
        // replika już nigdy nie zgadza się z resztą, bez jednego komunikatu.
        assertThat(get("/api/documents/" + document, anna.token()).body())
            .as("właściciel nie dostał swojej roli")
            .contains("\"role\":\"owner\"");
        assertThat(get("/api/documents/" + document, czytelnik.token()).body())
            .as("czytelnik dostał rolę pozwalającą pisać")
            .contains("\"role\":\"viewer\"");
        assertThat(get("/api/documents/" + document, komentator.token()).body())
            .contains("\"role\":\"commenter\"");
    }

    @Test
    @DisplayName("historia nie jest trzecią drogą do cudzego dokumentu — ani do odczytu, ani do zapisu")
    void historyEndpointsCheckAccess() throws Exception {
        User anna = newUser("Anna");
        User obcy = newUser("Obcy");
        User czytelnik = newUser("Czytelnik");
        UUID document = privateDocument(anna, "Prywatny z historią");
        permissions.grant(document, czytelnik.id(), Role.VIEWER);

        try (TestStompClient writer = new TestStompClient(port, anna.token(), document, 731)) {
            writer.insert(0, "tajna treść");
            writer.awaitCondition(() -> writer.lastAckSeq() > 0, PATIENCE, "zapis potwierdzony");
        }

        // CP12 zamknął DWIE drogi do treści — WebSocket i GET /documents/{id} — i przeoczył
        // trzecią, mimo że plan v2 wskazywał ją wprost. Historia oddawała pełną treść
        // dowolnego dokumentu, więc poprawka z CP12 była do obejścia jednym żądaniem.
        assertThat(get("/api/documents/" + document + "/history", obcy.token()).statusCode())
            .as("oś czasu cudzego dokumentu prywatnego jest widoczna")
            .isEqualTo(404);
        assertThat(get("/api/documents/" + document + "/history/at?seq=1", obcy.token()).body())
            .as("historia oddaje treść cudzego dokumentu prywatnego")
            .doesNotContain("tajna");

        // NAJWAŻNIEJSZE: przywracanie zmienia treść, więc wymaga prawa ZAPISU, a nie odczytu.
        // Bez tego czytelnik miał jedyną w projekcie drogę do zmiany cudzego tekstu — i to
        // nie o znak, tylko cofając cały dokument.
        assertThat(post("/api/documents/" + document + "/history/restore",
                czytelnik.token(), "{\"throughServerSeq\":1}").statusCode())
            .as("czytelnik przywrócił wersję — rola tylko do odczytu potrafi zmienić treść")
            .isEqualTo(404);
        assertThat(post("/api/documents/" + document + "/history/restore",
                obcy.token(), "{\"throughServerSeq\":1}").statusCode())
            .isEqualTo(404);

        // …a właścicielowi wolno, inaczej zabezpieczenie zepsułoby funkcję.
        assertThat(post("/api/documents/" + document + "/history/restore",
                anna.token(), "{\"throughServerSeq\":1}").statusCode())
            .as("zabezpieczenie odebrało dostęp również właścicielowi")
            .isEqualTo(200);
    }

    @Test
    @DisplayName("rola nadana per dokument nadpisuje rolę odziedziczoną z workspace'u")
    void documentRoleOverridesWorkspaceRole() {
        User anna = newUser("Anna");
        User zespol = newUser("Członek zespołu");

        UUID workspace = UUID.randomUUID();
        jdbc.update("INSERT INTO workspaces (id, name, owner_id) VALUES (?, ?, ?)",
            workspace, "Zespół", anna.id());
        jdbc.update("INSERT INTO workspace_members (workspace_id, user_id, role) VALUES (?, ?, ?)",
            workspace, zespol.id(), "editor");

        UUID document = privateDocument(anna, "Dokument zespołu");
        jdbc.update("UPDATE documents SET workspace_id = ? WHERE id = ?", workspace, document);
        permissions.invalidate(document);

        assertThat(permissions.roleOf(zespol.id(), document))
            .as("dziedziczenie z workspace'u nie działa — dokument zespołu jest niewidoczny")
            .contains(Role.EDITOR);

        // Sedno: nadpisanie musi działać W DÓŁ, nie tylko w górę. Gdyby wygrywała rola
        // silniejsza, nie dałoby się nikomu ograniczyć dostępu do JEDNEGO dokumentu
        // bez wyrzucania go z całego workspace'u — czyli dziedziczenie odbierałoby
        // kontrolę zamiast ją dawać.
        permissions.grant(document, zespol.id(), Role.VIEWER);

        assertThat(permissions.roleOf(zespol.id(), document))
            .as("rola per dokument nie nadpisała odziedziczonej — ograniczenie dostępu nie działa")
            .contains(Role.VIEWER);
        assertThat(permissions.canWriteText(zespol.id(), document)).isFalse();
    }

    @Test
    @DisplayName("tożsamość bierze się z tokenu, a nie z parametru żądania")
    void identityComesFromTokenNotFromRequest() throws Exception {
        User anna = newUser("Anna");
        User ciekawski = newUser("Ciekawski");

        UUID workspace = UUID.randomUUID();
        jdbc.update("INSERT INTO workspaces (id, name, owner_id) VALUES (?, ?, ?)",
            workspace, "Prywatny zespół Anny", anna.id());

        // Identyfikator innego użytkownika NIE jest sekretem — widać go w każdej ramce
        // obecności wspólnego dokumentu. Autoryzacja oparta na tym, że ktoś go nie zna,
        // nie jest autoryzacją; ten sam błąd co IDOR zamknięty w CP12, tylko na innej encji.
        assertThat(get("/api/workspaces?owner=" + anna.id(), ciekawski.token()).body())
            .as("parametr żądania decyduje, czyje workspace'y zobaczy pytający")
            .doesNotContain("Prywatny zespół Anny");

        assertThat(get("/api/workspaces?owner=" + anna.id(), anna.token()).body())
            .as("właściciel przestał widzieć własne workspace'y")
            .contains("Prywatny zespół Anny");

        // Tworzenie też: bez tego dało się wstawić komuś wpis na jego własną listę.
        post("/api/workspaces", ciekawski.token(), "{\"name\":\"Podrzucony\"}");
        assertThat(get("/api/workspaces", anna.token()).body())
            .as("obcy utworzył workspace należący do kogoś innego")
            .doesNotContain("Podrzucony");
    }

    @Test
    @DisplayName("odzyskany szkic należy do osoby, która go odzyskała, i jest prywatny")
    void recoveredDraftIsPrivateToItsOwner() throws Exception {
        User anna = newUser("Anna");
        User ktokolwiek = newUser("Ktokolwiek");

        String created = post("/api/documents/recovered", anna.token(),
            "{\"title\":\"Odzyskany szkic Anny\",\"text\":\"praca offline\"}").body();
        UUID id = UUID.fromString(created.replaceAll(".*\"id\"\\s*:\\s*\"([^\"]+)\".*", "$1"));

        // Szkic powstaje z niezsynchronizowanej pracy JEDNEJ osoby, której nikt nigdy nie
        // widział. Domyślna widoczność „link" oddałaby ją wszystkim zalogowanym dokładnie
        // w chwili, w której użytkownik martwi się o jej utratę.
        assertThat(get("/api/documents/" + id, ktokolwiek.token()).statusCode())
            .as("odzyskany szkic jest czytelny dla każdego zalogowanego")
            .isEqualTo(404);
        assertThat(get("/api/documents/" + id, anna.token()).body())
            .as("odzyskujący nie ma dostępu do własnego szkicu")
            .contains("praca offline");
        assertThat(permissions.roleOf(anna.id(), id))
            .as("odzyskujący nie jest właścicielem, więc nie może zmienić widoczności")
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
