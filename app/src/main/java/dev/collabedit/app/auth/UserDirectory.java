package dev.collabedit.app.auth;

import java.util.List;
import java.util.Optional;
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
 * <p>Small on purpose. It is not a repository layer waiting to grow — a row is an identifier, a
 * name, a colour and which provider vouched for it — and the point here is that the small thing
 * lives with the other identity things rather than inside the largest controller in the
 * application.
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

    /**
     * The upper bound on a name visible to others — enforced <b>here</b>, where the name is stored.
     *
     * <p>The name goes into the token, into this table and into every presence broadcast, that is,
     * a message sent many times a second to every participant. Without a limit one user with a
     * megabyte-long name loads everyone else's connection.
     *
     * <p>It was enforced by each caller instead: {@code LoginController} for the typed name and
     * {@code OidcLoginSuccess} for the provider's, with the same {@code 64} written twice and
     * nothing requiring a third caller to know about either. That is the shape of safeguard
     * {@code DocumentAccessGuard} exists to argue against — one that holds only while everybody
     * remembers it. Both callers now hand over whatever they have and this decides.
     */
    private static final int MAX_DISPLAY_NAME_LENGTH = 64;

    /**
     * Shortens a name to {@link #MAX_DISPLAY_NAME_LENGTH} <b>without splitting a character</b>.
     *
     * <p>A plain {@code substring(0, 64)} cuts between the two halves of a surrogate pair when the
     * 64th unit is the first half of one, and the result is a lone surrogate: not a character, not
     * encodable as UTF-8, and stored as a replacement byte or rejected outright depending on which
     * driver sees it first. Emoji in a display name is ordinary at both providers this application
     * talks to, and a 64-character name ending in one is all it takes.
     *
     * <p>The same cut is handled the same way in {@code DocumentService.splitForInsert} and
     * {@code HistoryService}, for the same reason — this was the third copy of the mistake and the
     * only one still open.
     */
    private static String capped(String displayName) {
        if (displayName.length() <= MAX_DISPLAY_NAME_LENGTH) {
            return displayName;
        }
        int end = MAX_DISPLAY_NAME_LENGTH;
        // The partner of a high surrogate at the boundary is the unit being cut, so drop both.
        if (Character.isHighSurrogate(displayName.charAt(end - 1))) {
            end--;
        }
        return displayName.substring(0, end);
    }

    private final JdbcTemplate jdbc;

    public UserDirectory(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Creates an identity with a fresh identifier.
     *
     * <p>Used by the DEVELOPMENT login only — {@code POST /api/login}, which is off unless a
     * deployment asks for it. Identity from a provider goes through {@link #upsertFederated}, which
     * finds the same row every time; this one mints a new identifier per call, which is precisely
     * the behaviour federated identity replaced and the reason this path is not the default.
     *
     * <p>The {@code INSERT} has no {@code ON CONFLICT} clause and does not need one: the identifier
     * is a {@code UUID.randomUUID} generated on the line above. The version in the controller
     * carried {@code ON CONFLICT (id) DO NOTHING}, which could never fire — a clause that reads as
     * "collisions are handled here" while handling nothing, and which would have swallowed a real
     * collision silently if one were ever possible.
     */
    public TokenService.Principal create(String displayName) {
        String userId = UUID.randomUUID().toString();
        String name = capped(displayName);
        // Derived from the NAME here, and from the identifier in upsertFederated — a difference
        // that looks like an oversight and is not. This path mints a new identifier on every call,
        // so an identifier-derived colour would give "Anna" a different colour every time she
        // signs in; the whole use of this path is two tabs pretending to be two people, and a
        // participant who changes colour on each reload defeats it. The federated path has the
        // opposite problem — a stable person who can rename themselves — so it keys on the thing
        // that does not move there. Same goal, different stable key.
        String color = colourFor(name);
        jdbc.update("INSERT INTO users (id, display_name, color) VALUES (?, ?, ?)",
            userId, name, color);
        return new TokenService.Principal(userId, name, color);
    }

    /**
     * The person a provider has just vouched for — <b>the same row every time they return</b>.
     *
     * <h2>Why the subject, and not the email</h2>
     * {@code subject} is the provider's own identifier for the account, and it is the only field
     * OpenID Connect promises is stable and unique within an issuer. An email address is neither:
     * it can be changed by its owner and, at some providers, released and reassigned to somebody
     * else. Keying on it would eventually hand one person another person's documents, which is the
     * one mistake in this file that cannot be undone by editing a row.
     *
     * <p>So the email is stored and never used to identify. It is here to be shown, and to make a
     * future "invite by email" mean something.
     *
     * <h2>Why an upsert rather than find-then-insert</h2>
     * Two tabs finishing a sign-in at once is ordinary — a person clicking the provider's button
     * twice produces it — and the check-then-act version races: both find nothing, both insert, and
     * the loser gets a unique-violation out of a login. {@code ON CONFLICT} on the identity index
     * makes the second one an update, and the {@code RETURNING} clause means the winner's row is
     * what comes back either way.
     *
     * <p>The display name and the email are refreshed on every sign-in, because the provider is the
     * authority on both and a person who changes their name at Google means it. The COLOUR is not:
     * it is derived from the identifier rather than the name, so somebody renaming themselves does
     * not change colour in the middle of a document for everyone reading it.
     */
    public TokenService.Principal upsertFederated(String provider, String subject,
                                                  String displayName, String email) {
        String candidateId = UUID.randomUUID().toString();
        return jdbc.queryForObject("""
            INSERT INTO users (id, display_name, color, provider, subject, email)
                 VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (provider, subject) WHERE provider IS NOT NULL DO UPDATE
                    SET display_name = EXCLUDED.display_name,
                        email        = EXCLUDED.email
              RETURNING id, display_name, color
            """,
            (rs, rowNum) -> new TokenService.Principal(
                rs.getString("id"), rs.getString("display_name"), rs.getString("color")),
            candidateId, capped(displayName), colourFor(candidateId), provider, subject, email);
    }

    /**
     * A stable colour.
     *
     * <p>Derived rather than random, so the same person looks the same to everybody else across
     * sessions and across instances — it is the only continuity a reader of the document has.
     *
     * <p>It used to be derived from the DISPLAY NAME, which was right while identity was a name
     * typed at a login box and nothing else. Now that a provider vouches for a person who can rename
     * themselves, deriving from the name would change their colour mid-document. The identifier is
     * the thing that does not move.
     */
    private static String colourFor(String stableKey) {
        return PALETTE[Math.floorMod(stableKey.hashCode(), PALETTE.length)];
    }

    /**
     * The identity behind a user id.
     *
     * <p>Read back rather than carried through the login code, so the code stays a bare reference
     * with nothing in it worth stealing — and so the name and colour handed to the browser are the
     * ones in the table at the moment of exchange rather than at the moment of the redirect.
     *
     * @return empty when the row has gone, which a foreign key makes rare and a deleted account
     *     makes possible
     */
    public Optional<TokenService.Principal> find(String userId) {
        List<TokenService.Principal> found = jdbc.query(
            "SELECT id, display_name, color FROM users WHERE id = ?",
            (rs, rowNum) -> new TokenService.Principal(
                rs.getString("id"), rs.getString("display_name"), rs.getString("color")),
            userId);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.getFirst());
    }
}
