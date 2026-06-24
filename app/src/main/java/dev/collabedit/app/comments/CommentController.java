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
public class CommentController {

    private final CommentService comments;
    private final DocumentRegistry registry;

    public CommentController(CommentService comments, DocumentRegistry registry) {
        this.comments = comments;
        this.registry = registry;
    }

    /**
     * The upper bound on one comment or reply.
     *
     * <p>A comment is a person writing a sentence, and nothing downstream treats it as anything
     * else: it is stored whole, read back whole with the thread, and rendered in a side panel.
     * Unbounded, it was a way to put arbitrary bytes into the database through an endpoint that
     * needs only {@code COMMENT} — the LOWEST role that can write anything at all.
     *
     * <p>4000 characters is about two pages of prose. Chosen large enough that nobody writing a
     * genuine comment meets it, and small enough that a thread of them stays a thread rather than
     * a document.
     */
    private static final int MAX_BODY_LENGTH = 4_000;

    /**
     * The upper bound on the quoted fragment stored with a thread.
     *
     * <p>The quote is what the panel shows when the anchored text has been deleted (CP14: the
     * thread survives, marked as detached), so it has to be able to hold a real selection — but it
     * is the CLIENT's copy of text the document already owns, and nothing verifies it. 8192
     * characters is the same bound the paste path uses, so a selection somebody could paste is a
     * selection they can quote.
     */
    private static final int MAX_QUOTE_LENGTH = 8_192;

    /**
     * @param clientId the caller's own identifier for this comment, so a retry after an
     *                 uncertain connection does not post it twice (CP27). Optional — a client
     *                 that does not send one gets the old always-insert behaviour
     */
    public record OpenThreadRequest(@Size(max = MAX_QUOTE_LENGTH) String quotedText,
                                    @Size(max = MAX_BODY_LENGTH) String body,
                                    @Size(max = 200) String clientId) {
    }

    public record MessageRequest(@Size(max = MAX_BODY_LENGTH) String body,
                                 @Size(max = 200) String clientId) {
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
    @RequiresDocumentAccess(Capability.READ)
    public ResponseEntity<List<CommentService.Thread>> threads(
        @PathVariable UUID documentId,
        @RequestParam(value = "resolved", defaultValue = "false") boolean includeResolved) {

        // The database FIRST, the replica only if there is something to anchor. The other order —
        // which this had — took the document lock unconditionally, and `registry.get` is
        // get-or-load, so opening the comments panel on a document with no comments still cost a
        // load plus a lock acquisition contending with the 10 ms broadcast pass. The panel reloads
        // on every settled keystroke, so that was one lock per typing pause, forever, to compute a
        // set that was then intersected with nothing.
        //
        // SuggestionService.openFor already does it this way, with a comment about "the same lock
        // every keystroke also needs". Same problem, same answer.
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

    /**
     * The identifiers of threads whose anchor still covers something.
     *
     * <p>Read from the replica's current state: a mark of type {@code comment} carries the thread
     * identifier as its value. A thread whose anchors were deleted along with the text simply does
     * not appear here.
     *
     * <h2>Why not {@code formatting()}</h2>
     * This used to read the resolved spans and collect their {@code comment} value. That is the
     * RENDER's view, and the render resolves exactly one value per type per segment — so when two
     * threads overlapped, the newer one won every shared segment and the older one vanished from
     * this set entirely. The panel then showed a thread whose text is fully present with the
     * detached treatment, which the README reserves for a thread whose text was <b>deleted</b>.
     *
     * <p>Two people commenting on the same sentence is the ordinary case, so this was not an edge.
     * {@code markPlacements} asks the mark layer directly and is blind to who wins the render.
     */
    private Set<String> anchoredThreadIds(UUID documentId) {
        return registry.get(documentId).withLock(
            replica -> Set.copyOf(replica.markPlacements(MarkTypes.COMMENT).keySet()));
    }

    // Commenting needs COMMENTER, not EDITOR — that distinction is the whole point of the role
    // model, and it is now stated where the endpoint is rather than in an if further down.
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

        // Read back by identifier. This used to call threadsOf(documentId, true) and filter for
        // the one thread it had just created — so posting a comment cost a full read of every
        // thread and every message in the document, to return a row the caller already half knows.
        // The client reads only `id` off this response (CommentsPanel: onAnchor(created.id)).
        //
        // `anchored` is true here because the anchor is applied by the CLIENT, as a mark, after
        // this call returns — there is nothing to look up yet, and threadsOf reported the same
        // value for the same reason.
        return comments.findThread(threadId)
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
