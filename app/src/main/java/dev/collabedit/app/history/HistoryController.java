package dev.collabedit.app.history;

import dev.collabedit.app.auth.Capability;
import dev.collabedit.app.auth.RequiresDocumentAccess;
import dev.collabedit.app.domain.DocumentRegistry;
import dev.collabedit.app.domain.DocumentService;
import dev.collabedit.crdt.Op;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * <h2>The hole that lived here for a whole checkpoint</h2>
 * <b>Authorization matters here as much as in {@code DocumentRestController}, and for one
 * checkpoint there was none.</b> CP12 zamknął dwie drogi do treści dokumentu — WebSocket i
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
 * <p>The conclusion worth writing down: per-controller authorization is incomplete by
 * construction, because a new controller starts without it and says nothing about that.
 *
 * <p><b>CP16 acted on that conclusion.</b> The checks below are gone — not deleted, moved.
 * Each endpoint now declares what it needs with {@link RequiresDocumentAccess}, and
 * {@code DocumentAccessGuard} refuses to serve any endpoint that declares nothing at all.
 * The difference matters here more than anywhere else in the project: under the old scheme
 * this file was three {@code if} statements away from being wide open, and for one checkpoint
 * it was exactly that.
 */
@RestController
@RequestMapping("/api/documents/{documentId}/history")
@CrossOrigin(originPatterns = {"http://localhost:*", "http://127.0.0.1:*"})
public class HistoryController {

    private static final Logger log = LoggerFactory.getLogger(HistoryController.class);

    private final HistoryService history;
    private final DocumentRegistry registry;
    private final DocumentService documents;

    public HistoryController(HistoryService history, DocumentRegistry registry,
                             DocumentService documents) {
        this.history = history;
        this.registry = registry;
        this.documents = documents;
    }

    @GetMapping
    @RequiresDocumentAccess(value = Capability.READ, param = "documentId")
    public ResponseEntity<List<HistoryService.Version>> timeline(
            @PathVariable("documentId") UUID documentId) {
        return ResponseEntity.ok(history.timeline(documentId));
    }

    public record Preview(long throughServerSeq, String text) {
    }

    // Returns the FULL TEXT of the document, exactly like `GET /documents/{id}` does, only
    // from a different moment. Without a check here the CP12 fix was one GET away from useless.
    @GetMapping("/at")
    @RequiresDocumentAccess(value = Capability.READ, param = "documentId")
    public ResponseEntity<Preview> textAt(@PathVariable("documentId") UUID documentId,
                                          @RequestParam("seq") long throughServerSeq) {
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
    // WRITE, not read. Restoring reads from history, but its EFFECT is a change to the text —
    // the largest change possible. A reader could once undo somebody else's whole document
    // through this endpoint. Capability.WRITE_TEXT is that sentence, enforced.
    @PostMapping("/restore")
    @RequiresDocumentAccess(value = Capability.WRITE_TEXT, param = "documentId")
    public ResponseEntity<RestoreResult> restore(@PathVariable("documentId") UUID documentId,
                                                 @RequestBody RestoreRequest body) {
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
