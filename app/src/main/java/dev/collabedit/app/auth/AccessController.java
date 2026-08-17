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

@RestController
public class AccessController {
    private static final Logger log = LoggerFactory.getLogger(AccessController.class);

    private static final int MAX_ENUM_WORD = 32;

    private static final int MAX_TOKEN_LENGTH = 200;

    private final PermissionService permissions;
    private final InviteService invites;

    public AccessController(PermissionService permissions, InviteService invites) {
        this.permissions = permissions;
        this.invites = invites;
    }

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

    public record MyRole(String role) {
    }

    @GetMapping("/api/documents/{documentId}/access/me")
    @RequiresDocumentAccess(Capability.READ)
    public ResponseEntity<MyRole> myRole(@PathVariable("documentId") UUID documentId,
                                         HttpServletRequest request) {
        return ResponseEntity.ok(new MyRole(DocumentAccessGuard.resolvedRole(request)
            .map(Role::wire)
            .orElseThrow(() -> new IllegalStateException(
                "the access guard did not resolve a role for a guarded endpoint"))));
    }

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
        permissions.setVisibility(documentId, visibility);
        log.info("Visibility of document {} set to {}", documentId, visibility);
        return ResponseEntity.noContent().build();
    }

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
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        if (!permissions.revoke(documentId, userId)) {
            return ResponseEntity.notFound().build();
        }
        log.info("Access of user {} to document {} revoked", userId, documentId);
        return ResponseEntity.noContent().build();
    }

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

    public record RedeemRequest(@Size(max = MAX_TOKEN_LENGTH) String token) {
    }

    public record RedeemResult(String documentId, String role) {
    }

    @PostMapping("/api/invites/redeem")
    @RequiresAuthentication("Redeems an invitation for the caller. The caller has no role in the "
        + "document yet - that is what an invitation is for - so the token is the authority and "
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
