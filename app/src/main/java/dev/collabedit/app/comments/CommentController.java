package dev.collabedit.app.comments;

import dev.collabedit.app.auth.Capability;
import dev.collabedit.app.auth.RequiresDocumentAccess;
import dev.collabedit.crdt.MarkTypes;
import dev.collabedit.app.auth.TokenAuthFilter;
import dev.collabedit.app.domain.DocumentRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class CommentController {
    private final CommentService comments;
    private final DocumentRegistry registry;

    public CommentController(CommentService comments, DocumentRegistry registry) {
        this.comments = comments;
        this.registry = registry;
    }

    private static final int MAX_BODY_LENGTH = 4_000;

    private static final int MAX_QUOTE_LENGTH = 8_192;

    public record OpenThreadRequest(@Size(max = MAX_QUOTE_LENGTH) String quotedText,
                                    @Size(max = MAX_BODY_LENGTH) String body,
                                    @Size(max = 200) String clientId) {
    }

    public record MessageRequest(@Size(max = MAX_BODY_LENGTH) String body,
                                 @Size(max = 200) String clientId) {
    }

    @GetMapping("/documents/{documentId}/comments")
    @RequiresDocumentAccess(Capability.READ)
    public ResponseEntity<List<CommentService.Thread>> threads(
        @PathVariable UUID documentId,
        @RequestParam(value = "resolved", defaultValue = "false") boolean includeResolved) {
        List<CommentService.Thread> threads = comments.threadsOf(documentId, includeResolved);
        if (threads.isEmpty()) {
            return ResponseEntity.ok(List.of());
        }
        Set<String> anchored = anchoredThreadIds(documentId);
        List<CommentService.Thread> found = threads.stream()
            .map(thread -> new CommentService.Thread(
                thread.id(), thread.documentId(), thread.createdBy(), thread.createdAt(),
                thread.resolvedAt(), thread.quotedText(),
                anchored.contains(thread.id().toString()),
                thread.messages()))
            .toList();
        return ResponseEntity.ok(found);
    }

    private Set<String> anchoredThreadIds(UUID documentId) {
        return registry.get(documentId).withLock(
            replica -> Set.copyOf(replica.markPlacements(MarkTypes.COMMENT).keySet()));
    }

    @PostMapping("/documents/{documentId}/comments")
    @RequiresDocumentAccess(Capability.COMMENT)
    public ResponseEntity<CommentService.Thread> open(@PathVariable UUID documentId,
                                                      @Valid @RequestBody OpenThreadRequest body,
                                                      HttpServletRequest request) {
        String caller = TokenAuthFilter.requiredCallerId(request);
        if (body.body() == null || body.body().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        UUID threadId = comments.openThread(
            documentId, caller,
            body.quotedText() == null ? "" : body.quotedText(),
            body.body().trim(),
            body.clientId());

        return comments.findThread(threadId)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.internalServerError().build());
    }

    @PostMapping("/comments/{threadId}/messages")
    @RequiresDocumentAccess(value = Capability.COMMENT, param = "threadId",
        from = RequiresDocumentAccess.Source.COMMENT_THREAD)
    public ResponseEntity<Void> reply(@PathVariable UUID threadId,
                                      @Valid @RequestBody MessageRequest body,
                                      HttpServletRequest request) {
        String caller = TokenAuthFilter.requiredCallerId(request);
        if (body.body() == null || body.body().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        comments.addMessage(threadId, caller, body.body().trim(), body.clientId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/comments/{threadId}/resolve")
    @RequiresDocumentAccess(value = Capability.COMMENT, param = "threadId",
        from = RequiresDocumentAccess.Source.COMMENT_THREAD)
    public ResponseEntity<Void> resolve(@PathVariable UUID threadId, HttpServletRequest request) {
        String caller = TokenAuthFilter.requiredCallerId(request);
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
