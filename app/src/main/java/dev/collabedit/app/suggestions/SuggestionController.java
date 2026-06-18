package dev.collabedit.app.suggestions;

import dev.collabedit.app.auth.Capability;
import dev.collabedit.app.auth.RequiresDocumentAccess;
import dev.collabedit.app.auth.TokenAuthFilter;
import dev.collabedit.app.auth.TokenService;
import dev.collabedit.crdt.Id;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
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
@CrossOrigin(originPatterns = {"http://localhost:*", "http://127.0.0.1:*"})
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
     * @param after the character the proposed text goes after; {@code null} means the very start
     * @param from  first character of the range proposed for deletion
     * @param to    last character of that range, inclusive
     */
    public record ProposeRequest(String kind, String text, WireId after, WireId from, WireId to) {
    }

    public record Proposed(String id) {
    }

    @PostMapping
    @RequiresDocumentAccess(value = Capability.COMMENT, param = "documentId")
    public ResponseEntity<Proposed> propose(@PathVariable("documentId") UUID documentId,
                                            @RequestBody ProposeRequest body,
                                            HttpServletRequest request) {
        String caller = TokenAuthFilter.principalOf(request)
            .map(TokenService.Principal::userId)
            .orElseThrow();

        var created = switch (body.kind() == null ? "" : body.kind()) {
            case "insert" -> suggestions.proposeInsertion(documentId, caller,
                body.after() == null ? null : body.after().toId(), body.text());
            case "delete" -> body.from() == null || body.to() == null
                ? java.util.Optional.<UUID>empty()
                : suggestions.proposeDeletion(documentId, caller, body.from().toId(),
                    body.to().toId());
            default -> java.util.Optional.<UUID>empty();
        };

        // 409, not 404: the document exists and the caller may see it. What failed is that the
        // text this was going to attach to is gone — somebody deleted it while the proposal was
        // being written. Saying "not found" would send the reader looking for the wrong thing.
        return created
            .map(id -> ResponseEntity.ok(new Proposed(id.toString())))
            .orElseGet(() -> ResponseEntity.status(409).build());
    }

    @GetMapping
    @RequiresDocumentAccess(value = Capability.READ, param = "documentId")
    public List<SuggestionService.Suggestion> open(@PathVariable("documentId") UUID documentId) {
        return suggestions.openFor(documentId);
    }

    // Accepting is an edit — the largest one a suggestion can be. WRITE_TEXT, therefore, and the
    // same reasoning as history restore: what matters is the effect, not where the content came
    // from.
    @PostMapping("/{suggestionId}/accept")
    @RequiresDocumentAccess(value = Capability.WRITE_TEXT, param = "documentId")
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
    @RequiresDocumentAccess(value = Capability.WRITE_TEXT, param = "documentId")
    public ResponseEntity<Void> reject(@PathVariable("documentId") UUID documentId,
                                       @PathVariable("suggestionId") UUID suggestionId,
                                       HttpServletRequest request) {
        return resolve(documentId, suggestionId, request, false);
    }

    private ResponseEntity<Void> resolve(UUID documentId, UUID suggestionId,
                                         HttpServletRequest request, boolean accept) {
        String caller = TokenAuthFilter.principalOf(request)
            .map(TokenService.Principal::userId)
            .orElseThrow();
        return suggestions.resolve(documentId, suggestionId, caller, accept)
            ? ResponseEntity.noContent().build()
            : ResponseEntity.notFound().build();
    }
}
