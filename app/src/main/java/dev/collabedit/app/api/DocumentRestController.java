package dev.collabedit.app.api;

import dev.collabedit.app.auth.TokenService;
import dev.collabedit.app.domain.DocumentRegistry;
import dev.collabedit.app.domain.DocumentService;
import dev.collabedit.app.auth.Capability;
import dev.collabedit.app.auth.DocumentAccessGuard;
import dev.collabedit.app.auth.PermissionService;
import dev.collabedit.app.auth.PublicEndpoint;
import dev.collabedit.app.auth.RequiresAuthentication;
import dev.collabedit.app.auth.RequiresDocumentAccess;
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
    @PublicEndpoint("Issues identity. There is nothing to authorize against yet — this is the "
        + "endpoint that produces the token every other endpoint checks.")
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
    @RequiresAuthentication("Returns only what the caller may read; the filter is per row, "
        + "below, because the answer is a list rather than one document.")
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
    @RequiresAuthentication("Same shape as the list above: filtered per row, so no single "
        + "document is being authorized here.")
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
    @RequiresAuthentication("Creates a document, so there is none to check yet — but there "
        + "must be a user to own it. See below for what used to happen without one.")
    public DocumentCatalog.DocumentSummary create(@RequestBody CreateDocumentRequest body,
                                                  HttpServletRequest request) {
        UUID id = UUID.randomUUID();
        String title = body.title() == null || body.title().isBlank()
            ? "Dokument bez tytułu" : body.title().trim();
        catalog.create(id, title, body.workspaceId());

        // The creator becomes the owner. Without that nobody could change the document's
        // visibility or grant access to it — a document with no owner cannot be secured
        // by anyone, ever.
        //
        // This used to sit inside `if (caller != null)`, which quietly produced exactly that
        // unsecurable document whenever the token was missing or expired. The annotation above
        // is what makes the unconditional call correct: the guard has already refused the
        // request if there is no caller.
        permissions.setOwner(id, callerId(request));
        return catalog.find(id).orElseThrow();
    }

    public record RenameRequest(String title) {
    }

    @PatchMapping("/documents/{id}")
    @RequiresDocumentAccess(Capability.WRITE_TEXT)
    public ResponseEntity<Void> rename(@PathVariable UUID id, @RequestBody RenameRequest body) {
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
    // Deleting needs WRITE, not ownership: a document a team shares should be hideable by
    // anyone who edits it. The operation being reversible (trash) is what makes that safe.
    @DeleteMapping("/documents/{id}")
    @RequiresDocumentAccess(Capability.WRITE_TEXT)
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        return catalog.softDelete(id)
            ? ResponseEntity.noContent().build()
            : ResponseEntity.notFound().build();
    }

    // includeDeleted, because restoring a document from the trash is by definition an
    // operation on a deleted one — and must not skip the permission check while doing so,
    // or the trash becomes a way to reach other people's documents.
    @PostMapping("/documents/{id}/restore")
    @RequiresDocumentAccess(value = Capability.WRITE_TEXT, includeDeleted = true)
    public ResponseEntity<Void> restore(@PathVariable UUID id) {
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
    @RequiresAuthentication("Scoped to the caller's own workspaces; identity comes from the "
        + "token, never from a request parameter.")
    public ResponseEntity<List<DocumentCatalog.Workspace>> workspaces(HttpServletRequest request) {
        return ResponseEntity.ok(catalog.workspacesOf(callerId(request)));
    }

    @PostMapping("/workspaces")
    @RequiresAuthentication("Creates a workspace owned by the caller; nothing exists yet to "
        + "authorize against.")
    public ResponseEntity<DocumentCatalog.Workspace> createWorkspace(
            @RequestBody CreateWorkspaceRequest body, HttpServletRequest request) {
        // Owner from the token as well: without that it was possible to create a workspace
        // belonging to somebody else, i.e. to inject an entry onto their own list.
        String caller = callerId(request);
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
    @RequiresAuthentication("Creates a document from work that was never synchronised, so "
        + "there is nothing to authorize against — but an anonymous caller here produced an "
        + "ownerless, link-visible draft, which is the exact opposite of the intent.")
    public DocumentCatalog.DocumentSummary createRecovered(@RequestBody RecoveredRequest request,
                                                           HttpServletRequest httpRequest) {
        UUID id = UUID.randomUUID();
        String title = request.title() == null || request.title().isBlank()
            ? "Odzyskany szkic" : request.title();
        catalog.create(id, title, null);

        // Unconditional, and that is the fix. Both calls used to sit inside
        // `if (caller != null)`, so a missing or EXPIRED token skipped them both: no owner,
        // and `visibility` falling back to the schema default of 'link' — which
        // PermissionService rule 4 reads as EDITOR for every logged-in user.
        //
        // Not a corner case. A token lives 12 hours; this endpoint exists only for clients
        // that were offline longer than the GC window of 7 days. The population it was
        // written for is the population whose token has certainly expired.
        String caller = callerId(httpRequest);
        permissions.setOwner(id, caller);
        permissions.setVisibility(id, "private");

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
    // The most important check in this controller: this endpoint returns the FULL TEXT of the
    // document. Without it the whole WebSocket authorization is decoration, because the same
    // data leaves through one GET.
    @GetMapping("/documents/{id}")
    @RequiresDocumentAccess(Capability.READ)
    public ResponseEntity<DocumentState> open(@PathVariable UUID id, HttpServletRequest request) {
        // The role the guard admitted this request with, rather than a second lookup. Asking
        // again could straddle the 5 s permission cache expiry and answer differently than the
        // answer this request was let in on.
        Role role = DocumentAccessGuard.resolvedRole(request).orElseThrow();
        // Through the catalog rather than a direct query: the catalog filters out deleted
        // documents, so opening a hidden one gives 404 rather than empty text with no reason.
        return catalog.find(id)
            .map(summary -> ResponseEntity.ok(new DocumentState(
                id, summary.title(), documents.textOf(id), documents.headServerSeq(id),
                role.wire())))
            .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
