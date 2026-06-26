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

/**
 * REST for the things that need not be real time: the document list, creating a new document, the
 * initial state read on open.
 *
 * <p>The split is deliberate. The WebSocket channel carries only <b>incremental changes</b>;
 * fetching the initial state over REST lets us use the ordinary HTTP machinery (compression,
 * caching, handling large responses) and keeps frames of several hundred kilobytes off a channel
 * optimised for small messages (a 64 KiB limit per message).
 *
 * <h2>The path variable is {@code documentId}, and used not to be</h2>
 * This controller spelled it {@code {id}} while every other controller in the project spelled it
 * {@code {documentId}}. The cost was not aesthetic: {@link RequiresDocumentAccess#param}
 * defaulted to {@code "id"}, so <b>fifteen endpoints elsewhere repeated
 * {@code param = "documentId"}</b> to override a default that only four used — and a mismatch
 * between the two produces a permanent, silent 404, because the guard cannot find the variable and
 * fails closed. Fail-closed is right and invisible is not, which is why
 * {@code EndpointAuthorizationArchitectureTest} now also checks that every declared {@code param}
 * appears in that endpoint's own mapping.
 *
 * <p>The URLs are unchanged — a path variable's name is not part of the address.
 */
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

    // login used to be here, together with the only JdbcTemplate under api/ and the author
    // colour palette it reads. It is identity management, and it now lives in auth/ beside the
    // token service, the request filter and the guard — see LoginController and UserDirectory.

    /**
     * The upper bound on a document's title.
     *
     * <p>The title is not free-form storage. It is shown in the document list, it goes into the
     * search vector on every snapshot ({@code to_tsvector('simple', title || ' ' || …)}), and it is
     * read back on every list request — so a megabyte title is a megabyte in every one of those.
     * 256 characters is longer than any title anybody writes and short enough that none of those
     * paths has to think about it.
     */
    private static final int MAX_TITLE_LENGTH = 256;

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

    // ------------------------------------------------------------------
    // Listing
    // ------------------------------------------------------------------

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
    public ResponseEntity<List<DocumentCatalog.DocumentSummary>> list(
        @RequestParam(value = "q", required = false)
        @Size(max = MAX_SEARCH_QUERY) String query,
        @RequestParam(value = "workspace", required = false) UUID workspaceId,
        HttpServletRequest request) {

        String caller = TokenAuthFilter.requiredCallerId(request);
        List<DocumentCatalog.DocumentSummary> found = query != null && !query.isBlank()
            ? catalog.search(caller, workspaceId, query.trim(), LIST_LIMIT)
: catalog.list(caller, workspaceId, LIST_LIMIT);

        // Permission filtering happens TWICE, and each pass has a different job.
        //
        // The query narrows the candidate set, so the LIMIT below applies to documents the caller
        // can actually see. It used to not: the list was "the 200 most recently updated documents
        // on the instance" and this filter ran over that global slice, so on a busy instance a
        // user's own documents silently vanished from their own first screen.
        //
        // This pass stays because it is the authority. The rule now exists in two places — the SQL
        // in DocumentCatalog and PermissionService here — which is a real cost, paid deliberately
        // and pinned by DocumentCatalogPermissionParityTest rather than trusted. Keeping both means
        // a mistake in the SQL can only ever show too FEW documents.
        return ResponseEntity.ok(
            found.stream().filter(doc -> permissions.canRead(caller, doc.id())).toList());
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
    public ResponseEntity<List<DocumentCatalog.DocumentSummary>> trash(HttpServletRequest request) {
        String caller = TokenAuthFilter.requiredCallerId(request);
        // The bin shows only documents the caller could open if they were not deleted. Without
        // that the bin would be a way around permissions — a list of other people's titles.
        return ResponseEntity.ok(catalog.listDeleted(caller, LIST_LIMIT).stream()
            .filter(doc -> permissions.roleOfDeleted(caller, doc.id()).isPresent())
            .toList());
    }

    // ------------------------------------------------------------------
    // Creating
    // ------------------------------------------------------------------

    public record CreateDocumentRequest(@Size(max = MAX_TITLE_LENGTH) String title,
                                        UUID workspaceId) {
    }

    @PostMapping("/documents")
    @RequiresAuthentication("Creates a document, so there is none to check yet — but there "
        + "must be a user to own it. See createOwned for what used to happen without one.")
    public ResponseEntity<DocumentCatalog.DocumentSummary> create(
            @Valid @RequestBody CreateDocumentRequest body, HttpServletRequest request) {
        String title = body.title() == null || body.title().isBlank()
            ? "Untitled document" : body.title().trim();
        return ResponseEntity.ok(
            createOwned(title, body.workspaceId, TokenAuthFilter.requiredCallerId(request)));
    }

    /**
     * The four steps every document creation takes, in one place.
     *
     * <h2>Why they are four steps and not one</h2>
     * They were repeated verbatim in {@code create} and {@code createRecovered}, and each one
     * exists for a reason that is easy to drop when copying:
     *
     * <ol>
     * <li><b>The row.</b> Nothing interesting.</li>
     * <li><b>The owner.</b> Without one, nobody can ever change the document's visibility or
     * grant access to it — the document is unsecurable for the rest of its life, and nothing
     * anywhere reports that. Both call sites used to do this inside {@code if (caller !=
     * null)}, which produced exactly that state whenever the token was missing or expired.
     * The annotations on the endpoints are what make the unconditional call correct: the
     * guard has already refused a request with no caller.</li>
     * <li><b>PRIVATE, not the schema default.</b> The column defaults to {@code link}, and
     * {@code link} resolves to EDITOR for every logged-in user (PermissionService rule 4). So
     * until CP21, every document created through the interface was editable by anybody who
     * typed a name into the login box — which made the four roles the repository documents
     * into the two the product could produce: owner, and everyone. On a laptop that reads as
     * convenience; on a public instance it is the whole access story being false in the one
     * place a visitor can check it.</li>
     * <li><b>Reading the row back</b>, so the response carries the same values the list will.</li>
     * </ol>
     */
    private DocumentCatalog.DocumentSummary createOwned(String title, UUID workspaceId,
                                                        String ownerId) {
        UUID id = UUID.randomUUID();
        // `private` is in the INSERT now (DocumentCatalog.create) rather than being set by a
        // follow-up UPDATE here. The difference is where the safety lives: this call was the only
        // reason documents created through the product were private, so every OTHER path into
        // `create` produced a world-editable document — which is how a two-day-old development
        // database ended up holding 132 of them. V12 drops the column default that allowed it.
        //
        // It also removes an invalidation: setOwner and setVisibility each broadcast an access
        // change, so creating a document published two of them, to a topic for an identifier
        // nobody can yet be subscribed to.
        catalog.create(id, title, workspaceId);
        permissions.setOwner(id, ownerId);
        return catalog.find(id).orElseThrow();
    }

    /**
     * @param text the recovered content. Bounded, but generously: this endpoint exists to save
     * somebody's unsynchronised work, so a limit tight enough to be "safe" would
     * discard exactly what the request is for. A megabyte is far above any draft a
     * person types offline and far below anything that threatens the process.
     */
    public record RecoveredRequest(@Size(max = MAX_TITLE_LENGTH) String title,
                                   @Size(max = MAX_RECOVERED_CHARACTERS) String text) {
    }

    /**
     * How long a search phrase may be.
     *
     * <p>Every other caller-supplied string in this project carries a bound — title 256, comment
     * body 4000, quoted text 8192, suggestion text 8192 — and this one did not. Tomcat's 8 KB
     * request line caps it in practice, which is why this is a consistency gap rather than a hole;
     * but {@code RequestBoundsIT}'s thesis is that a value the CALLER chooses must not decide how
     * much work the server does, and {@code websearch_to_tsquery} parses whatever arrives.
     *
     * <p>This is the project's first constraint on a method PARAMETER rather than on a body field,
     * and the mechanism differs: Spring resolves it through built-in method validation and reports
     * it as {@code HandlerMethodValidationException}, not {@code MethodArgumentNotValidException}.
     * A bound that looks enforced and is not would be worse than no bound, so
     * {@code RequestBoundsIT.aSearchPhrasePastTheBoundIsRefused} exists to prove this one fires.
     */
    private static final int MAX_SEARCH_QUERY = 256;

    /** See {@link RecoveredRequest#text}. */
    private static final int MAX_RECOVERED_CHARACTERS = 1_000_000;

    /**
     * How much text goes into a single generated {@code Insert}.
     *
     * <p>The rule and its reasoning live on {@link DocumentService#MAX_INSERT_CHARACTERS}, because
     * this endpoint turned out not to be the only place that breaks it. Kept as an alias so the
     * call sites below read the same as they did.
     */
    static final int MAX_INSERT_CHARACTERS = DocumentService.MAX_INSERT_CHARACTERS;

    /**
     * Creates a document from recovered content.
     *
     * <p>The rescue path from the tombstone-collection design: the client was offline for longer than the GC window, its
     * operations point at tombstones that no longer exist, and <b>there is no honest way to merge
     * them</b>. Rather than gluing the text into a random place or losing it, we save the local
     * version as a separate document and tell the user so plainly.
     *
     * <p>The content is inserted as ordinary operations of the server replica — so the new document
     * is nothing special and has a full history from the first moment, like any other.
     *
     * <p><b>The visibility is private</b>, unlike the {@code link} default the schema carries, and
     * that is deliberate. A recovered draft is created from <b>one person's unsynchronised work</b>
     * that nobody else has ever seen; handing it to every logged-in user would be disclosing the
     * content at exactly the moment the user is worried about losing it.
     */
    @PostMapping("/documents/recovered")
    @RequiresAuthentication("Creates a document from work that was never synchronised, so "
        + "there is nothing to authorize against — but an anonymous caller here produced an "
        + "ownerless, link-visible draft, which is the exact opposite of the intent.")
    public ResponseEntity<DocumentCatalog.DocumentSummary> createRecovered(
            @Valid @RequestBody RecoveredRequest request, HttpServletRequest httpRequest) {
        String title = request.title == null || request.title.isBlank()
            ? "Recovered draft" : request.title;
        DocumentCatalog.DocumentSummary created = createOwned(
            title, null, TokenAuthFilter.requiredCallerId(httpRequest));

        String text = request.text == null ? "" : request.text;
        if (!text.isEmpty()) {
            // Generated AND enqueued under ONE lock acquisition. This used to take the replica,
            // build the operation under withLock, and then call submitAlreadyMerged — which asks
            // the registry for the replica AGAIN. Between the two the replica can be evicted (both
            // buffers are empty at that moment, so it is a legal candidate), and the operations
            // would then be enqueued on a FRESH replica that never saw them, with no
            // StaleReplicaException to say so. See DocumentService.generateAndSubmit.
            documents.generateAndSubmit(created.id(), model -> {
                List<Op> inserts = new ArrayList<>();
                int at = 0;
                // splitForInsert rather than a fixed stride, which is what this loop used to do.
                // The stride cut on a raw code-unit boundary, so a recovered draft with an emoji
                // straddling character 8192 produced two operations each carrying half of it —
                // a lone surrogate in the operation log. Same limit, same shape, one fewer way to
                // corrupt a document the user was told had been rescued.
                for (String piece : DocumentService.splitForInsert(text)) {
                    inserts.add(model.insert(at, piece));
                    at += piece.length();
                }
                return inserts;
            }, null);
        }
        // Read back AFTER the content exists, so the caller does not have to ask twice.
        return ResponseEntity.ok(catalog.find(created.id()).orElseThrow());
    }

    // ------------------------------------------------------------------
    // One document
    // ------------------------------------------------------------------

    public record RenameRequest(@Size(max = MAX_TITLE_LENGTH) String title) {
    }

    @PatchMapping("/documents/{documentId}")
    @RequiresDocumentAccess(Capability.WRITE_TEXT)
    public ResponseEntity<Void> rename(@PathVariable UUID documentId,
                                       @Valid @RequestBody RenameRequest body) {
        if (body.title() == null || body.title().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        return catalog.rename(documentId, body.title.trim())
            ? ResponseEntity.noContent().build()
            : ResponseEntity.notFound().build();
    }

    /**
     * Hides a document. <b>It deletes nothing.</b>
     *
     * <p>The {@code operations} table has {@code ON DELETE CASCADE} on {@code doc_id}, so a hard
     * delete would erase the operation log — the source of truth from the operation-log decision, which is
     * append-only. A user clicking "delete" wants the document gone from their list, not gone from
     * existence.
     */
    // Deleting needs WRITE, not ownership: a document a team shares should be hideable by
    // anyone who edits it. The operation being reversible (trash) is what makes that safe.
    @DeleteMapping("/documents/{documentId}")
    @RequiresDocumentAccess(Capability.WRITE_TEXT)
    public ResponseEntity<Void> delete(@PathVariable UUID documentId) {
        if (!catalog.softDelete(documentId)) {
            return ResponseEntity.notFound().build();
        }
        // Deleting CHANGES THE ANSWER to "what is your role here", because PermissionService.resolve
        // filters `AND d.deleted_at IS NULL` — so a soft delete is a permission change and has to
        // invalidate like one. It did not, and roleOf caches for five seconds on every instance, so
        // for that window a deleted document could still be read through /snapshot (which has no
        // deleted-check of its own) and still be written through /history/restore.
        //
        // Every other mutation that moves this answer — grant, revoke, setVisibility, setOwner —
        // goes through PermissionService and invalidates. These two went through the catalog and
        // bypassed it. PermissionIT.deletedDocumentIsInvisibleEvenToOwner did not catch it because
        // the test performed this invalidation BY HAND, so it proved the query and not the endpoint.
        permissions.invalidate(documentId);
        return ResponseEntity.noContent().build();
    }

    // includeDeleted, because restoring a document from the trash is by definition an
    // operation on a deleted one — and must not skip the permission check while doing so,
    // or the trash becomes a way to reach other people's documents.
    @PostMapping("/documents/{documentId}/restore")
    @RequiresDocumentAccess(value = Capability.WRITE_TEXT, includeDeleted = true)
    public ResponseEntity<Void> restore(@PathVariable UUID documentId) {
        if (!catalog.restore(documentId)) {
            return ResponseEntity.notFound().build();
        }
        // The same in the other direction, and the direction that annoys a user rather than
        // exposing one: without this, everybody who was refused while the document sat in the bin
        // keeps their cached "no access" and gets a 404 for five more seconds after it comes back.
        permissions.invalidate(documentId);
        return ResponseEntity.noContent().build();
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
    @GetMapping("/documents/{documentId}")
    @RequiresDocumentAccess(Capability.READ)
    public ResponseEntity<DocumentState> open(@PathVariable UUID documentId,
                                              HttpServletRequest request) {
        // The role the guard admitted this request with, rather than a second lookup. Asking
        // again could straddle the 5 s permission cache expiry and answer differently than the
        // answer this request was let in on.
        Role role = DocumentAccessGuard.resolvedRole(request).orElseThrow();
        // Through the catalog rather than a direct query: the catalog filters out deleted
        // documents, so opening a hidden one gives 404 rather than empty text with no reason.
        return catalog.find(documentId)
            .map(summary -> ResponseEntity.ok(new DocumentState(
                documentId, summary.title(), documents.textOf(documentId),
                documents.headServerSeq(documentId), role.wire())))
                    .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // ------------------------------------------------------------------
    // Workspaces
    // ------------------------------------------------------------------

    public record CreateWorkspaceRequest(@Size(max = MAX_TITLE_LENGTH) String name) {
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
        return ResponseEntity.ok(
            catalog.workspacesOf(TokenAuthFilter.requiredCallerId(request)));
    }

    @PostMapping("/workspaces")
    @RequiresAuthentication("Creates a workspace owned by the caller; nothing exists yet to "
        + "authorize against.")
    public ResponseEntity<DocumentCatalog.Workspace> createWorkspace(
            @Valid @RequestBody CreateWorkspaceRequest body, HttpServletRequest request) {
        // Owner from the token as well: without that it was possible to create a workspace
        // belonging to somebody else, i.e. to inject an entry onto their own list.
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
