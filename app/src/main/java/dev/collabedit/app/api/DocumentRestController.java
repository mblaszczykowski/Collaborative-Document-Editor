package dev.collabedit.app.api;

import dev.collabedit.app.auth.TokenService;
import dev.collabedit.app.domain.DocumentRegistry;
import dev.collabedit.app.domain.DocumentService;
import dev.collabedit.app.persist.DocumentCatalog;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST do rzeczy, które nie muszą być w czasie rzeczywistym: lista dokumentów, utworzenie
 * nowego, początkowy odczyt stanu przy otwarciu.
 *
 * <p>Podział jest celowy. Kanałem WebSocket idą wyłącznie <b>zmiany przyrostowe</b>;
 * pobranie stanu początkowego przez REST pozwala wykorzystać zwykłe mechanizmy HTTP
 * (kompresja, cache, obsługa dużych odpowiedzi) i nie obciąża ramkami po kilkaset kilobajtów
 * kanału zoptymalizowanego pod małe komunikaty (ADR-0002, limit 64 KiB na wiadomość).
 */
@RestController
@RequestMapping("/api")
@CrossOrigin(originPatterns = {"http://localhost:*", "http://127.0.0.1:*"})
public class DocumentRestController {

    private final JdbcTemplate jdbc;
    private final DocumentService documents;
    private final DocumentRegistry registry;
    private final TokenService tokens;
    private final DocumentCatalog catalog;

    public DocumentRestController(JdbcTemplate jdbc, DocumentService documents,
                                  DocumentRegistry registry, TokenService tokens,
                                  DocumentCatalog catalog) {
        this.jdbc = jdbc;
        this.documents = documents;
        this.registry = registry;
        this.tokens = tokens;
        this.catalog = catalog;
    }

    public record DocumentState(UUID id, String title, String text, long headServerSeq) {
    }

    public record LoginRequest(String displayName) {
    }

    public record LoginResponse(String token, String userId, String displayName, String color) {
    }

    /**
     * Górna granica długości nazwy widocznej dla innych.
     *
     * <p>Nazwa trafia do tokenu, do bazy i — co najistotniejsze — do <b>każdego rozgłoszenia
     * presence</b>, czyli do wiadomości wysyłanej wielokrotnie na sekundę do wszystkich
     * uczestników dokumentu. Bez limitu jeden użytkownik z nazwą o długości megabajta
     * obciąża łącze wszystkich pozostałych. 64 znaki mieszczą każde realne imię i nazwisko.
     */
    private static final int MAX_DISPLAY_NAME_LENGTH = 64;

    /**
     * Logowanie — <b>bez hasła</b>. Podajesz nazwę, dostajesz tożsamość.
     *
     * <p>Świadome uproszczenie opisane w README: zakresem projektu jest spójność danych
     * rozproszonych, nie zarządzanie tożsamością. Pełny mechanizm kont zabrałby czas
     * problemowi rdzeniowemu, nie demonstrując niczego, czego ten projekt ma dowodzić.
     */
    @PostMapping("/login")
    public LoginResponse login(@RequestBody LoginRequest request) {
        String displayName = request.displayName() == null || request.displayName().isBlank()
            ? "Gość" : request.displayName().trim();
        if (displayName.length() > MAX_DISPLAY_NAME_LENGTH) {
            displayName = displayName.substring(0, MAX_DISPLAY_NAME_LENGTH);
        }
        String userId = UUID.randomUUID().toString();
        String color = PALETTE[Math.floorMod(displayName.hashCode(), PALETTE.length)];

        jdbc.update("INSERT INTO users (id, display_name, color) VALUES (?, ?, ?) "
            + "ON CONFLICT (id) DO NOTHING", userId, displayName, color);

        return new LoginResponse(
            tokens.issue(new TokenService.Principal(userId, displayName, color)),
            userId, displayName, color);
    }

    /** Paleta z mockupu (docs/design/mockup-inkline.html) — kolory autorów w tekście. */
    private static final String[] PALETTE = {"#C1592E", "#4C5FD5", "#2E8B6B", "#8A5CB8", "#B8862E"};

    /**
     * Lista dokumentów albo wynik wyszukiwania — jedno wejście, bo z punktu widzenia
     * interfejsu to jest ta sama rzecz: „pokaż mi dokumenty, opcjonalnie zawężone".
     *
     * <p>Odpowiedź NIE zawiera treści dokumentów, tylko podgląd materializowany przy
     * snapshotowaniu. Bez tego wypisanie stu pozycji ładowałoby sto replik CRDT do pamięci
     * (CP11) — czyli lista dokumentów byłaby najdroższą operacją w całym systemie.
     */
    @GetMapping("/documents")
    public List<DocumentCatalog.DocumentSummary> list(
        @RequestParam(value = "q", required = false) String query,
        @RequestParam(value = "workspace", required = false) UUID workspaceId) {

        if (query != null && !query.isBlank()) {
            return catalog.search(query.trim(), LIST_LIMIT);
        }
        return catalog.list(workspaceId, LIST_LIMIT);
    }

    /**
     * Górna granica długości listy.
     *
     * <p>Nie ma tu stronicowania i to jest świadome uproszczenie: interfejs pokazuje listę
     * ostatnio zmienianych dokumentów, a nie archiwum. Limit istnieje po to, żeby zapytanie
     * miało ograniczony koszt niezależnie od tego, ile dokumentów jest w bazie — bez niego
     * konto z dziesiątkami tysięcy dokumentów wysyłałoby je wszystkie przy każdym otwarciu.
     */
    private static final int LIST_LIMIT = 200;

    @GetMapping("/documents/trash")
    public List<DocumentCatalog.DocumentSummary> trash() {
        return catalog.listDeleted(LIST_LIMIT);
    }

    public record CreateDocumentRequest(String title, UUID workspaceId) {
    }

    @PostMapping("/documents")
    public DocumentCatalog.DocumentSummary create(@RequestBody CreateDocumentRequest request) {
        UUID id = UUID.randomUUID();
        String title = request.title() == null || request.title().isBlank()
            ? "Dokument bez tytułu" : request.title().trim();
        catalog.create(id, title, request.workspaceId());
        return catalog.find(id).orElseThrow();
    }

    public record RenameRequest(String title) {
    }

    @PatchMapping("/documents/{id}")
    public ResponseEntity<Void> rename(@PathVariable UUID id, @RequestBody RenameRequest request) {
        if (request.title() == null || request.title().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        return catalog.rename(id, request.title().trim())
            ? ResponseEntity.noContent().build()
            : ResponseEntity.notFound().build();
    }

    /**
     * Chowa dokument. <b>Nie kasuje niczego.</b>
     *
     * <p>Tabela {@code operations} ma {@code ON DELETE CASCADE} na {@code doc_id}, więc
     * twarde usunięcie skasowałoby log operacji — źródło prawdy z ADR-0003, wyłącznie
     * dopisywane. Użytkownik klikający „usuń" chce, żeby dokument zniknął mu z listy,
     * a nie żeby przestał istnieć.
     */
    @DeleteMapping("/documents/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        return catalog.softDelete(id)
            ? ResponseEntity.noContent().build()
            : ResponseEntity.notFound().build();
    }

    @PostMapping("/documents/{id}/restore")
    public ResponseEntity<Void> restore(@PathVariable UUID id) {
        return catalog.restore(id)
            ? ResponseEntity.noContent().build()
            : ResponseEntity.notFound().build();
    }

    // ------------------------------------------------------------------
    // Workspace'y
    // ------------------------------------------------------------------

    public record CreateWorkspaceRequest(String name, String ownerId) {
    }

    @GetMapping("/workspaces")
    public List<DocumentCatalog.Workspace> workspaces(@RequestParam("owner") String ownerId) {
        return catalog.workspacesOf(ownerId);
    }

    @PostMapping("/workspaces")
    public DocumentCatalog.Workspace createWorkspace(@RequestBody CreateWorkspaceRequest request) {
        UUID id = UUID.randomUUID();
        String name = request.name() == null || request.name().isBlank()
            ? "Bez nazwy" : request.name().trim();
        catalog.createWorkspace(id, name, request.ownerId());
        return catalog.workspacesOf(request.ownerId()).stream()
            .filter(w -> w.id().equals(id))
            .findFirst()
            .orElseThrow();
    }

    public record RecoveredRequest(String title, String text) {
    }

    /**
     * Tworzy dokument z odzyskaną treścią.
     *
     * <p>Ścieżka ratunkowa z ADR-0005: klient był offline dłużej niż okno GC, jego operacje
     * wskazują na tombstone'y, których już nie ma, i <b>nie da się ich uczciwie scalić</b>.
     * Zamiast doklejać tekst w przypadkowe miejsce albo go zgubić, zapisujemy lokalną wersję
     * jako osobny dokument i mówimy o tym użytkownikowi wprost.
     *
     * <p>Treść jest wstawiana jako zwykłe operacje repliki serwera — nowy dokument nie jest
     * więc niczym szczególnym i od pierwszej chwili ma pełną historię, jak każdy inny.
     */
    @PostMapping("/documents/recovered")
    public DocumentCatalog.DocumentSummary createRecovered(@RequestBody RecoveredRequest request) {
        UUID id = UUID.randomUUID();
        String title = request.title() == null || request.title().isBlank()
            ? "Odzyskany szkic" : request.title();
        catalog.create(id, title, null);

        String text = request.text() == null ? "" : request.text();
        if (!text.isEmpty()) {
            var replica = registry.get(id);
            var op = replica.withLock(model -> model.insert(0, text));
            documents.submitAlreadyMerged(id, op, null);
        }
        return catalog.find(id).orElseThrow();
    }

    /**
     * Stan początkowy dokumentu.
     *
     * <p>{@code headServerSeq} jest tu istotny: klient zapamiętuje go jako punkt, od którego
     * prosi o zmiany przy ponownym połączeniu. Bez niego po każdym rozłączeniu musiałby
     * pobierać całą historię od zera.
     */
    @GetMapping("/documents/{id}")
    public ResponseEntity<DocumentState> open(@PathVariable UUID id) {
        // Przez katalog, nie zapytaniem wprost: katalog odsiewa dokumenty usunięte,
        // więc otwarcie schowanego dokumentu daje 404, a nie pustą treść bez wyjaśnienia.
        return catalog.find(id)
            .map(summary -> ResponseEntity.ok(new DocumentState(
                id, summary.title(), documents.textOf(id), documents.headServerSeq(id))))
            .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
