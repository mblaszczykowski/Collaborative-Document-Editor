package dev.collabedit.app.auth;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * The one-time code that carries a finished sign-in from the server to the browser application.
 *
 * <h2>The problem this solves</h2>
 * The provider redirects back to the SERVER — that is where the authorization code is exchanged and
 * where the identity is established. The session token then has to reach the single-page
 * application, which is a different origin. There is no shared memory and no shared cookie jar
 * between them, so something has to travel through the redirect.
 *
 * <p>Sending the token itself is the obvious move and the wrong one: a redirect target lands in
 * browser history, in the {@code Referer} of anything the page loads next, and in any extension
 * reading {@code location}. That is the deprecated implicit flow, deprecated for exactly this.
 *
 * <p>So what travels is a code that is worth nothing on its own: single use, one minute, and
 * exchangeable only over POST. If it leaks it has almost certainly already been spent, and if it has
 * not it expires before anybody reads the history.
 *
 * <h2>Why the row is hashed</h2>
 * The same reasoning {@code InviteService} applies to invitations, on a shorter fuse: the row is a
 * credential, and a database read — a backup, a support query, a log of a slow statement — should
 * not yield one. The plaintext exists in the redirect and in the exchange request, and nowhere else.
 *
 * <h2>Why Postgres and not a map</h2>
 * The redirect can land on any instance, so the code has to be visible to all of them. An in-memory
 * map works on a laptop and fails on the cluster the transport is designed for — the failure that
 * only shows up in production. Redis would serve too, but the cluster design makes Redis the
 * best-effort path, and a lost login code is a sign-in that fails in somebody's face.
 */
@Service
public class LoginCodeStore {

    private static final Logger log = LoggerFactory.getLogger(LoginCodeStore.class);

    /**
     * How long a code may sit unspent.
     *
     * <p>One minute is the whole span between the provider's redirect and the application's first
     * request — a page load. Anything longer widens the window for no gain; anything shorter starts
     * failing on a slow phone.
     */
    static final Duration LIFETIME = Duration.ofMinutes(1);

    /**
     * 192 bits, the same as an invitation token.
     *
     * <p>The code is guessable-or-not on this number alone: there is no rate limit in front of the
     * exchange, deliberately, because the endpoint has nothing to enumerate — a wrong code is
     * indistinguishable from an expired one and both are a flat refusal.
     */
    private static final int CODE_BYTES = 24;

    private final SecureRandom random = new SecureRandom();
    private final JdbcTemplate jdbc;

    public LoginCodeStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Mints a code for a user who has just been authenticated.
     *
     * @return the plaintext, which is returned exactly once and never stored
     */
    public String issue(String userId) {
        byte[] raw = new byte[CODE_BYTES];
        random.nextBytes(raw);
        String code = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        jdbc.update("INSERT INTO login_codes (code_hash, user_id) VALUES (?, ?)",
            TokenService.sha256(code), userId);
        return code;
    }

    /**
     * Spends a code, if it is real and still alive.
     *
     * <p><b>A DELETE with RETURNING, rather than a SELECT and then a DELETE.</b> That is what makes
     * "single use" true rather than likely: two requests carrying the same code race, and exactly
     * one of them gets a row back, because the delete is the thing that decides. The check-then-act
     * version would hand the same session to both.
     *
     * <p>Expiry is in the predicate for the same reason it is in {@code InviteService}: a code that
     * nothing has cleaned up yet must still be refused. The sweep below is about the table's size,
     * not about correctness.
     *
     * @return the user the code was issued for, or empty when it is unknown, already spent or older
     *     than {@link #LIFETIME}
     */
    public Optional<String> redeem(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        List<String> owner = jdbc.query("""
            DELETE FROM login_codes
             WHERE code_hash = ?
               AND created_at > now() - make_interval(secs => ?)
             RETURNING user_id
            """,
            (rs, rowNum) -> rs.getString("user_id"),
            TokenService.sha256(code), (double) LIFETIME.toSeconds());
        return owner.isEmpty() ? Optional.empty() : Optional.of(owner.getFirst());
    }

    /**
     * Removes codes nobody can spend any more.
     *
     * <p>Correctness does not depend on this — {@link #redeem} refuses an expired row whether or not
     * it is still there. What depends on it is the table staying small, and a sign-in that is
     * abandoned half-way (the provider's consent screen closed, the redirect never followed) leaves
     * a row behind, so abandonment is the ordinary case rather than the exceptional one.
     */
    public int pruneExpired() {
        int removed = jdbc.update(
            "DELETE FROM login_codes WHERE created_at <= now() - make_interval(secs => ?)",
            (double) LIFETIME.toSeconds());
        if (removed > 0) {
            log.debug("Pruned {} unspent login codes", removed);
        }
        return removed;
    }
}
