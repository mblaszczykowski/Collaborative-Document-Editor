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

@RestController
@RequestMapping("/api/documents/{documentId}/suggestions")
public class SuggestionController {
    private final SuggestionService suggestions;

    public SuggestionController(SuggestionService suggestions) {
        this.suggestions = suggestions;
    }

    public record WireId(int replicaId, long lamport) {
        Id toId() {
            return new Id(replicaId, lamport);
        }
    }

    public record ProposeRequest(@Size(max = 32) String kind,
                                 @Size(max = MAX_SUGGESTED_CHARACTERS) String text,
                                 WireId after, WireId from, WireId to) {
    }

    private static final int MAX_SUGGESTED_CHARACTERS = 8_192;

    public record Proposed(String id) {
    }

    @PostMapping
    @RequiresDocumentAccess(Capability.COMMENT)
    public ResponseEntity<Proposed> propose(@PathVariable("documentId") UUID documentId,
                                            @Valid @RequestBody ProposeRequest body,
                                            HttpServletRequest request) {
        String caller = TokenAuthFilter.requiredCallerId(request);

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

    @PostMapping("/{suggestionId}/accept")
    @RequiresDocumentAccess(Capability.WRITE_TEXT)
    public ResponseEntity<Void> accept(@PathVariable("documentId") UUID documentId,
                                       @PathVariable("suggestionId") UUID suggestionId,
                                       HttpServletRequest request) {
        return resolve(documentId, suggestionId, request, true);
    }

    @PostMapping("/{suggestionId}/reject")
    @RequiresDocumentAccess(Capability.WRITE_TEXT)
    public ResponseEntity<Void> reject(@PathVariable("documentId") UUID documentId,
                                       @PathVariable("suggestionId") UUID suggestionId,
                                       HttpServletRequest request) {
        return resolve(documentId, suggestionId, request, false);
    }

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
