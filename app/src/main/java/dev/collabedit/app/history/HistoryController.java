package dev.collabedit.app.history;

import dev.collabedit.app.auth.PermissionService;
import dev.collabedit.app.auth.Role;
import dev.collabedit.app.auth.TokenAuthFilter;
import dev.collabedit.app.domain.DocumentRegistry;
import dev.collabedit.app.domain.DocumentService;
import dev.collabedit.crdt.Op;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Oś czasu, podgląd stanu z przeszłości i przywracanie (ADR-0003).
 *
 * <p><b>Autoryzacja jest tu równie istotna jak w {@code DocumentRestController}, i przez jeden
 * checkpoint jej nie było.</b> CP12 zamknął dwie drogi do treści dokumentu — WebSocket i
 * {@code GET /api/documents/{id}} — i przeoczył trzecią, mimo że plan v2 wskazywał ją wprost
 * („autoryzacja wyłącznie w transporcie jest autoryzacją jednej ścieżki"). Ten kontroler
 * oddawał:
 * <ul>
 *   <li>{@code GET /history} — oś czasu dowolnego dokumentu,</li>
 *   <li>{@code GET /history/at} — <b>pełną treść</b> dowolnego dokumentu w dowolnej chwili,</li>
 *   <li>{@code POST /history/restore} — <b>nadpisanie</b> dowolnego dokumentu.</li>
 * </ul>
 *
 * <p>Ostatni punkt jest najgorszy: to jedyna w projekcie ścieżka, którą <b>czytelnik mógł
 * zmienić treść</b>, i to nie o znak, tylko cofając cały dokument. Przywracanie wymaga więc
 * prawa ZAPISU, a nie odczytu — mimo że czyta z historii.
 *
 * <p>Wniosek, który wart jest zapisania: autoryzacja per kontroler jest z definicji
 * niekompletna, bo nowy kontroler startuje bez niej i nic o tym nie mówi. Właściwym
 * rozwiązaniem jest reguła wspólna dla wszystkich ścieżek pod {@code /api/documents/{id}/**},
 * wymuszona filtrem — patrz README, sekcja o granicach.
 */
@RestController
@RequestMapping("/api/documents/{documentId}/history")
@CrossOrigin(originPatterns = {"http://localhost:*", "http://127.0.0.1:*"})
public class HistoryController {

    private static final Logger log = LoggerFactory.getLogger(HistoryController.class);

    /** 404 zamiast 403 — ten sam powód co w {@code DocumentRestController}: brak potwierdzenia,
     *  że dokument istnieje. */
    private static final HttpStatus DENIED = HttpStatus.NOT_FOUND;

    private final HistoryService history;
    private final DocumentRegistry registry;
    private final DocumentService documents;
    private final PermissionService permissions;

    public HistoryController(HistoryService history, DocumentRegistry registry,
                             DocumentService documents, PermissionService permissions) {
        this.history = history;
        this.registry = registry;
        this.documents = documents;
        this.permissions = permissions;
    }

    private String callerId(HttpServletRequest request) {
        return TokenAuthFilter.principalOf(request).map(p -> p.userId()).orElse(null);
    }

    @GetMapping
    public ResponseEntity<List<HistoryService.Version>> timeline(
            @PathVariable("documentId") UUID documentId, HttpServletRequest request) {
        if (!permissions.canRead(callerId(request), documentId)) {
            return ResponseEntity.status(DENIED).build();
        }
        return ResponseEntity.ok(history.timeline(documentId));
    }

    public record Preview(long throughServerSeq, String text) {
    }

    @GetMapping("/at")
    public ResponseEntity<Preview> textAt(@PathVariable("documentId") UUID documentId,
                                          @RequestParam("seq") long throughServerSeq,
                                          HttpServletRequest request) {
        // Ten endpoint zwraca PEŁNĄ TREŚĆ dokumentu — dokładnie tak samo jak `GET /documents/{id}`,
        // tylko z innej chwili. Bez tego sprawdzenia poprawka z CP12 była obejściem jednym GET-em.
        if (!permissions.canRead(callerId(request), documentId)) {
            return ResponseEntity.status(DENIED).build();
        }
        return ResponseEntity.ok(
            new Preview(throughServerSeq, history.textAt(documentId, throughServerSeq)));
    }

    public record RestoreRequest(long throughServerSeq) {
    }

    public record RestoreResult(int operationsGenerated, String text) {
    }

    /**
     * Przywraca stan z przeszłości.
     *
     * <p>Nie jest to cofnięcie logu, tylko <b>nowa zmiana</b> sprowadzająca dokument do
     * dawnego stanu (ADR-0003 pkt 6). Dla wszystkich podłączonych klientów wygląda to jak
     * zwykła edycja i scala się normalnie — nikt nie musi odświeżać strony ani resetować
     * swojej repliki.
     *
     * <p>Autorem operacji jest replika serwera, nie użytkownik. To świadome uproszczenie:
     * przypisanie ich osobie klikającej wymagałoby generowania operacji w imieniu cudzej
     * repliki, czyli dokładnie tej sytuacji, przed którą chroni unikalność identyfikatorów.
     * W audycie widać więc „przywrócono wersję", a kto kliknął — trafia do osobnego wpisu.
     * Do sekcji „co zrobiłbym inaczej w produkcji".
     */
    @PostMapping("/restore")
    public ResponseEntity<RestoreResult> restore(@PathVariable("documentId") UUID documentId,
                                                 @RequestBody RestoreRequest body,
                                                 HttpServletRequest request) {
        // Prawo ZAPISU, nie odczytu. Przywracanie czyta z historii, ale SKUTKIEM jest zmiana
        // treści — i to zmiana największa z możliwych. Czytelnik z prawem odczytu mógł tędy
        // cofnąć cały cudzy dokument.
        if (permissions.roleOf(callerId(request), documentId)
                .filter(Role::canWriteText).isEmpty()) {
            return ResponseEntity.status(DENIED).build();
        }
        String target = history.textAt(documentId, body.throughServerSeq());
        var replica = registry.get(documentId);

        List<Op> generated = replica.withLock(
            document -> history.restoreOperations(document, target));

        for (Op op : generated) {
            // Operacje przeszły już przez replikę serwera (powstały na niej), więc tutaj
            // trafiają wyłącznie do zapisu i rozgłoszenia. Ponowne scalanie byłoby
            // nieszkodliwe (idempotencja), ale mylące przy czytaniu kodu.
            documents.submitAlreadyMerged(documentId, op, null);
        }

        log.info("Przywrócono dokument {} do stanu na server_seq={} ({} operacji)",
            documentId, body.throughServerSeq(), generated.size());

        return ResponseEntity.ok(new RestoreResult(generated.size(), target));
    }
}
