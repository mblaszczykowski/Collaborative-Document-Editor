package dev.collabedit.app.api;

import dev.collabedit.app.auth.TokenService;
import dev.collabedit.app.domain.DocumentRegistry;
import dev.collabedit.app.domain.DocumentService;
import dev.collabedit.app.auth.Capability;
import dev.collabedit.app.auth.DocumentAccessGuard;
import dev.collabedit.app.auth.PermissionService;
import dev.collabedit.app.auth.PublicEndpoint;
import dev.collabedit.app.auth.RequiresAuthentication;
import dev.collabedit.app.auth.RequiresDocumentAccess;
import dev.collabedit.app.auth.Role;
import dev.collabedit.app.auth.TokenAuthFilter;
import dev.collabedit.app.persist.DocumentCatalog;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST for the things that need not be real time: the document list, creating a new document, the
 * initial state read on open.
 *
 * <p>The split is deliberate. The WebSocket channel carries only <b>incremental changes</b>;
 * fetching the initial state over REST lets us use the ordinary HTTP machinery (compression,
 * caching, handling large responses) and keeps frames of several hundred kilobytes off a channel
 * optimised for small messages (ADR-0002, a 64 KiB limit per message).
 */
@RestController
@RequestMapping("/api")
@CrossOrigin(originPatterns = {"http://localhost:*", "http://127.0.0.1:*"})
public class DocumentRestController {

    private final JdbcTemplate jdbc;
    private final DocumentService documents;
    private final DocumentRegistry registry;
    private final TokenService tokens;
    private final DocumentCatalog catalog;
    private final PermissionService permissions;

    public DocumentRestController(JdbcTemplate jdbc, DocumentService documents,
                                  DocumentRegistry registry, TokenService tokens,
                                  DocumentCatalog catalog, PermissionService permissions) {
        this.jdbc = jdbc;
        this.documents = documents;
        this.registry = registry;
        this.tokens = tokens;
        this.catalog = catalog;
        this.permissions = permissions;
    }

    /**
     * A document's initial state together with the <b>caller's role</b>.
     *
     * <p>The role is here for the same reason a rate-limit refusal is loud (see
     * {@code DocumentAccessInterceptor}): enforcing on the server without telling the client turns
     * a refusal into <b>silent divergence</b>. A reader who does not know they are a reader types,
     * sees their own text, and the server rejects it — and their local replica never agrees with
     * the rest again, without a single message.
     *
     * <p>This is not moving authorization to the client. The server checks exactly as before; the
     * client is given this information solely so it does not let the user make a gesture that will
     * be refused anyway.
     */
    public record DocumentState(UUID id, String title, String text, long headServerSeq,
                                String role) {
    }

    public record LoginRequest(String displayName) {
    }

    public record LoginResponse(String token, String userId, String displayName, String color) {
    }

    /**
     * The upper bound on the length of a name visible to others.
     *
     * <p>The name goes into the token, into the database and — most importantly — into <b>every
     * presence broadcast</b>, that is, a message sent many times a second to every participant in
     * the document. Without a limit, one user with a megabyte-long name loads everyone else's
     * connection. 64 characters accommodate any real name.
     */
    private static final int MAX_DISPLAY_NAME_LENGTH = 64;

    /**
     * Login — <b>with no password</b>. You give a name, you get an identity.
     *
     * <p>A deliberate simplification described in the README: the project's scope is distributed
     * data consistency, not identity management. A full account mechanism would take time away from
     * the core problem while demonstrating nothing this project sets out to prove.
     */
    @PostMapping("/login")
    @PublicEndpoint("Issues identity. There is nothing to authorize against yet — this is the "
        + "endpoint that produces the token every other endpoint checks.")
    public LoginResponse login(@RequestBody LoginRequest request) {
        String displayName = request.displayName() == null || request.displayName().isBlank()
            ? "Guest" : request.displayName().trim();
        if (displayName.length() > MAX_DISPLAY_NAME_LENGTH) {
            displayName = displayName.substring(0, MAX_DISPLAY_NAME_LENGTH);
        }
        String userId = UUID.randomUUID().toString();
        String color = PALETTE[Math.floorMod(displayName.hashCode(), PALETTE.length)];

        jdbc.update("INSERT INTO users (id, display_name, color) VALUES (?, ?, ?) "
            + "ON CONFLICT (id) DO NOTHING", userId, displayName, color);

        return new LoginResponse(
            tokens.issue(new TokenService.Principal(userId, displayName, color)),
            userId, displayName, color);
    }


    // ------------------------------------------------------------------
    // Authorization (CP12)
    // ------------------------------------------------------------------

    /**
     * The logged-in user's identifier, or {@code null}.
     *
     * <p>{@link TokenAuthFilter} only ESTABLISHES the identity; the decision to refuse belongs to
     * the endpoint, because only it knows whether a given path needs access to a specific document
     * or is public.
     */
    private String callerId(HttpServletRequest request) {
        return TokenAuthFilter.principalOf(request).map(p -> p.userId()).orElse(null);
    }

    /**
     * 404 rather than 403 when access is denied — <b>deliberately</b>.
     *
     * <p>The answer "this document exists, but you may not see it" is itself a leak: it lets
     * someone probe which identifiers are real. From the point of view of someone without
     * permission the document should simply not exist — the same as on the WebSocket path, where
     * the subscription simply does not work.
     */
    private static final org.springframework.http.HttpStatus DENIED =
        org.springframework.http.HttpStatus.NOT_FOUND;

    /** The palette from the mockup (docs/design/mockup-inkline.html) — author colours in the text. */
    private static final String[] PALETTE = {"#C1592E", "#4C5FD5", "#2E8B6B", "#8A5CB8", "#B8862E"};

    /**
     * The document list or a search result — one entry point, because from the interface's point of
     * view they are the same thing: "show me documents, optionally narrowed".
     *
     * <p>The response does NOT contain document content, only the preview materialised at snapshot
     * time. Without that, listing a hundred entries would load a hundred CRDT replicas into memory
     * (CP11) — making the document list the most expensive operation in the whole system.
     */
    @GetMapping("/documents")
    @RequiresAuthentication("Returns only what the caller may read; the filter is per row, "
        + "below, because the answer is a list rather than one document.")
    public List<DocumentCatalog.DocumentSummary> list(
        @RequestParam(value = "q", required = false) String query,
        @RequestParam(value = "workspace", required = false) UUID workspaceId,
        HttpServletRequest request) {

        List<DocumentCatalog.DocumentSummary> found = query != null && !query.isBlank()
            ? catalog.search(query.trim(), LIST_LIMIT)
            : catalog.list(workspaceId, LIST_LIMIT);

        // Permission filtering in the application, not in the query.
        //
        // A query with the access rule built in would be faster, but it would split that rule
        // across two places: the SQL here and PermissionService elsewhere. Two copies of an access
        // rule drift apart, and then one of them lets too much through. At a limit of 200 entries
        // the filtering cost is negligible, and the rule stays in one place.
        String caller = callerId(request);
        return found.stream().filter(doc -> permissions.canRead(caller, doc.id())).toList();
    }

    /**
     * The upper bound on the list's length.
     *
     * <p>There is no pagination here, and that is a deliberate simplification: the interface shows
     * a list of recently changed documents, not an archive. The limit exists so that the query has
     * a bounded cost regardless of how many documents are in the database — without it an account
     * with tens of thousands of documents would send all of them on every open.
     */
    private static final int LIST_LIMIT = 200;

    @GetMapping("/documents/trash")
    @RequiresAuthentication("Same shape as the list above: filtered per row, so no single "
        + "document is being authorized here.")
    public List<DocumentCatalog.DocumentSummary> trash(HttpServletRequest request) {
        String caller = callerId(request);
        // The bin shows only documents the caller could open if they were not deleted. Without
        // that the bin would be a way around permissions — a list of other people's titles.
        return catalog.listDeleted(LIST_LIMIT).stream()
            .filter(doc -> permissions.roleOfDeleted(caller, doc.id()).isPresent())
            .toList();
    }

    public record CreateDocumentRequest(String title, UUID workspaceId) {
    }

    @PostMapping("/documents")
    @RequiresAuthentication("Creates a document, so there is none to check yet — but there "
        + "must be a user to own it. See below for what used to happen without one.")
    public DocumentCatalog.DocumentSummary create(@RequestBody CreateDocumentRequest body,
                                                  HttpServletRequest request) {
        UUID id = UUID.randomUUID();
        String title = body.title() == null || body.title().isBlank()
            ? "Untitled document" : body.title().trim();
        catalog.create(id, title, body.workspaceId());

        // The creator becomes the owner. Without that nobody could change the document's
        // visibility or grant access to it — a document with no owner cannot be secured
        // by anyone, ever.
        //
        // This used to sit inside `if (caller != null)`, which quietly produced exactly that
        // unsecurable document whenever the token was missing or expired. The annotation above
        // is what makes the unconditional call correct: the guard has already refused the
        // request if there is no caller.
        permissions.setOwner(id, callerId(request));

        // PRIVATE, not the schema default (CP21).
        //
        // The column defaults to `link`, and `link` resolves to EDITOR for every logged-in user
        // (PermissionService.roleFrom). So until this line, every document created through the
        // interface was editable by anybody who typed a name into the login box — which made the
        // four roles the repository documents into two the product could produce: owner, and
        // everyone.
        //
        // On a laptop that reads as convenience. On a public instance it is the whole access
        // story being false in the one place a visitor can check it. Sharing is now a gesture —
        // an invite link or a visibility switch — rather than the absence of one.
        permissions.setVisibility(id, "private");
        return catalog.find(id).orElseThrow();
    }

    public record RenameRequest(String title) {
    }

    @PatchMapping("/documents/{id}")
    @RequiresDocumentAccess(Capability.WRITE_TEXT)
    public ResponseEntity<Void> rename(@PathVariable UUID id, @RequestBody RenameRequest body) {
        if (body.title() == null || body.title().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        return catalog.rename(id, body.title().trim())
            ? ResponseEntity.noContent().build()
            : ResponseEntity.notFound().build();
    }

    /**
     * Hides a document. <b>It deletes nothing.</b>
     *
     * <p>The {@code operations} table has {@code ON DELETE CASCADE} on {@code doc_id}, so a hard
     * delete would erase the operation log — the source of truth from ADR-0003, which is
     * append-only. A user clicking "delete" wants the document gone from their list, not gone from
     * existence.
     */
    // Deleting needs WRITE, not ownership: a document a team shares should be hideable by
    // anyone who edits it. The operation being reversible (trash) is what makes that safe.
    @DeleteMapping("/documents/{id}")
    @RequiresDocumentAccess(Capability.WRITE_TEXT)
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        return catalog.softDelete(id)
            ? ResponseEntity.noContent().build()
            : ResponseEntity.notFound().build();
    }

    // includeDeleted, because restoring a document from the trash is by definition an
    // operation on a deleted one — and must not skip the permission check while doing so,
    // or the trash becomes a way to reach other people's documents.
    @PostMapping("/documents/{id}/restore")
    @RequiresDocumentAccess(value = Capability.WRITE_TEXT, includeDeleted = true)
    public ResponseEntity<Void> restore(@PathVariable UUID id) {
        return catalog.restore(id)
            ? ResponseEntity.noContent().build()
            : ResponseEntity.notFound().build();
    }

    // ------------------------------------------------------------------
    // Workspaces
    // ------------------------------------------------------------------

    public record CreateWorkspaceRequest(String name) {
    }

    /**
     * The <b>caller's</b> workspaces — not the ones they ask for in a parameter.
     *
     * <p>Originally the owner arrived as {@code ?owner=...} on the request, which meant any user
     * could list somebody else's workspaces given their identifier — and another person's
     * identifier is visible in the presence frames of every shared document. The same class of bug
     * as the IDOR closed in CP12, on a different entity: <b>identity taken from the request instead
     * of from the token</b>.
     */
    @GetMapping("/workspaces")
    @RequiresAuthentication("Scoped to the caller's own workspaces; identity comes from the "
        + "token, never from a request parameter.")
    public ResponseEntity<List<DocumentCatalog.Workspace>> workspaces(HttpServletRequest request) {
        return ResponseEntity.ok(catalog.workspacesOf(callerId(request)));
    }

    @PostMapping("/workspaces")
    @RequiresAuthentication("Creates a workspace owned by the caller; nothing exists yet to "
        + "authorize against.")
    public ResponseEntity<DocumentCatalog.Workspace> createWorkspace(
            @RequestBody CreateWorkspaceRequest body, HttpServletRequest request) {
        // Owner from the token as well: without that it was possible to create a workspace
        // belonging to somebody else, i.e. to inject an entry onto their own list.
        String caller = callerId(request);
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

    public record RecoveredRequest(String title, String text) {
    }

    /**
     * Creates a document from recovered content.
     *
     * <p>The rescue path from ADR-0005: the client was offline for longer than the GC window, its
     * operations point at tombstones that no longer exist, and <b>there is no honest way to merge
     * them</b>. Rather than gluing the text into a random place or losing it, we save the local
     * version as a separate document and tell the user so plainly.
     *
     * <p>The content is inserted as ordinary operations of the server replica — so the new document
     * is nothing special and has a full history from the first moment, like any other.
     *
     * <p><b>The owner and the visibility are set differently here than on ordinary creation</b>,
     * and that is deliberate. An ordinary document was created as {@code link} by default — because
     * it is created in order to show it to someone. A recovered draft is created from <b>one
     * person's unsynchronised work</b> that nobody else has ever seen; handing it to every logged-in
     * user would be disclosing the content at exactly the moment the user is worried about losing
     * it. Before CP12 this did not matter (there was no visibility); after CP12 it did — and it was
     * an oversight.
     */
    @PostMapping("/documents/recovered")
    @RequiresAuthentication("Creates a document from work that was never synchronised, so "
        + "there is nothing to authorize against — but an anonymous caller here produced an "
        + "ownerless, link-visible draft, which is the exact opposite of the intent.")
    public DocumentCatalog.DocumentSummary createRecovered(@RequestBody RecoveredRequest request,
                                                           HttpServletRequest httpRequest) {
        UUID id = UUID.randomUUID();
        String title = request.title() == null || request.title().isBlank()
            ? "Recovered draft" : request.title();
        catalog.create(id, title, null);

        // Unconditional, and that is the fix. Both calls used to sit inside
        // `if (caller != null)`, so a missing or EXPIRED token skipped them both: no owner,
        // and `visibility` falling back to the schema default of 'link' — which
        // PermissionService rule 4 reads as EDITOR for every logged-in user.
        //
        // Not a corner case. A token lives 12 hours; this endpoint exists only for clients
        // that were offline longer than the GC window of 7 days. The population it was
        // written for is the population whose token has certainly expired.
        String caller = callerId(httpRequest);
        permissions.setOwner(id, caller);
        permissions.setVisibility(id, "private");

        String text = request.text() == null ? "" : request.text();
        if (!text.isEmpty()) {
            var replica = registry.get(id);
            var op = replica.withLock(model -> model.insert(0, text));
            documents.submitAlreadyMerged(id, op, null);
        }
        return catalog.find(id).orElseThrow();
    }

    /**
     * A document's initial state.
     *
     * <p>{@code headServerSeq} matters here: the client remembers it as the point it asks for
     * changes from when reconnecting. Without it, every disconnect would mean downloading the whole
     * history from scratch.
     */
    // The most important check in this controller: this endpoint returns the FULL TEXT of the
    // document. Without it the whole WebSocket authorization is decoration, because the same
    // data leaves through one GET.
    @GetMapping("/documents/{id}")
    @RequiresDocumentAccess(Capability.READ)
    public ResponseEntity<DocumentState> open(@PathVariable UUID id, HttpServletRequest request) {
        // The role the guard admitted this request with, rather than a second lookup. Asking
        // again could straddle the 5 s permission cache expiry and answer differently than the
        // answer this request was let in on.
        Role role = DocumentAccessGuard.resolvedRole(request).orElseThrow();
        // Through the catalog rather than a direct query: the catalog filters out deleted
        // documents, so opening a hidden one gives 404 rather than empty text with no reason.
        return catalog.find(id)
            .map(summary -> ResponseEntity.ok(new DocumentState(
                id, summary.title(), documents.textOf(id), documents.headServerSeq(id),
                role.wire())))
            .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
