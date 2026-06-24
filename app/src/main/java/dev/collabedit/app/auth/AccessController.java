package dev.collabedit.app.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Who may do what with a document — reachable from the product at last (CP21).
 *
 * <h2>Why this controller had to exist before any of it could be believed</h2>
 * The role model has been complete since CP12 and enforced structurally since CP16: four roles,
 * a capability vocabulary, an architecture test, two ADRs. It was also <b>unreachable</b>.
 * {@code PermissionService.grant}, {@code revoke} and {@code membersOf} were called from tests
 * and from nowhere else, {@link Capability#MANAGE_ACCESS} was declared and used by no endpoint at
 * all, and every document created through the interface was left at the schema default
 * {@code link} — which resolves to {@link Role#EDITOR} for <b>every logged-in user</b>.
 *
 * <p>So the product had two roles, owner and "anybody who logged in", while the repository
 * described four. This is the file that closes that gap, and it is the first endpoint in the
 * project to require {@code MANAGE_ACCESS} — which means it is also the first evidence that the
 * CP16 vocabulary describes something real rather than an intention.
 *
 * <h2>Two refusals that are the point, not paperwork</h2>
 * <ol>
 *   <li><b>No grant of {@code owner}.</b> Ownership transfer has a consequence a role change does
 *       not: the previous owner may be unable to undo it. Folding it into "set a role" is how
 *       that happens by accident rather than by intent.</li>
 *   <li><b>The owner may not revoke themselves.</b> A document with no owner cannot be secured by
 *       anybody, ever — the exact state CP16 fixed in {@code POST /documents}, which would
 *       otherwise be reachable again through the front door.</li>
 * </ol>
 *
 * <h2>Reading the member list needs MANAGE_ACCESS too</h2>
 * Not {@code READ}. The list answers "who has access to this", which is a different question from
 * "what does this say" and has no reason to be available to everyone who can read the text.
 */
@RestController
public class AccessController {

    private static final Logger log = LoggerFactory.getLogger(AccessController.class);

    /** A role or a visibility is one short word; nothing legitimate comes close. */
    private static final int MAX_ENUM_WORD = 32;

    /** 192 random bits in base64url is 32 characters; the rest is room for a format change. */
    private static final int MAX_TOKEN_LENGTH = 200;

    private final PermissionService permissions;
    private final InviteService invites;

    public AccessController(PermissionService permissions, InviteService invites) {
        this.permissions = permissions;
        this.invites = invites;
    }

    // ------------------------------------------------------------------
    // Reading the current state of access
    // ------------------------------------------------------------------

    public record InviteView(String id, String role, String expiresAt) {
    }

    public record MemberView(String userId, String displayName, String role) {
    }

    public record AccessState(String visibility, String ownerId, List<MemberView> members,
                              List<InviteView> invites) {
    }

    @GetMapping("/api/documents/{documentId}/access")
    @RequiresDocumentAccess(Capability.MANAGE_ACCESS)
    public ResponseEntity<AccessState> access(@PathVariable("documentId") UUID documentId) {
        var settings = permissions.settingsOf(documentId);
        return ResponseEntity.ok(new AccessState(
            settings.map(PermissionService.Settings::visibility).orElse("private"),
            settings.map(PermissionService.Settings::ownerId).orElse(null),
            permissions.membersOf(documentId).stream()
                .map(m -> new MemberView(m.userId(), m.displayName(), m.role().wire()))
                .toList(),
            invites.listFor(documentId).stream()
                .map(i -> new InviteView(i.id(), i.role().wire(), i.expiresAt().toString()))
                .toList()));
    }

    /**
     * The caller's own role, and nothing else.
     *
     * <p>Exists because of the event below: a client told that access changed has to find out
     * what it changed <b>to</b>, and the only endpoint that carried a role was
     * {@code GET /api/documents/{id}} — which also carries the entire text. Re-fetching a
     * hundred thousand characters to learn one word is the kind of thing that quietly makes a
     * feature not worth having.
     *
     * <p>A revoked caller gets 404 from the guard, which is the answer they need: no access.
     */
    public record MyRole(String role) {
    }

    @GetMapping("/api/documents/{documentId}/access/me")
    @RequiresDocumentAccess(Capability.READ)
    public ResponseEntity<MyRole> myRole(@PathVariable("documentId") UUID documentId,
                                         HttpServletRequest request) {
        // orElseThrow, not orElse("viewer"). Capability.READ requires a non-null role and the
        // guard sets the attribute before it lets the request through, so the fallback could never
        // fire — and if it ever did, this endpoint's entire job is to report the caller's role, so
        // inventing "viewer" would be answering the one question it exists to answer with a guess.
        // DocumentRestController.open already uses orElseThrow for the same attribute.
        return ResponseEntity.ok(new MyRole(DocumentAccessGuard.resolvedRole(request)
            .map(Role::wire)
            .orElseThrow(() -> new IllegalStateException(
                "the access guard did not resolve a role for a guarded endpoint"))));
    }

    // ------------------------------------------------------------------
    // Changing it
    // ------------------------------------------------------------------

    /**
     * @param visibility one of two words, checked against an allow-list below. The bound is here
     *     anyway, because {@code RequestBoundsIT}'s rule is that a value the CALLER chooses never
     *     decides how much work the server does — and an allow-list check on a 10 MB string is
     *     still a 10 MB string parsed, allocated and held. This controller was the only one with
     *     no bounds on any of its bodies.
     */
    public record VisibilityRequest(@Size(max = MAX_ENUM_WORD) String visibility) {
    }

    @PutMapping("/api/documents/{documentId}/access/visibility")
    @RequiresDocumentAccess(Capability.MANAGE_ACCESS)
    public ResponseEntity<Void> setVisibility(@PathVariable("documentId") UUID documentId,
                                              @Valid @RequestBody VisibilityRequest body) {
        String visibility = body.visibility();
        if (!"private".equals(visibility) && !"link".equals(visibility)) {
            return ResponseEntity.badRequest().build();
        }
        // Everybody is told, not one person: switching to `private` changes what every
        // non-member may do. The telling is a consequence of the invalidation inside setVisibility
        // — see PermissionService.invalidateLocally.
        permissions.setVisibility(documentId, visibility);
        log.info("Visibility of document {} set to {}", documentId, visibility);
        return ResponseEntity.noContent().build();
    }

    /** @param role a role name; {@code grantable} decides which, this decides how long. */
    public record RoleRequest(@Size(max = MAX_ENUM_WORD) String role) {
    }

    @PutMapping("/api/documents/{documentId}/access/members/{userId}")
    @RequiresDocumentAccess(Capability.MANAGE_ACCESS)
    public ResponseEntity<Void> setMemberRole(@PathVariable("documentId") UUID documentId,
                                              @PathVariable("userId") String userId,
                                              @Valid @RequestBody RoleRequest body) {
        Role role = grantable(body.role());
        if (role == null) {
            return ResponseEntity.badRequest().build();
        }
        // A user identifier nobody holds hits a foreign key and comes back as a 500 — an
        // unhandled server error for a caller who simply named somebody who does not exist.
        // Checked here rather than caught below, because the two cases deserve different
        // answers: "there is no such person" is the caller's mistake, and a constraint failing
        // for any other reason is ours and should stay loud.
        if (!permissions.userExists(userId)) {
            return ResponseEntity.notFound().build();
        }
        permissions.grant(documentId, userId, role);
        log.info("User {} is now {} in document {}", userId, role.wire(), documentId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/api/documents/{documentId}/access/members/{userId}")
    @RequiresDocumentAccess(Capability.MANAGE_ACCESS)
    public ResponseEntity<Void> removeMember(@PathVariable("documentId") UUID documentId,
                                             @PathVariable("userId") String userId,
                                             HttpServletRequest request) {
        String caller = TokenAuthFilter.requiredCallerId(request);
        if (userId.equals(caller)) {
            // Removing yourself from your own document leaves it with an owner_id that no longer
            // matches anybody who can act — and nothing else in the system can repair that.
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        // 404 when there was no membership row to remove, matching revokeInvite one method below
        // and every other DELETE in this project (catalog.softDelete, catalog.restore). It used to
        // answer 204 unconditionally, because PermissionService.revoke discarded the row count —
        // so "I removed Marek" and "Marek was never a member, and you may be looking at the wrong
        // document" were the same answer.
        //
        // The choice between 204-always and 404-when-absent is a real one: DELETE is idempotent, so
        // 204 is defensible. This codebase had already made it, four times, in the other direction.
        // One convention, applied, beats two defensible ones.
        if (!permissions.revoke(documentId, userId)) {
            return ResponseEntity.notFound().build();
        }
        log.info("Access of user {} to document {} revoked", userId, documentId);
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------
    // Invitations
    // ------------------------------------------------------------------

    /** @param days clamped in {@code InviteService.create}; the role is a word, not a document. */
    public record CreateInviteRequest(@Size(max = MAX_ENUM_WORD) String role, Integer days) {
    }

    public record CreatedInvite(String token, String id, String role, String expiresAt) {
    }

    @PostMapping("/api/documents/{documentId}/access/invites")
    @RequiresDocumentAccess(Capability.MANAGE_ACCESS)
    public ResponseEntity<CreatedInvite> createInvite(@PathVariable("documentId") UUID documentId,
                                                      @Valid @RequestBody CreateInviteRequest body,
                                                      HttpServletRequest request) {
        Role role = grantable(body.role());
        if (role == null) {
            return ResponseEntity.badRequest().build();
        }
        String caller = TokenAuthFilter.requiredCallerId(request);

        var issued = invites.create(documentId, role, caller,
            body.days() == null ? null : Duration.ofDays(body.days()));
        return ResponseEntity.ok(new CreatedInvite(
            issued.token(), issued.invite().id(), role.wire(),
            issued.invite().expiresAt().toString()));
    }

    @DeleteMapping("/api/documents/{documentId}/access/invites/{inviteId}")
    @RequiresDocumentAccess(Capability.MANAGE_ACCESS)
    public ResponseEntity<Void> revokeInvite(@PathVariable("documentId") UUID documentId,
                                             @PathVariable("inviteId") String inviteId) {
        return invites.revoke(documentId, inviteId)
            ? ResponseEntity.noContent().build()
            : ResponseEntity.notFound().build();
    }

    /**
     * @param token 192 bits of {@code SecureRandom}, base64url — so it has a known length, and the
     *     bound is generous rather than exact. This is the one unbounded string in this file that
     *     any logged-in user could send: {@code POST /api/invites/redeem} needs no role at all,
     *     which is the whole point of an invitation.
     */
    public record RedeemRequest(@Size(max = MAX_TOKEN_LENGTH) String token) {
    }

    public record RedeemResult(String documentId, String role) {
    }

    /**
     * Redeems an invitation.
     *
     * <p><b>Why this one is {@link RequiresAuthentication} and not {@link RequiresDocumentAccess}
     * — the interesting authorization decision in this checkpoint.</b> Every other endpoint here
     * asks "what is the caller's role in this document". This one exists precisely for callers who
     * have <i>no</i> role yet; requiring one would make an invitation redeemable only by people
     * who no longer need it. The authority is the token, and the token is the thing being checked.
     *
     * <p>What stays enforced: the caller must be logged in, because a grant needs somebody to
     * grant to; the document is taken <b>from the invite</b> and never from the request, so a
     * caller cannot redeem a valid token against a document of their choosing; and an unknown,
     * revoked or expired token gives one answer, 404, exactly like a document one may not read.
     */
    @PostMapping("/api/invites/redeem")
    @RequiresAuthentication("Redeems an invitation for the caller. The caller has no role in the "
        + "document yet — that is what an invitation is for — so the token is the authority and "
        + "the document comes from inside it, never from the request.")
    public ResponseEntity<RedeemResult> redeem(@Valid @RequestBody RedeemRequest body,
                                               HttpServletRequest request) {
        String caller = TokenAuthFilter.requiredCallerId(request);

        var documentId = invites.documentOf(body.token());
        var role = invites.redeem(body.token(), caller);
        if (documentId.isEmpty() || role.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(new RedeemResult(documentId.get().toString(), role.get().wire()));
    }

    // ------------------------------------------------------------------

    /*
     * `announce` used to live here: a private method publishing an empty AccessChanged frame with a
     * local messaging template, called from the four endpoints above.
     *
     * It moved to PermissionService.invalidateLocally, and the reason is not tidiness. Every one of
     * those four call sites had already changed a role through PermissionService, and every such
     * change invalidates — locally and, over the bus, on every other instance. Announcing HERE told
     * only the clients connected to the instance that happened to serve the request; the revoked
     * tab on any other instance was left in the state RevokedAccessFilter exempts /access from
     * filtering specifically to prevent, "still showing the document, still letting the user type
     * into a replica whose operations the inbound interceptor was refusing".
     *
     * Announcing from the invalidation instead covers every instance without adding a second
     * cluster channel for the same fact — which the old javadoc rightly refused to do. It also
     * covers the two paths that were never announcing at all: soft delete and restore both change
     * the answer to "what is your role here" and now invalidate like the rest.
     */

    /**
     * The roles this API is allowed to hand out, by name.
     *
     * <p>Deliberately <b>not</b> {@link Role#parse}. That method answers a different question —
     * "what does this value from the database mean" — and answers it by falling back to
     * {@code VIEWER}, which is the right answer there and the wrong one here: a caller who typed
     * {@code editr} would be told the grant succeeded and would have granted something else. An
     * unknown name is a client error, and {@code owner} is an unknown name on purpose.
     *
     * @return {@code null} when the name is not one this endpoint may grant
     */
    private static Role grantable(String wire) {
        if (wire == null) {
            return null;
        }
        return switch (wire.toLowerCase()) {
            case "viewer" -> Role.VIEWER;
            case "commenter" -> Role.COMMENTER;
            case "editor" -> Role.EDITOR;
            default -> null;
        };
    }
}
