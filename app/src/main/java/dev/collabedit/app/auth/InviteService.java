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

/**
 * Invitations to a document (CP21).
 *
 * <h2>What problem this actually solves</h2>
 * Not "a screen for managing access" — the role model has had {@code grant} and {@code revoke}
 * since CP12, and they were called from tests only. The missing piece is that <b>there is nothing
 * to type into a share box</b>: login has no password and mints a new user identifier on every
 * call, so a person is not addressable before they arrive. The reasoning, and the option that was
 * rejected (a searchable user directory), is in {@code V5__invites.sql} and the invitation design.
 *
 * <p>So an invitation carries the role and identity is bound at redemption. The consequence worth
 * stating plainly: <b>an invite is a bearer credential</b>. Whoever holds the token gets the role
 * — there is no second factor, and there cannot be one in a system without passwords.
 *
 * <h2>What follows from that</h2>
 * <ul>
 *   <li>the token is random (192 bits from {@link SecureRandom}), never derived from anything
 *       guessable such as the document identifier or a counter;</li>
 *   <li>only its hash is stored, so a database dump is not a set of working invitations;</li>
 *   <li>every invite expires, and expiry is checked at redemption rather than by a sweep —
 *       a job that has to run for a security property to hold is a security property that
 *       fails quietly when the job does not run.</li>
 * </ul>
 */
@Service
public class InviteService {

    private static final Logger log = LoggerFactory.getLogger(InviteService.class);

    /**
     * How long an invitation is valid by default.
     *
     * <p>Seven days is long enough that "I sent it on Friday" still works on Monday, and short
     * enough that a link pasted into a chat channel stops being a way in before anybody
     * remembers it is there. Unlike the GC window — the other seven days in this project — this
     * one is not load-bearing for correctness, so it does not need telemetry to justify: the
     * cost of being wrong is one more click.
     */
    private static final Duration DEFAULT_LIFETIME = Duration.ofDays(7);

    /** Upper bound on what the caller may ask for; a "never expires" invite is not on offer. */
    private static final Duration MAX_LIFETIME = Duration.ofDays(30);

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final PermissionService permissions;

    public InviteService(JdbcTemplate jdbc, PermissionService permissions) {
        this.jdbc = jdbc;
        this.permissions = permissions;
    }

    /** An invitation as the owner sees it. The token is absent — it exists only once, at creation. */
    public record Invite(String id, UUID documentId, Role role, String createdBy,
                         Instant createdAt, Instant expiresAt) {
    }

    /** The one moment the plain token exists on this side. */
    public record IssuedInvite(String token, Invite invite) {
    }

    /**
     * Mints an invitation.
     *
     * @param role never {@link Role#OWNER} — refused here and by a CHECK constraint in the
     *             schema, because two layers is what makes the refusal survive a bug in one
     */
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

    /**
     * Redeems an invitation for the calling user.
     *
     * <p>The grant is <b>not</b> unconditional: somebody who already has a stronger role keeps it.
     * Without that check, an owner opening their own invite link — the most likely way anybody
     * tests one — would demote themselves to editor and lose the ability to undo it. That is the
     * same class of accident as revoking your own ownership, and it deserves the same refusal.
     *
     * @return the role the caller ends up with, or empty when the invite is unknown, revoked or
     *         expired — the three are one answer on purpose, exactly like a document the caller
     *         may not read
     */
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

    /** Which document an invite points at, without redeeming it — for the preview before login. */
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

    /**
     * Stops an invitation working.
     *
     * <p>Scoped by document as well as by identifier, so a caller authorized for document A
     * cannot revoke an invite belonging to document B by guessing its hash. The guard authorizes
     * the document in the path; without this condition the identifier in the body would be
     * authorizing itself.
     */
    public boolean revoke(UUID documentId, String inviteId) {
        int updated = jdbc.update("""
            UPDATE document_invites SET revoked_at = now()
             WHERE token_hash = ? AND doc_id = ? AND revoked_at IS NULL
            """, inviteId, documentId);
        return updated > 0;
    }

    /** See {@link TokenService#sha256} — shared with the login-code store, which stores the same
     * kind of secret for the same reason. */
    private static String hashOf(String token) {
        return TokenService.sha256(token);
    }

    private record Row(UUID documentId, Role role) {
    }
}
