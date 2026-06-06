package dev.collabedit.app.history;

import dev.collabedit.app.domain.DocumentRegistry;
import dev.collabedit.app.domain.DocumentService;
import dev.collabedit.crdt.Op;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Oś czasu, podgląd stanu z przeszłości i przywracanie (ADR-0003). */
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
    public List<HistoryService.Version> timeline(@PathVariable("documentId") UUID documentId) {
        return history.timeline(documentId);
    }

    public record Preview(long throughServerSeq, String text) {
    }

    @GetMapping("/at")
    public Preview textAt(@PathVariable("documentId") UUID documentId,
                          @RequestParam("seq") long throughServerSeq) {
        return new Preview(throughServerSeq, history.textAt(documentId, throughServerSeq));
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
    public RestoreResult restore(@PathVariable("documentId") UUID documentId,
                                 @RequestBody RestoreRequest request) {
        String target = history.textAt(documentId, request.throughServerSeq());
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
            documentId, request.throughServerSeq(), generated.size());

        return new RestoreResult(generated.size(), target);
    }
}
