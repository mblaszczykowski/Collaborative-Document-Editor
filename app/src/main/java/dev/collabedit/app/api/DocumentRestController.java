package dev.collabedit.app.api;

import dev.collabedit.app.auth.TokenService;
import dev.collabedit.app.domain.DocumentRegistry;
import dev.collabedit.app.domain.DocumentService;
import dev.collabedit.app.auth.PermissionService;
import dev.collabedit.app.auth.Role;
import dev.collabedit.app.auth.TokenAuthFilter;
import dev.collabedit.app.persist.DocumentCatalog;
import jakarta.servlet.http.HttpServletRequest;
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
    private final PermissionService permissions;

    public DocumentRestController(JdbcTemplate jdbc, DocumentService documents,
                                  DocumentRegistry registry, TokenService tokens,
                                  DocumentCatalog catalog, PermissionService permissions) {
        this.jdbc = jdbc;
        this.documents = documents;
        this.registry = registry;
        this.tokens = tokens;
        this.catalog = catalog;
        this.permissions = permissions;
    }

    /**
     * Stan początkowy dokumentu wraz z <b>rolą pytającego</b>.
     *
     * <p>Rola jest tu z tego samego powodu, dla którego odmowa z powodu tempa jest głośna
     * (zob. {@code DocumentAccessInterceptor}): egzekwowanie po stronie serwera bez
     * powiedzenia o tym klientowi zamienia odmowę w <b>cichy rozjazd</b>. Czytelnik, który
     * nie wie, że jest czytelnikiem, pisze u siebie, widzi swój tekst, a serwer go odrzuca —
     * i lokalna replika już nigdy nie zgadza się z resztą, bez jednego komunikatu.
     *
     * <p>Nie jest to przeniesienie autoryzacji na klienta. Serwer sprawdza tak samo jak
     * poprzednio; klient dostaje tę informację wyłącznie po to, żeby nie pozwolić
     * użytkownikowi wykonać gestu, który i tak zostanie odrzucony.
     */
    public record DocumentState(UUID id, String title, String text, long headServerSeq,
                                String role) {
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


    // ------------------------------------------------------------------
    // Autoryzacja (CP12)
    // ------------------------------------------------------------------

    /**
     * Identyfikator zalogowanego użytkownika albo {@code null}.
     *
     * <p>Filtr {@link TokenAuthFilter} wyłącznie USTALA tożsamość; decyzja o odmowie należy
     * do kontrolera, bo tylko on wie, czy dana ścieżka wymaga dostępu do konkretnego
     * dokumentu, czy jest publiczna.
     */
    private String callerId(HttpServletRequest request) {
        return TokenAuthFilter.principalOf(request).map(p -> p.userId()).orElse(null);
    }

    /**
     * 404 zamiast 403 przy braku dostępu — <b>świadomie</b>.
     *
     * <p>Odpowiedź „ten dokument istnieje, ale nie możesz go zobaczyć" jest sama w sobie
     * wyciekiem: pozwala sprawdzać, które identyfikatory są prawdziwe. Z punktu widzenia
     * kogoś bez uprawnień dokument ma po prostu nie istnieć — tak samo, jak dla ścieżki
     * WebSocket, gdzie subskrypcja po prostu nie działa.
     */
    private static final org.springframework.http.HttpStatus DENIED =
        org.springframework.http.HttpStatus.NOT_FOUND;

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
        @RequestParam(value = "workspace", required = false) UUID workspaceId,
        HttpServletRequest request) {

        List<DocumentCatalog.DocumentSummary> found = query != null && !query.isBlank()
            ? catalog.search(query.trim(), LIST_LIMIT)
            : catalog.list(workspaceId, LIST_LIMIT);

        // Odsiew po uprawnieniach PO stronie aplikacji, a nie w zapytaniu.
        //
        // Zapytanie z wbudowaną regułą dostępu byłoby szybsze, ale rozdzieliłoby tę regułę
        // na dwa miejsca: SQL tutaj i PermissionService gdzie indziej. Dwie kopie reguły
        // dostępu rozjeżdżają się i wtedy jedna z nich przepuszcza za dużo. Przy limicie
        // 200 pozycji koszt odsiewu jest pomijalny, a reguła zostaje w jednym miejscu.
        String caller = callerId(request);
        return found.stream().filter(doc -> permissions.canRead(caller, doc.id())).toList();
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
    public List<DocumentCatalog.DocumentSummary> trash(HttpServletRequest request) {
        String caller = callerId(request);
        // Kosz pokazuje wyłącznie dokumenty, które wołający mógłby otworzyć, gdyby nie były
        // usunięte. Bez tego kosz byłby obejściem uprawnień — listą cudzych tytułów.
        return catalog.listDeleted(LIST_LIMIT).stream()
            .filter(doc -> permissions.roleOfDeleted(caller, doc.id()).isPresent())
            .toList();
    }

    public record CreateDocumentRequest(String title, UUID workspaceId) {
    }

    @PostMapping("/documents")
    public DocumentCatalog.DocumentSummary create(@RequestBody CreateDocumentRequest body,
                                                  HttpServletRequest request) {
        UUID id = UUID.randomUUID();
        String title = body.title() == null || body.title().isBlank()
            ? "Dokument bez tytułu" : body.title().trim();
        catalog.create(id, title, body.workspaceId());

        // Twórca zostaje właścicielem. Bez tego nikt nie mógłby zmienić widoczności
        // dokumentu ani nadać do niego dostępu — a dokument bez właściciela jest
        // dokumentem, którego nie da się zabezpieczyć.
        String caller = callerId(request);
        if (caller != null) {
            permissions.setOwner(id, caller);
        }
        return catalog.find(id).orElseThrow();
    }

    public record RenameRequest(String title) {
    }

    @PatchMapping("/documents/{id}")
    public ResponseEntity<Void> rename(@PathVariable UUID id, @RequestBody RenameRequest body,
                                       HttpServletRequest request) {
        if (!permissions.canWriteText(callerId(request), id)) {
            return ResponseEntity.status(DENIED).build();
        }
        if (body.title() == null || body.title().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        return catalog.rename(id, body.title().trim())
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
    public ResponseEntity<Void> delete(@PathVariable UUID id, HttpServletRequest request) {
        // Usuwanie wymaga prawa ZAPISU, nie własności: dokument współdzielony przez zespół
        // ma dać się schować każdemu, kto go redaguje. Odwracalność tej operacji (kosz)
        // sprawia, że nie jest to ryzyko, którym trzeba zarządzać rolą.
        if (!permissions.canWriteText(callerId(request), id)) {
            return ResponseEntity.status(DENIED).build();
        }
        return catalog.softDelete(id)
            ? ResponseEntity.noContent().build()
            : ResponseEntity.notFound().build();
    }

    @PostMapping("/documents/{id}/restore")
    public ResponseEntity<Void> restore(@PathVariable UUID id, HttpServletRequest request) {
        if (permissions.roleOfDeleted(callerId(request), id).filter(Role::canWriteText).isEmpty()) {
            return ResponseEntity.status(DENIED).build();
        }
        return catalog.restore(id)
            ? ResponseEntity.noContent().build()
            : ResponseEntity.notFound().build();
    }

    // ------------------------------------------------------------------
    // Workspace'y
    // ------------------------------------------------------------------

    public record CreateWorkspaceRequest(String name) {
    }

    /**
     * Workspace'y <b>pytającego</b> — nie te, o które poprosi w parametrze.
     *
     * <p>Pierwotnie właściciel przyjeżdżał jako {@code ?owner=...} z żądania, co znaczyło,
     * że dowolny użytkownik mógł wylistować cudze workspace'y, znając czyjś identyfikator —
     * a identyfikator innej osoby widać w ramkach obecności każdego wspólnego dokumentu.
     * Ta sama klasa błędu co IDOR zamknięty w CP12, tylko na innej encji: <b>tożsamość
     * pochodząca z żądania zamiast z tokenu</b>.
     */
    @GetMapping("/workspaces")
    public ResponseEntity<List<DocumentCatalog.Workspace>> workspaces(HttpServletRequest request) {
        String caller = callerId(request);
        if (caller == null) {
            return ResponseEntity.status(DENIED).build();
        }
        return ResponseEntity.ok(catalog.workspacesOf(caller));
    }

    @PostMapping("/workspaces")
    public ResponseEntity<DocumentCatalog.Workspace> createWorkspace(
            @RequestBody CreateWorkspaceRequest body, HttpServletRequest request) {
        // Właściciel również z tokenu: bez tego dało się utworzyć workspace należący
        // do kogoś innego, czyli wstrzyknąć komuś wpis na jego własną listę.
        String caller = callerId(request);
        if (caller == null) {
            return ResponseEntity.status(DENIED).build();
        }
        UUID id = UUID.randomUUID();
        String name = body.name() == null || body.name().isBlank()
            ? "Bez nazwy" : body.name().trim();
        catalog.createWorkspace(id, name, caller);
        return catalog.workspacesOf(caller).stream()
            .filter(w -> w.id().equals(id))
            .findFirst()
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.internalServerError().build());
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
     *
     * <p><b>Właściciel i widoczność są tu ustawiane inaczej niż przy zwykłym tworzeniu</b>,
     * i jest to celowe. Zwykły dokument powstaje domyślnie jako {@code link} — bo powstaje
     * po to, żeby go komuś pokazać. Odzyskany szkic powstaje z <b>niezsynchronizowanej pracy
     * jednej osoby</b>, której nikt inny nigdy nie widział; oddanie go wszystkim zalogowanym
     * byłoby ujawnieniem treści dokładnie w tym momencie, w którym użytkownik martwi się
     * o jej utratę. Do CP12 nie miało to znaczenia (nie było widoczności), po CP12 miało —
     * i było przeoczeniem.
     */
    @PostMapping("/documents/recovered")
    public DocumentCatalog.DocumentSummary createRecovered(@RequestBody RecoveredRequest request,
                                                           HttpServletRequest httpRequest) {
        UUID id = UUID.randomUUID();
        String title = request.title() == null || request.title().isBlank()
            ? "Odzyskany szkic" : request.title();
        catalog.create(id, title, null);

        String caller = callerId(httpRequest);
        if (caller != null) {
            permissions.setOwner(id, caller);
            permissions.setVisibility(id, "private");
        }

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
    public ResponseEntity<DocumentState> open(@PathVariable UUID id, HttpServletRequest request) {
        // NAJWAŻNIEJSZE sprawdzenie w tym kontrolerze: ten endpoint zwraca PEŁNĄ TREŚĆ
        // dokumentu. Bez niego cała autoryzacja WebSocketa jest ozdobą, bo te same dane
        // wychodzą jednym żądaniem GET.
        String caller = callerId(request);
        Role role = permissions.roleOf(caller, id).orElse(null);
        if (role == null || !role.canRead()) {
            return ResponseEntity.status(DENIED).build();
        }
        // Przez katalog, nie zapytaniem wprost: katalog odsiewa dokumenty usunięte,
        // więc otwarcie schowanego dokumentu daje 404, a nie pustą treść bez wyjaśnienia.
        return catalog.find(id)
            .map(summary -> ResponseEntity.ok(new DocumentState(
                id, summary.title(), documents.textOf(id), documents.headServerSeq(id),
                role.wire())))
            .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
