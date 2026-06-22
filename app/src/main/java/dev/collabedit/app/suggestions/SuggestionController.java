package dev.collabedit.app.suggestions;

import dev.collabedit.app.auth.Capability;
import dev.collabedit.app.auth.RequiresDocumentAccess;
import dev.collabedit.app.auth.TokenAuthFilter;
import dev.collabedit.crdt.Id;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Proposing changes, and deciding on them (CP23).
 *
 * <h2>The authorization split is the feature</h2>
 * Proposing needs {@link Capability#COMMENT}; deciding needs {@link Capability#WRITE_TEXT}. That
 * one line is what makes the {@code commenter} role mean something other than "may talk about the
 * document": a commenter can now contribute <b>to</b> it without being able to change it, which is
 * what the role was for since CP12 and had no way to express.
 *
 * <p>Note what is <b>not</b> here: no endpoint lets a commenter send an operation. The operations
 * are generated on the server replica ({@link SuggestionService}), so the rule stays where CP16
 * put it — declared at the endpoint, checked by the guard, with nothing reading an operation's
 * payload to decide whether the operation is allowed.
 */
@RestController
@RequestMapping("/api/documents/{documentId}/suggestions")
public class SuggestionController {

    private final SuggestionService suggestions;

    public SuggestionController(SuggestionService suggestions) {
        this.suggestions = suggestions;
    }

    /** A character identifier as it travels on the wire — the same shape as everywhere else. */
    public record WireId(int replicaId, long lamport) {
        Id toId() {
            return new Id(replicaId, lamport);
        }
    }

    /**
     * @param text the proposed insertion. <b>Bounded</b>, and that bound is the point of the
     * annotation rather than tidiness: {@code SuggestionService.proposeInsertion}
     * turns this straight into {@code document.insert(position, text)} — ONE
     * {@code Op.Insert} of whatever size arrived. Nothing can split an operation
     * afterwards, because its identity is what the merge algorithm is built on, so an
     * oversized one is a permanent undeliverable entry in the log: it exceeds the
     * 64 KiB frame limit from the transport decision and every client silently fails to receive it.
     * {@code DocumentStompController.chunkByBytes} names this as its remaining limit
     * and puts the mitigation on the client ("the paste path in bridge.ts", capped at
     * 8192 characters) — which is not a mitigation at all for an endpoint the client
     * is not the only caller of. The same 8192, so the server and the browser produce
     * operations of one shape
     * @param after the character the proposed text goes after; {@code null} means the very start
     * @param from  first character of the range proposed for deletion
     * @param to    last character of that range, inclusive
     */
    public record ProposeRequest(@Size(max = 32) String kind,
                                 @Size(max = MAX_SUGGESTED_CHARACTERS) String text,
                                 WireId after, WireId from, WireId to) {
    }

    /** See {@link ProposeRequest#text}. The client's paste cap, deliberately the same number. */
    private static final int MAX_SUGGESTED_CHARACTERS = 8_192;

    public record Proposed(String id) {
    }

    @PostMapping
    @RequiresDocumentAccess(Capability.COMMENT)
    public ResponseEntity<Proposed> propose(@PathVariable("documentId") UUID documentId,
                                            @Valid @RequestBody ProposeRequest body,
                                            HttpServletRequest request) {
        String caller = TokenAuthFilter.requiredCallerId(request);

        // A MALFORMED request is a 400, and a well-formed one that cannot be anchored is a 409.
        //
        // Every one of these used to fall through to `Optional.empty` and come back as 409, and
        // the client renders 409 as "that text is no longer there — somebody changed it while you
        // were writing". So an unknown `kind`, or a deletion missing its range, was reported to the
        // user as somebody else's concurrent edit. AccessController gets this right one package
        // over: an unparseable role is a 400 there, with a comment saying why.
        String kind = body.kind == null ? "" : body.kind;
        if (!"insert".equals(kind) && !"delete".equals(kind)) {
            return ResponseEntity.badRequest().build();
        }
        if ("insert".equals(kind) && (body.text == null || body.text.isEmpty())) {
            return ResponseEntity.badRequest().build();
        }
        if ("delete".equals(kind) && (body.from == null || body.to == null)) {
            return ResponseEntity.badRequest().build();
        }

        var created = "insert".equals(kind)
            ? suggestions.proposeInsertion(documentId, caller,
                body.after == null ? null : body.after.toId(), body.text)
                : suggestions.proposeDeletion(documentId, caller, body.from().toId(),
                body.to.toId());

        // 409, not 404: the document exists and the caller may see it. What failed is that the
        // text this was going to attach to is gone — somebody deleted it while the proposal was
        // being written. Saying "not found" would send the reader looking for the wrong thing.
        return created
            .map(id -> ResponseEntity.ok(new Proposed(id.toString())))
            .orElseGet(() -> ResponseEntity.status(409).build());
    }

    @GetMapping
    @RequiresDocumentAccess(Capability.READ)
    public ResponseEntity<List<SuggestionService.Suggestion>> open(
            @PathVariable("documentId") UUID documentId) {
        return ResponseEntity.ok(suggestions.openFor(documentId));
    }

    // Accepting is an edit — the largest one a suggestion can be. WRITE_TEXT, therefore, and the
    // same reasoning as history restore: what matters is the effect, not where the content came
    // from.
    @PostMapping("/{suggestionId}/accept")
    @RequiresDocumentAccess(Capability.WRITE_TEXT)
    public ResponseEntity<Void> accept(@PathVariable("documentId") UUID documentId,
                                       @PathVariable("suggestionId") UUID suggestionId,
                                       HttpServletRequest request) {
        return resolve(documentId, suggestionId, request, true);
    }

    /**
     * Rejecting also needs WRITE_TEXT — including rejecting your own suggestion.
     *
     * <p>Tempting to allow the author to withdraw their own, and wrong in this direction:
     * rejecting a proposed insertion <b>deletes text from the document</b>. The text is the
     * author's own, but the operation is a deletion, and "may delete on the condition that they
     * wrote it" is a rule that would have to be re-derived at every future call site.
     */
    @PostMapping("/{suggestionId}/reject")
    @RequiresDocumentAccess(Capability.WRITE_TEXT)
    public ResponseEntity<Void> reject(@PathVariable("documentId") UUID documentId,
                                       @PathVariable("suggestionId") UUID suggestionId,
                                       HttpServletRequest request) {
        return resolve(documentId, suggestionId, request, false);
    }

    /**
     * 404 for a suggestion that is not there, 409 for one that has already been decided.
     *
     * <p>Both used to be 404, because {@code SuggestionService.resolve} returned one boolean for
     * two situations — and the two mean opposite things to whoever is looking at the screen. "There
     * is no such proposal" says the list is stale or the link is wrong; "somebody already accepted
     * this" says the list is stale in a specific way, and the right response is to re-read it and
     * see what happened. {@code CommentController} answers 409 for the same situation one package
     * over, which is the convention this project now applies everywhere.
     */
    private ResponseEntity<Void> resolve(UUID documentId, UUID suggestionId,
                                         HttpServletRequest request, boolean accept) {
        String caller = TokenAuthFilter.requiredCallerId(request);
        return switch (suggestions.resolve(documentId, suggestionId, caller, accept)) {
            case RESOLVED -> ResponseEntity.noContent().build();
            case ALREADY_RESOLVED -> ResponseEntity.status(HttpStatus.CONFLICT).build();
            case UNKNOWN -> ResponseEntity.notFound().build();
        };
    }
}
