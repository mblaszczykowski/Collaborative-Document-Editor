package dev.collabedit.app.history;

import dev.collabedit.app.auth.Capability;
import dev.collabedit.app.auth.RequiresDocumentAccess;
import dev.collabedit.app.domain.DocumentService;
import dev.collabedit.crdt.MarkSet;
import dev.collabedit.crdt.Op;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/documents/{documentId}/history")
public class HistoryController {
    private static final Logger log = LoggerFactory.getLogger(HistoryController.class);

    private final HistoryService history;
    private final DocumentService documents;

    public HistoryController(HistoryService history, DocumentService documents) {
        this.history = history;
        this.documents = documents;
    }

    @GetMapping
    @RequiresDocumentAccess(Capability.READ)
    public ResponseEntity<List<HistoryService.Version>> timeline(
            @PathVariable("documentId") UUID documentId) {
        return ResponseEntity.ok(history.timeline(documentId));
    }

    public record Preview(long throughServerSeq, String text, List<MarkSet.Span> marks) {
    }

    @GetMapping("/at")
    @RequiresDocumentAccess(Capability.READ)
    public ResponseEntity<Preview> textAt(@PathVariable("documentId") UUID documentId,
                                          @RequestParam("seq") long throughServerSeq) {
        var state = history.stateAt(documentId, throughServerSeq);
        return ResponseEntity.ok(new Preview(throughServerSeq, state.text(), state.marks()));
    }

    public record Comparison(long fromServerSeq, long toServerSeq,
                             List<HistoryService.Segment> segments) {
    }

    @GetMapping("/diff")
    @RequiresDocumentAccess(Capability.READ)
    public ResponseEntity<Comparison> diff(@PathVariable("documentId") UUID documentId,
                                           @RequestParam("from") long fromServerSeq,
                                           @RequestParam("to") long toServerSeq) {
        return ResponseEntity.ok(new Comparison(fromServerSeq, toServerSeq,
            history.diff(documentId, fromServerSeq, toServerSeq)));
    }

    public record RestoreRequest(@Min(1) long throughServerSeq) {
    }

    public record RestoreResult(int operationsGenerated, String text) {
    }

    @PostMapping("/restore")
    @RequiresDocumentAccess(Capability.WRITE_TEXT)
    public ResponseEntity<RestoreResult> restore(@PathVariable("documentId") UUID documentId,
                                                 @Valid @RequestBody RestoreRequest body) {
        var target = history.stateAt(documentId, body.throughServerSeq());

        List<Op> generated = documents.generateAndSubmit(
            documentId, document -> history.restoreOperations(document, target), null);

        log.info("Restored document {} to its state at server_seq={} ({} operations)",
            documentId, body.throughServerSeq(), generated.size());

        return ResponseEntity.ok(new RestoreResult(generated.size(), target.text()));
    }
}
