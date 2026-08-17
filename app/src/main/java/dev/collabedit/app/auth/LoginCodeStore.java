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

@Service
public class LoginCodeStore {
    private static final Logger log = LoggerFactory.getLogger(LoginCodeStore.class);

    static final Duration LIFETIME = Duration.ofMinutes(1);

    private static final int CODE_BYTES = 24;

    private final SecureRandom random = new SecureRandom();
    private final JdbcTemplate jdbc;

    public LoginCodeStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String issue(String userId) {
        byte[] raw = new byte[CODE_BYTES];
        random.nextBytes(raw);
        String code = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        jdbc.update("INSERT INTO login_codes (code_hash, user_id) VALUES (?, ?)",
            TokenService.sha256(code), userId);
        return code;
    }

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
