package dev.collabedit.app.auth;

import dev.collabedit.app.ws.Messages;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.CrossOrigin;
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
@CrossOrigin(originPatterns = {"http://localhost:*", "http://127.0.0.1:*"})
public class AccessController {

    private static final Logger log = LoggerFactory.getLogger(AccessController.class);

    private final PermissionService permissions;
    private final InviteService invites;
    private final SimpMessagingTemplate messaging;

    public AccessController(PermissionService permissions, InviteService invites,
                            SimpMessagingTemplate messaging) {
        this.permissions = permissions;
        this.invites = invites;
        this.messaging = messaging;
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
    @RequiresDocumentAccess(value = Capability.MANAGE_ACCESS, param = "documentId")
    public AccessState access(@PathVariable("documentId") UUID documentId) {
        var settings = permissions.settingsOf(documentId);
        return new AccessState(
            settings.map(PermissionService.Settings::visibility).orElse("private"),
            settings.map(PermissionService.Settings::ownerId).orElse(null),
            permissions.membersOf(documentId).stream()
                .map(m -> new MemberView(m.userId(), m.displayName(), m.role().wire()))
                .toList(),
            invites.listFor(documentId).stream()
                .map(i -> new InviteView(i.id(), i.role().wire(), i.expiresAt().toString()))
                .toList());
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
    @RequiresDocumentAccess(value = Capability.READ, param = "documentId")
    public MyRole myRole(@PathVariable("documentId") UUID documentId,
                         HttpServletRequest request) {
        return new MyRole(DocumentAccessGuard.resolvedRole(request)
            .map(Role::wire)
            .orElse("viewer"));
    }

    // ------------------------------------------------------------------
    // Changing it
    // ------------------------------------------------------------------

    public record VisibilityRequest(String visibility) {
    }

    @PutMapping("/api/documents/{documentId}/access/visibility")
    @RequiresDocumentAccess(value = Capability.MANAGE_ACCESS, param = "documentId")
    public ResponseEntity<Void> setVisibility(@PathVariable("documentId") UUID documentId,
                                              @RequestBody VisibilityRequest body) {
        String visibility = body.visibility();
        if (!"private".equals(visibility) && !"link".equals(visibility)) {
            return ResponseEntity.badRequest().build();
        }
        permissions.setVisibility(documentId, visibility);
        // Everybody, not one person: switching to `private` changes what every non-member may do.
        announce(documentId);
        log.info("Visibility of document {} set to {}", documentId, visibility);
        return ResponseEntity.noContent().build();
    }

    public record RoleRequest(String role) {
    }

    @PutMapping("/api/documents/{documentId}/access/members/{userId}")
    @RequiresDocumentAccess(value = Capability.MANAGE_ACCESS, param = "documentId")
    public ResponseEntity<Void> setMemberRole(@PathVariable("documentId") UUID documentId,
                                              @PathVariable("userId") String userId,
                                              @RequestBody RoleRequest body) {
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
        announce(documentId);
        log.info("User {} is now {} in document {}", userId, role.wire(), documentId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/api/documents/{documentId}/access/members/{userId}")
    @RequiresDocumentAccess(value = Capability.MANAGE_ACCESS, param = "documentId")
    public ResponseEntity<Void> removeMember(@PathVariable("documentId") UUID documentId,
                                             @PathVariable("userId") String userId,
                                             HttpServletRequest request) {
        String caller = TokenAuthFilter.principalOf(request)
            .map(TokenService.Principal::userId)
            .orElse(null);
        if (userId.equals(caller)) {
            // Removing yourself from your own document leaves it with an owner_id that no longer
            // matches anybody who can act — and nothing else in the system can repair that.
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        permissions.revoke(documentId, userId);
        announce(documentId);
        log.info("Access of user {} to document {} revoked", userId, documentId);
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------
    // Invitations
    // ------------------------------------------------------------------

    public record CreateInviteRequest(String role, Integer days) {
    }

    public record CreatedInvite(String token, String id, String role, String expiresAt) {
    }

    @PostMapping("/api/documents/{documentId}/access/invites")
    @RequiresDocumentAccess(value = Capability.MANAGE_ACCESS, param = "documentId")
    public ResponseEntity<CreatedInvite> createInvite(@PathVariable("documentId") UUID documentId,
                                                      @RequestBody CreateInviteRequest body,
                                                      HttpServletRequest request) {
        Role role = grantable(body.role());
        if (role == null) {
            return ResponseEntity.badRequest().build();
        }
        String caller = TokenAuthFilter.principalOf(request)
            .map(TokenService.Principal::userId)
            .orElseThrow();

        var issued = invites.create(documentId, role, caller,
            body.days() == null ? null : Duration.ofDays(body.days()));
        return ResponseEntity.ok(new CreatedInvite(
            issued.token(), issued.invite().id(), role.wire(),
            issued.invite().expiresAt().toString()));
    }

    @DeleteMapping("/api/documents/{documentId}/access/invites/{inviteId}")
    @RequiresDocumentAccess(value = Capability.MANAGE_ACCESS, param = "documentId")
    public ResponseEntity<Void> revokeInvite(@PathVariable("documentId") UUID documentId,
                                             @PathVariable("inviteId") String inviteId) {
        return invites.revoke(documentId, inviteId)
            ? ResponseEntity.noContent().build()
            : ResponseEntity.notFound().build();
    }

    public record RedeemRequest(String token) {
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
    public ResponseEntity<RedeemResult> redeem(@RequestBody RedeemRequest body,
                                               HttpServletRequest request) {
        String caller = TokenAuthFilter.principalOf(request)
            .map(TokenService.Principal::userId)
            .orElseThrow();

        var documentId = invites.documentOf(body.token());
        var role = invites.redeem(body.token(), caller);
        if (documentId.isEmpty() || role.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        announce(documentId.get());
        return ResponseEntity.ok(new RedeemResult(documentId.get().toString(), role.get().wire()));
    }

    // ------------------------------------------------------------------

    /**
     * Tells the connected clients that access changed — <b>without saying whose, or to what</b>.
     *
     * <p>The README lists under <i>open, unresolved</i>: the role arrives once, with the initial
     * state, so raising somebody's permissions does nothing until they reload. Revocation was
     * never a hole — the server rejects the operation regardless — but a grant that visibly does
     * nothing is how a working feature reads as broken.
     *
     * <p><b>The frame is empty on purpose.</b> It used to carry the affected user and their new
     * role, which handed every subscriber the answer to a question this very class refuses to
     * answer without {@code MANAGE_ACCESS}: who has access to this document. Each client now
     * re-asks about itself, which costs one small request per connected client on a rare event.
     *
     * <p><b>Instance-local, deliberately.</b> A client connected to another instance learns of the
     * change when its permission cache entry expires (five seconds), which is the same bound that
     * already governs revocation. Publishing this on the cluster channel would mean a second
     * mechanism carrying the same fact as the permission invalidation broadcast, and two channels
     * for one fact is how they drift apart.
     */
    private void announce(UUID documentId) {
        try {
            messaging.convertAndSend("/topic/doc/" + documentId + "/access",
                new Messages.AccessChanged());
        } catch (RuntimeException e) {
            // The change is already in the database; failing to announce it costs at most the
            // five seconds it would have taken anyway. Not worth failing the request over.
            log.warn("Failed to announce the access change for document {}: {}", documentId,
                e.toString());
        }
    }

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
