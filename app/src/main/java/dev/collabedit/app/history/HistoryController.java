package dev.collabedit.app.history;

import dev.collabedit.app.auth.Capability;
import dev.collabedit.app.auth.RequiresDocumentAccess;
import dev.collabedit.app.domain.DocumentRegistry;
import dev.collabedit.app.domain.DocumentService;
import dev.collabedit.crdt.MarkSet;
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
 * The timeline, previewing a past state and restoring it (ADR-0003).
 *
 * <h2>The hole that lived here for a whole checkpoint</h2>
 * <b>Authorization matters here as much as in {@code DocumentRestController}, and for one
 * checkpoint there was none.</b> CP12 closed two routes to a document's content — the WebSocket
 * and {@code GET /api/documents/{id}} — and missed a third, even though the v2 plan pointed at it
 * explicitly ("authorization only in the transport is authorization of one path"). This controller
 * handed out:
 * <ul>
 *   <li>{@code GET /history} — the timeline of any document,</li>
 *   <li>{@code GET /history/at} — the <b>full content</b> of any document at any moment,</li>
 *   <li>{@code POST /history/restore} — <b>overwriting</b> any document.</li>
 * </ul>
 *
 * <p>The last one is the worst: it is the only path in the project by which a <b>reader could
 * change the content</b> — and not by one character, but by rewinding the whole document. Restoring
 * therefore requires WRITE, not read — even though it reads from history.
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

    /**
     * A past state of the document.
     *
     * <p>Carries the marks as well since CP22. Without them the preview of a version whose only
     * change was formatting was identical to the version before it — the timeline said "3
     * operations" and the preview showed no difference at all.
     */
    public record Preview(long throughServerSeq, String text, List<MarkSet.Span> marks) {
    }

    // Returns the FULL TEXT of the document, exactly like `GET /documents/{id}` does, only
    // from a different moment. Without a check here the CP12 fix was one GET away from useless.
    @GetMapping("/at")
    @RequiresDocumentAccess(value = Capability.READ, param = "documentId")
    public ResponseEntity<Preview> textAt(@PathVariable("documentId") UUID documentId,
                                          @RequestParam("seq") long throughServerSeq) {
        var state = history.stateAt(documentId, throughServerSeq);
        return ResponseEntity.ok(new Preview(throughServerSeq, state.text(), state.marks()));
    }

    public record Comparison(long fromServerSeq, long toServerSeq,
                             List<HistoryService.Segment> segments) {
    }

    /**
     * What changed between two versions, computed from character identity (CP22).
     *
     * <p>READ, like the preview: it discloses exactly what the preview discloses, in a different
     * arrangement. Worth saying out loud, because "a diff is only a summary" is the sort of
     * reasoning that produces an endpoint with a weaker check than the one it summarises.
     */
    @GetMapping("/diff")
    @RequiresDocumentAccess(value = Capability.READ, param = "documentId")
    public ResponseEntity<Comparison> diff(@PathVariable("documentId") UUID documentId,
                                           @RequestParam("from") long fromServerSeq,
                                           @RequestParam("to") long toServerSeq) {
        return ResponseEntity.ok(new Comparison(fromServerSeq, toServerSeq,
            history.diff(documentId, fromServerSeq, toServerSeq)));
    }

    public record RestoreRequest(long throughServerSeq) {
    }

    public record RestoreResult(int operationsGenerated, String text) {
    }

    /**
     * Restores a past state.
     *
     * <p>This is not an undo of the log but a <b>new change</b> bringing the document back to its
     * former state (ADR-0003, point 6). To every connected client it looks like an ordinary edit
     * and merges normally — nobody has to reload the page or reset their replica.
     *
     * <p>The operations' author is the server replica, not the user. A deliberate simplification:
     * attributing them to the person clicking would mean generating operations on behalf of
     * someone else's replica, which is precisely the situation identifier uniqueness protects
     * against. So the audit trail shows "a version was restored", and who clicked goes into a
     * separate entry. One for the "what I would do differently in production" section.
     */
    // WRITE, not read. Restoring reads from history, but its EFFECT is a change to the text —
    // the largest change possible. A reader could once undo somebody else's whole document
    // through this endpoint. Capability.WRITE_TEXT is that sentence, enforced.
    @PostMapping("/restore")
    @RequiresDocumentAccess(value = Capability.WRITE_TEXT, param = "documentId")
    public ResponseEntity<RestoreResult> restore(@PathVariable("documentId") UUID documentId,
                                                 @RequestBody RestoreRequest body) {
        var target = history.stateAt(documentId, body.throughServerSeq());
        var replica = registry.get(documentId);

        List<Op> generated = replica.withLock(
            document -> history.restoreOperations(document, target));

        for (Op op : generated) {
            // The operations have already been through the server replica (they were created
            // there), so here they only go to storage and broadcast. Merging them again would be
            // harmless (idempotence) but misleading to read.
            documents.submitAlreadyMerged(documentId, op, null);
        }

        log.info("Restored document {} to its state at server_seq={} ({} operations)",
            documentId, body.throughServerSeq(), generated.size());

        return ResponseEntity.ok(new RestoreResult(generated.size(), target.text()));
    }
}
