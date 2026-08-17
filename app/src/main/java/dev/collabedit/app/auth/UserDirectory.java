package dev.collabedit.app.auth;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class UserDirectory {
    private static final String[] PALETTE =
        {"#C1592E", "#4C5FD5", "#2E8B6B", "#8A5CB8", "#B8862E"};

    private static final int MAX_DISPLAY_NAME_LENGTH = 64;

    private static String capped(String displayName) {
        if (displayName.length() <= MAX_DISPLAY_NAME_LENGTH) {
            return displayName;
        }
        int end = MAX_DISPLAY_NAME_LENGTH;
        if (Character.isHighSurrogate(displayName.charAt(end - 1))) {
            end--;
        }
        return displayName.substring(0, end);
    }

    private final JdbcTemplate jdbc;

    public UserDirectory(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public TokenService.Principal create(String displayName) {
        String userId = UUID.randomUUID().toString();
        String name = capped(displayName);
        String color = colourFor(name);
        jdbc.update("INSERT INTO users (id, display_name, color) VALUES (?, ?, ?)",
            userId, name, color);
        return new TokenService.Principal(userId, name, color);
    }

    public TokenService.Principal upsertFederated(String provider, String subject,
                                                  String displayName, String email) {
        String candidateId = UUID.randomUUID().toString();
        return jdbc.queryForObject("""
            INSERT INTO users (id, display_name, color, provider, subject, email)
                 VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (provider, subject) WHERE provider IS NOT NULL DO UPDATE
                    SET display_name = EXCLUDED.display_name,
                        -- COALESCE, because a sign-in that does not carry an email must not erase
                        -- one an earlier sign-in did. GitHub returns a null email whenever the
                        -- address is private, and a user can make it private at any time - so the
                        -- plain assignment meant the column emptied itself on a later login,
                        -- non-deterministically. The javadoc says the email is kept so that a
                        -- future "invite by email" can mean something; that only holds if it stays.
                        email        = COALESCE(EXCLUDED.email, users.email)
              RETURNING id, display_name, color
            """,
            (rs, rowNum) -> new TokenService.Principal(
                rs.getString("id"), rs.getString("display_name"), rs.getString("color")),
            candidateId, capped(displayName), colourFor(candidateId), provider, subject, email);
    }

    private static String colourFor(String stableKey) {
        return PALETTE[Math.floorMod(stableKey.hashCode(), PALETTE.length)];
    }

    public Optional<TokenService.Principal> find(String userId) {
        List<TokenService.Principal> found = jdbc.query(
            "SELECT id, display_name, color FROM users WHERE id = ?",
            (rs, rowNum) -> new TokenService.Principal(
                rs.getString("id"), rs.getString("display_name"), rs.getString("color")),
            userId);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.getFirst());
    }
}
