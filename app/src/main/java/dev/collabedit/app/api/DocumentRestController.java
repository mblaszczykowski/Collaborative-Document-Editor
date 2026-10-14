package dev.collabedit.app.api;

import dev.collabedit.app.auth.Capability;
import dev.collabedit.app.auth.DocumentAccessGuard;
import dev.collabedit.app.auth.PermissionService;
import dev.collabedit.app.auth.RequiresAuthentication;
import dev.collabedit.app.auth.RequiresDocumentAccess;
import dev.collabedit.app.auth.Role;
import dev.collabedit.app.auth.TokenAuthFilter;
import dev.collabedit.app.domain.DocumentService;
import dev.collabedit.app.persist.DocumentCatalog;
import dev.collabedit.crdt.Op;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import dev.collabedit.crdt.MarkTypes;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class DocumentRestController {
    private final DocumentService documents;
    private final DocumentCatalog catalog;
    private final PermissionService permissions;

    public DocumentRestController(DocumentService documents, DocumentCatalog catalog,
                                  PermissionService permissions) {
        this.documents = documents;
        this.catalog = catalog;
        this.permissions = permissions;
    }

    private static final int MAX_TITLE_LENGTH = 256;

    public record DocumentSummaryWithRole(UUID id, String title, UUID workspaceId, String preview,
                                          Instant updatedAt, String role) {
        static DocumentSummaryWithRole of(DocumentCatalog.DocumentSummary summary, Role role) {
            return new DocumentSummaryWithRole(summary.id(), summary.title(), summary.workspaceId(),
                summary.preview(), summary.updatedAt(), role.wire());
        }
    }

    public record DocumentState(UUID id, String title, String text, long headServerSeq,
                                String role) {
    }

    @GetMapping("/documents")
    @RequiresAuthentication("Returns only what the caller may read; the filter is per row, "
        + "below, because the answer is a list rather than one document.")
    public ResponseEntity<List<DocumentSummaryWithRole>> list(
        @RequestParam(value = "q", required = false)
        @Size(max = MAX_SEARCH_QUERY) String query,
        @RequestParam(value = "workspace", required = false) UUID workspaceId,
        HttpServletRequest request) {
        String caller = TokenAuthFilter.requiredCallerId(request);
        List<DocumentCatalog.DocumentSummary> found = query != null && !query.isBlank()
            ? catalog.search(caller, workspaceId, query.trim(), LIST_LIMIT)
: catalog.list(caller, workspaceId, LIST_LIMIT);

        return ResponseEntity.ok(
            found.stream().flatMap(doc -> permissions.roleOf(caller, doc.id()).stream()
                .map(role -> DocumentSummaryWithRole.of(doc, role))).toList());
    }

    private static final int LIST_LIMIT = 200;

    @GetMapping("/documents/trash")
    @RequiresAuthentication("Same shape as the list above: filtered per row, so no single "
        + "document is being authorized here.")
    public ResponseEntity<List<DocumentSummaryWithRole>> trash(HttpServletRequest request) {
        String caller = TokenAuthFilter.requiredCallerId(request);
        return ResponseEntity.ok(catalog.listDeleted(caller, LIST_LIMIT).stream()
            .flatMap(doc -> permissions.roleOfDeleted(caller, doc.id()).stream()
                .map(role -> DocumentSummaryWithRole.of(doc, role)))
            .toList());
    }

    public record CreateDocumentRequest(@Size(max = MAX_TITLE_LENGTH) String title,
                                        UUID workspaceId) {
    }

    @PostMapping("/documents")
    @RequiresAuthentication("Creates a document, so there is none to check yet - but there "
        + "must be a user to own it. See createOwned for what used to happen without one.")
    public ResponseEntity<DocumentCatalog.DocumentSummary> create(
            @Valid @RequestBody CreateDocumentRequest body, HttpServletRequest request) {
        String title = body.title() == null || body.title().isBlank()
            ? "Untitled document" : body.title().trim();
        return ResponseEntity.ok(
            createOwned(title, body.workspaceId, TokenAuthFilter.requiredCallerId(request)));
    }

    private DocumentCatalog.DocumentSummary createOwned(String title, UUID workspaceId,
                                                        String ownerId) {
        if (workspaceId != null && !catalog.ownsWorkspace(workspaceId, ownerId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "workspace is not owned by caller");
        }
        UUID id = UUID.randomUUID();
        catalog.create(id, title, workspaceId);
        permissions.setOwner(id, ownerId);
        return catalog.find(id).orElseThrow();
    }

    public record RecoveredSpan(int from, int to,
                                @Size(max = 16) Map<@Size(max = 32) String,
                                    @Size(max = 2048) String> marks) { }

    public record RecoveredRequest(@Size(max = MAX_TITLE_LENGTH) String title,
                                   @Size(max = MAX_RECOVERED_CHARACTERS) String text,
                                   @Size(max = 10_000) List<@Valid RecoveredSpan> marks) { }

    private static final Set<String> RECOVERABLE_MARKS = Set.of(MarkTypes.BOLD, MarkTypes.ITALIC,
        MarkTypes.UNDERLINE, MarkTypes.STRIKETHROUGH, MarkTypes.CODE, MarkTypes.LINK,
        MarkTypes.HEADING, MarkTypes.QUOTE, MarkTypes.LIST);

    private static final int MAX_SEARCH_QUERY = 256;

    private static final int MAX_RECOVERED_CHARACTERS = 1_000_000;

    static final int MAX_INSERT_CHARACTERS = DocumentService.MAX_INSERT_CHARACTERS;

    @PostMapping("/documents/recovered")
    @RequiresAuthentication("Creates a document from work that was never synchronised, so "
        + "there is nothing to authorize against - but an anonymous caller here produced an "
        + "ownerless, link-visible draft, which is the exact opposite of the intent.")
    public ResponseEntity<DocumentCatalog.DocumentSummary> createRecovered(
            @Valid @RequestBody RecoveredRequest request, HttpServletRequest httpRequest) {
        String title = request.title == null || request.title.isBlank()
            ? "Recovered draft" : request.title;
        String text = request.text == null ? "" : request.text;
        List<RecoveredSpan> marks = request.marks == null ? List.of() : request.marks;
        for (RecoveredSpan span : marks) {
            if (span == null || span.from() < 0 || span.to() <= span.from()
                || span.to() > text.length() || span.marks() == null) {
                throw new IllegalArgumentException("recovered formatting range is invalid");
            }
        }
        String caller = TokenAuthFilter.requiredCallerId(httpRequest);
        DocumentCatalog.DocumentSummary created = createOwned(title, null, caller);

        if (!text.isEmpty()) {
            documents.generateAndSubmit(created.id(), model -> {
                List<Op> inserts = new ArrayList<>();
                int at = 0;
                for (String piece : DocumentService.splitForInsert(text)) {
                    inserts.add(model.insert(at, piece));
                    at += piece.length();
                }
                for (RecoveredSpan span : marks) {
                    for (Map.Entry<String, String> mark : span.marks().entrySet()) {
                        if (RECOVERABLE_MARKS.contains(mark.getKey())) {
                            inserts.add(model.addMark(span.from(), span.to(), mark.getKey(),
                                mark.getValue()));
                        }
                    }
                }
                return inserts;
            }, caller);
        }
        return ResponseEntity.ok(catalog.find(created.id()).orElseThrow());
    }

    public record RenameRequest(@Size(max = MAX_TITLE_LENGTH) String title) {
    }

    @PatchMapping("/documents/{documentId}")
    @RequiresDocumentAccess(Capability.WRITE_TEXT)
    public ResponseEntity<Void> rename(@PathVariable UUID documentId,
                                       @Valid @RequestBody RenameRequest body) {
        if (body.title() == null || body.title().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        DocumentService.DurableState state = documents.durableState(documentId);
        String text = dev.collabedit.crdt.RgaDocument.restore(0,
            new dev.collabedit.crdt.TreapBlockList(), state.snapshot()).text();
        boolean renamed = catalog.rename(documentId, body.title.trim(), text, state.throughServerSeq());
        documents.invalidateSearchIndex(documentId);
        return renamed ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    @DeleteMapping("/documents/{documentId}")
    @RequiresDocumentAccess(Capability.WRITE_TEXT)
    public ResponseEntity<Void> delete(@PathVariable UUID documentId) {
        if (!catalog.softDelete(documentId)) {
            return ResponseEntity.notFound().build();
        }
        permissions.invalidate(documentId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/documents/{documentId}/restore")
    @RequiresDocumentAccess(value = Capability.WRITE_TEXT, includeDeleted = true)
    public ResponseEntity<Void> restore(@PathVariable UUID documentId) {
        if (!catalog.restore(documentId)) {
            return ResponseEntity.notFound().build();
        }
        permissions.invalidate(documentId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/documents/{documentId}")
    @RequiresDocumentAccess(Capability.READ)
    public ResponseEntity<DocumentState> open(@PathVariable UUID documentId,
                                              HttpServletRequest request) {
        Role role = DocumentAccessGuard.resolvedRole(request).orElseThrow();
        return catalog.find(documentId)
            .map(summary -> {
                DocumentService.DurableState state = documents.durableState(documentId);
                String text = dev.collabedit.crdt.RgaDocument.restore(0,
                    new dev.collabedit.crdt.TreapBlockList(), state.snapshot()).text();
                return ResponseEntity.ok(new DocumentState(documentId, summary.title(), text,
                    state.throughServerSeq(), role.wire()));
            })
                    .orElseGet(() -> ResponseEntity.notFound().build());
    }

    public record CreateWorkspaceRequest(@Size(max = MAX_TITLE_LENGTH) String name) {
    }

    @GetMapping("/workspaces")
    @RequiresAuthentication("Scoped to the caller's own workspaces; identity comes from the "
        + "token, never from a request parameter.")
    public ResponseEntity<List<DocumentCatalog.Workspace>> workspaces(HttpServletRequest request) {
        return ResponseEntity.ok(
            catalog.workspacesOf(TokenAuthFilter.requiredCallerId(request)));
    }

    @PostMapping("/workspaces")
    @RequiresAuthentication("Creates a workspace owned by the caller; nothing exists yet to "
        + "authorize against.")
    public ResponseEntity<DocumentCatalog.Workspace> createWorkspace(
            @Valid @RequestBody CreateWorkspaceRequest body, HttpServletRequest request) {
        String caller = TokenAuthFilter.requiredCallerId(request);
        UUID id = UUID.randomUUID();
        String name = body.name() == null || body.name().isBlank()
            ? "Untitled" : body.name().trim();
        catalog.createWorkspace(id, name, caller);
        return catalog.workspacesOf(caller).stream()
            .filter(w -> w.id().equals(id))
            .findFirst()
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.internalServerError().build());
    }
}
