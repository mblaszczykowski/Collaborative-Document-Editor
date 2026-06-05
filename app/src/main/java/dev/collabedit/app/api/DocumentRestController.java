package dev.collabedit.app.api;

import dev.collabedit.app.auth.TokenService;
import dev.collabedit.app.domain.DocumentService;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
    private final TokenService tokens;

    public DocumentRestController(JdbcTemplate jdbc, DocumentService documents, TokenService tokens) {
        this.jdbc = jdbc;
        this.documents = documents;
        this.tokens = tokens;
    }

    public record DocumentSummary(UUID id, String title) {
    }

    public record DocumentState(UUID id, String title, String text, long headServerSeq) {
    }

    public record LoginRequest(String displayName) {
    }

    public record LoginResponse(String token, String userId, String displayName, String color) {
    }

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

    @GetMapping("/documents")
    public List<DocumentSummary> list() {
        return jdbc.query("SELECT id, title FROM documents ORDER BY updated_at DESC",
            (rs, rowNum) -> new DocumentSummary(
                UUID.fromString(rs.getString("id")), rs.getString("title")));
    }

    @PostMapping("/documents")
    public DocumentSummary create(@RequestBody DocumentSummary request) {
        UUID id = UUID.randomUUID();
        String title = request.title() == null || request.title().isBlank()
            ? "Dokument bez tytułu" : request.title();
        jdbc.update("INSERT INTO documents (id, title) VALUES (?, ?)", id, title);
        return new DocumentSummary(id, title);
    }

    /**
     * Stan początkowy dokumentu.
     *
     * <p>{@code headServerSeq} jest tu istotny: klient zapamiętuje go jako punkt, od którego
     * prosi o zmiany przy ponownym połączeniu. Bez niego po każdym rozłączeniu musiałby
     * pobierać całą historię od zera.
     */
    @GetMapping("/documents/{id}")
    public DocumentState open(@PathVariable UUID id) {
        String title = jdbc.queryForObject(
            "SELECT title FROM documents WHERE id = ?", String.class, id);
        return new DocumentState(id, title, documents.textOf(id), documents.headServerSeq(id));
    }
}
