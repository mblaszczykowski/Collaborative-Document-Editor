package dev.collabedit.app.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Issuing an identity — <b>the one endpoint every other endpoint depends on</b>.
 *
 * <h2>Why it is here and not in DocumentRestController</h2>
 * It was there, writing to the {@code users} table through a {@code JdbcTemplate} injected into the
 * document controller for that single statement. Nothing about logging in concerns documents, and
 * the package that owns identity — token issuing, token verification, the request filter that
 * establishes a principal, the guard that acts on one — is this one. A reader looking for "where
 * does a user come from" had no reason to open a file about documents.
 *
 * <h2>Login with no password</h2>
 * A deliberate simplification described in the README: the project's scope is distributed data
 * consistency, not identity management. A full account mechanism would take time away from the core
 * problem while demonstrating nothing this project sets out to prove.
 *
 * <p>The consequence is stated rather than hidden, and it shapes another feature: "Anna" today and
 * "Anna" tomorrow are two different users, because each login mints a fresh identifier. That is why
 * sharing works by invite LINK rather than by naming a person — see the long note at the top of
 * {@code V5__invites.sql}.
 */
@RestController
@RequestMapping("/api")
public class LoginController {

    /**
     * The upper bound on the length of a name visible to others.
     *
     * <p>The name goes into the token, into the database and — most importantly — into <b>every
     * presence broadcast</b>, that is, a message sent many times a second to every participant in
     * the document. Without a limit, one user with a megabyte-long name loads everyone else's
     * connection. 64 characters accommodate any real name.
     *
     * <p>Enforced by TRUNCATION rather than by refusal, and that is the one place in this
     * application where an over-long field is not a 400. The reason is what the endpoint is for: it
     * is the first request a visitor makes, it has no prior state to protect, and refusing it means
     * the product does not open. A name quietly shortened to 64 characters is a worse experience
     * than a name; a login screen that rejects you is not an experience at all.
     */
    private static final int MAX_DISPLAY_NAME_LENGTH = 64;

    /**
     * @param displayName the name others will see. The {@code @Size} bound here is not the same
     *                    rule as {@link #MAX_DISPLAY_NAME_LENGTH} and does not replace it: this one
     *                    refuses a body that is absurd — a megabyte of text posted at an
     *                    unauthenticated endpoint — while the constant above shortens a name that
     *                    is merely long. Two bounds because there are two failures: one is abuse,
     *                    the other is a person with a long name.
     */
    public record LoginRequest(@Size(max = 4096) String displayName) {
    }

    public record LoginResponse(String token, String userId, String displayName, String color) {
    }

    private final TokenService tokens;
    private final UserDirectory users;

    public LoginController(TokenService tokens, UserDirectory users) {
        this.tokens = tokens;
        this.users = users;
    }

    @PostMapping("/login")
    @PublicEndpoint("Issues identity. There is nothing to authorize against yet — this is the "
        + "endpoint that produces the token every other endpoint checks.")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        String displayName = request.displayName() == null || request.displayName().isBlank()
            ? "Guest" : request.displayName().trim();
        if (displayName.length() > MAX_DISPLAY_NAME_LENGTH) {
            displayName = displayName.substring(0, MAX_DISPLAY_NAME_LENGTH);
        }

        TokenService.Principal principal = users.create(displayName);
        return ResponseEntity.ok(new LoginResponse(
            tokens.issue(principal),
            principal.userId(), principal.displayName(), principal.color()));
    }
}
