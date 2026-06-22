package dev.collabedit.app.auth;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The {@code users} table — who exists, and what they are called.
 *
 * <h2>Why this class exists at all</h2>
 * The row was written by {@code DocumentRestController.login}, with a {@code JdbcTemplate} injected
 * into the <b>document</b> controller for that one statement — the only raw SQL anywhere under
 * {@code api/}, in a class whose subject is documents, while an {@code auth} package sat one
 * directory away. Identity management had a home; it just was not living in it.
 *
 * <p>Small on purpose. It is not a repository layer waiting to grow — the project's identity model
 * is deliberately thin (passwordless, an identifier per login), and the point here is
 * that the thin thing lives with the other thin identity things rather than inside the largest
 * controller in the application.
 *
 * <p>{@code PermissionService.userExists} reads the same table and stays where it is: it answers a
 * question about AUTHORIZATION ("is there anybody to grant this to"), and it already lives in the
 * class that owns that question.
 */
@Repository
public class UserDirectory {

    /**
     * The palette from the mockup described in the original UI scope — author colours in the text.
     *
     * <p>It used to sit in {@code DocumentRestController} under a banner reading "Authorization
     * (CP12)", about eighty lines from {@code login}, which is its only reader. A constant filed
     * under an unrelated heading is a constant the next person has to search for.
     */
    private static final String[] PALETTE =
        {"#C1592E", "#4C5FD5", "#2E8B6B", "#8A5CB8", "#B8862E"};

    private final JdbcTemplate jdbc;

    public UserDirectory(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Creates an identity with a fresh identifier.
     *
     * <p>The colour is derived from the name rather than assigned at random, so the same person
     * logging in twice looks the same to everybody else — the identifiers differ (login is
     * passwordless and mints a new one each time), and the colour is the only continuity a reader
     * of the document has.
     *
     * <p>The {@code INSERT} has no {@code ON CONFLICT} clause and does not need one: the identifier
     * is a {@code UUID.randomUUID} generated on the line above. The version in the controller
     * carried {@code ON CONFLICT (id) DO NOTHING}, which could never fire — a clause that reads as
     * "collisions are handled here" while handling nothing, and which would have swallowed a real
     * collision silently if one were ever possible.
     */
    public TokenService.Principal create(String displayName) {
        String userId = UUID.randomUUID().toString();
        String color = PALETTE[Math.floorMod(displayName.hashCode(), PALETTE.length)];
        jdbc.update("INSERT INTO users (id, display_name, color) VALUES (?, ?, ?)",
            userId, displayName, color);
        return new TokenService.Principal(userId, displayName, color);
    }
}
