package dev.collabedit.app.comments;

import dev.collabedit.app.auth.Capability;
import dev.collabedit.app.auth.RequiresDocumentAccess;
import dev.collabedit.crdt.MarkTypes;
import dev.collabedit.app.auth.TokenAuthFilter;
import dev.collabedit.app.domain.DocumentRegistry;
import dev.collabedit.crdt.MarkSet;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Set;
import java.util.UUID;
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
 * Comment threads over REST (CP14).
 *
 * <h2>Why REST rather than WebSocket</h2>
 * The same split as for a document's initial state (see {@code DocumentRestController}): the
 * WebSocket channel carries <b>incremental changes that need low latency</b>. A comment is not such
 * a change — it is created once, whole, and nobody expects it to appear for others in the same
 * frame as a keystroke.
 *
 * <p>The ANCHOR, on the other hand, goes over the WebSocket, because it is an ordinary CRDT mark.
 * A thread and its anchor are therefore created by two different routes, and that is deliberate:
 * the anchor has to merge like any other operation, while the conversation's text has nothing to
 * merge.
 */
@RestController
@RequestMapping("/api")
@CrossOrigin(originPatterns = {"http://localhost:*", "http://127.0.0.1:*"})
public class CommentController {

    private final CommentService comments;
    private final DocumentRegistry registry;

    public CommentController(CommentService comments, DocumentRegistry registry) {
        this.comments = comments;
        this.registry = registry;
    }

    private String callerId(HttpServletRequest request) {
        return TokenAuthFilter.principalOf(request).map(p -> p.userId()).orElse(null);
    }

    public record OpenThreadRequest(String quotedText, String body) {
    }

    public record MessageRequest(String body) {
    }

    /**
     * A document's threads.
     *
     * <p>The {@code anchored} field is computed from the <b>CRDT layer</b>, not from the database:
     * the database does not know whether the anchored text still exists. A thread whose anchor has
     * stopped covering anything is "detached" — we show it with the quoted fragment instead of
     * deleting it.
     */
    @GetMapping("/documents/{documentId}/comments")
    @RequiresDocumentAccess(value = Capability.READ, param = "documentId")
    public ResponseEntity<List<CommentService.Thread>> threads(
        @PathVariable UUID documentId,
        @RequestParam(value = "resolved", defaultValue = "false") boolean includeResolved) {

        Set<String> anchored = anchoredThreadIds(documentId);
        List<CommentService.Thread> found = comments.threadsOf(documentId, includeResolved).stream()
            .map(thread -> new CommentService.Thread(
                thread.id(), thread.documentId(), thread.createdBy(), thread.createdAt(),
                thread.resolvedAt(), thread.quotedText(),
                anchored.contains(thread.id().toString()),
                thread.messages()))
            .toList();
        return ResponseEntity.ok(found);
    }

    /**
     * The identifiers of threads whose anchor still covers something.
     *
     * <p>Read from the replica's current state: a mark of type {@code comment} carries the thread
     * identifier as its value, and {@code formatting()} returns only the ranges that actually cover
     * something. A thread whose anchors were deleted along with the text simply does not appear
     * here.
     */
    private Set<String> anchoredThreadIds(UUID documentId) {
        return registry.get(documentId).withLock(replica -> replica.formatting().stream()
            .map(MarkSet.Span::marks)
            .map(marks -> marks.get(MarkTypes.COMMENT))
            .filter(java.util.Objects::nonNull)
            .collect(java.util.stream.Collectors.toSet()));
    }

    // Commenting needs COMMENTER, not EDITOR — that distinction is the whole point of the role
    // model, and it is now stated where the endpoint is rather than in an if further down.
    @PostMapping("/documents/{documentId}/comments")
    @RequiresDocumentAccess(value = Capability.COMMENT, param = "documentId")
    public ResponseEntity<CommentService.Thread> open(@PathVariable UUID documentId,
                                                      @RequestBody OpenThreadRequest body,
                                                      HttpServletRequest request) {
        String caller = callerId(request);
        if (body.body() == null || body.body().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        UUID threadId = comments.openThread(
            documentId, caller,
            body.quotedText() == null ? "" : body.quotedText(),
            body.body().trim());

        return comments.threadsOf(documentId, true).stream()
            .filter(thread -> thread.id().equals(threadId))
            .findFirst()
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.internalServerError().build());
    }

    // The three endpoints below are keyed by thread, not by document — the case a pure path
    // rule over /api/documents/{id}/** cannot express, and the reason the declaration is an
    // annotation. The guard resolves thread -> document before deciding.
    @PostMapping("/comments/{threadId}/messages")
    @RequiresDocumentAccess(value = Capability.COMMENT, param = "threadId",
        from = RequiresDocumentAccess.Source.COMMENT_THREAD)
    public ResponseEntity<Void> reply(@PathVariable UUID threadId,
                                      @RequestBody MessageRequest body,
                                      HttpServletRequest request) {
        String caller = callerId(request);
        if (body.body() == null || body.body().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        comments.addMessage(threadId, caller, body.body().trim());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/comments/{threadId}/resolve")
    @RequiresDocumentAccess(value = Capability.COMMENT, param = "threadId",
        from = RequiresDocumentAccess.Source.COMMENT_THREAD)
    public ResponseEntity<Void> resolve(@PathVariable UUID threadId, HttpServletRequest request) {
        String caller = callerId(request);
        return comments.resolve(threadId, caller)
            ? ResponseEntity.noContent().build()
            : ResponseEntity.status(HttpStatus.CONFLICT).build();
    }

    @PostMapping("/comments/{threadId}/reopen")
    @RequiresDocumentAccess(value = Capability.COMMENT, param = "threadId",
        from = RequiresDocumentAccess.Source.COMMENT_THREAD)
    public ResponseEntity<Void> reopen(@PathVariable UUID threadId) {
        return comments.reopen(threadId)
            ? ResponseEntity.noContent().build()
            : ResponseEntity.status(HttpStatus.CONFLICT).build();
    }
}
