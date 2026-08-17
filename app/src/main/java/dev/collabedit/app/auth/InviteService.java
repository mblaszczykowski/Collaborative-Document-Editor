package dev.collabedit.app.auth;

import java.sql.Timestamp;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class InviteService {
    private static final Logger log = LoggerFactory.getLogger(InviteService.class);

    private static final Duration DEFAULT_LIFETIME = Duration.ofDays(7);

    private static final Duration MAX_LIFETIME = Duration.ofDays(30);

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final PermissionService permissions;

    public InviteService(JdbcTemplate jdbc, PermissionService permissions) {
        this.jdbc = jdbc;
        this.permissions = permissions;
    }

    public record Invite(String id, UUID documentId, Role role, String createdBy,
                         Instant createdAt, Instant expiresAt) {
    }

    public record IssuedInvite(String token, Invite invite) {
    }

    public IssuedInvite create(UUID documentId, Role role, String createdBy, Duration lifetime) {
        if (role == Role.OWNER) {
            throw new IllegalArgumentException("an invite cannot grant ownership");
        }
        Duration valid = lifetime == null || lifetime.isZero() || lifetime.isNegative()
            ? DEFAULT_LIFETIME
            : lifetime;
        if (valid.compareTo(MAX_LIFETIME) > 0) {
            valid = MAX_LIFETIME;
        }

        byte[] entropy = new byte[24];
        RANDOM.nextBytes(entropy);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
        String hash = hashOf(token);
        Instant now = Instant.now();
        Instant expiresAt = now.plus(valid);

        jdbc.update("""
            INSERT INTO document_invites (token_hash, doc_id, role, created_by, expires_at)
            VALUES (?, ?, ?, ?, ?)
            """, hash, documentId, role.wire(), createdBy, Timestamp.from(expiresAt));

        log.info("Invite minted for document {} with role {} by {}", documentId, role.wire(),
            createdBy);
        return new IssuedInvite(token,
            new Invite(hash, documentId, role, createdBy, now, expiresAt));
    }

    public Optional<Role> redeem(String token, String userId) {
        if (token == null || token.isBlank() || userId == null) {
            return Optional.empty();
        }
        List<Row> rows = jdbc.query("""
            SELECT doc_id, role
              FROM document_invites
             WHERE token_hash = ?
               AND revoked_at IS NULL
               AND expires_at > now()
            """,
            (rs, rowNum) -> new Row((UUID) rs.getObject("doc_id"), Role.parse(rs.getString("role"))),
            hashOf(token));

        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Row invite = rows.getFirst();

        Role existing = permissions.roleOf(userId, invite.documentId()).orElse(null);
        if (existing != null && existing.atLeast(invite.role())) {
            return Optional.of(existing);
        }
        permissions.grant(invite.documentId(), userId, invite.role());
        log.info("Invite redeemed: user {} is now {} in document {}", userId,
            invite.role().wire(), invite.documentId());
        return Optional.of(invite.role());
    }

    public Optional<UUID> documentOf(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return jdbc.query("""
            SELECT doc_id FROM document_invites
             WHERE token_hash = ? AND revoked_at IS NULL AND expires_at > now()
            """,
            (rs, rowNum) -> (UUID) rs.getObject("doc_id"),
            hashOf(token)).stream().findFirst();
    }

    public List<Invite> listFor(UUID documentId) {
        return jdbc.query("""
            SELECT token_hash, doc_id, role, created_by, created_at, expires_at
              FROM document_invites
             WHERE doc_id = ? AND revoked_at IS NULL AND expires_at > now()
             ORDER BY created_at DESC
            """,
            (rs, rowNum) -> new Invite(
                rs.getString("token_hash"),
                (UUID) rs.getObject("doc_id"),
                Role.parse(rs.getString("role")),
                rs.getString("created_by"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("expires_at").toInstant()),
            documentId);
    }

    public boolean revoke(UUID documentId, String inviteId) {
        int updated = jdbc.update("""
            UPDATE document_invites SET revoked_at = now()
             WHERE token_hash = ? AND doc_id = ? AND revoked_at IS NULL
            """, inviteId, documentId);
        return updated > 0;
    }

    private static String hashOf(String token) {
        return TokenService.sha256(token);
    }

    private record Row(UUID documentId, Role role) {
    }
}
