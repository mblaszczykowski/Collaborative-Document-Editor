package dev.collabedit.app.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Token tests. No Spring and no Docker — {@link TokenService} is a pure signing function, so
 * standing up a context would be a cost with no benefit.
 *
 * <table>
 *   <caption>Test -> failure mode</caption>
 *   <tr><th>Test</th><th>What it catches</th></tr>
 *   <tr><td>{@code displayNameWithSeparatorSurvivesRoundTrip}</td>
 *       <td>The field separator inside a user's name splitting the payload. The symptom is
 *           misleading: login returns a token, and then CONNECT rejects it as invalid — that is,
 *           "logged in, but the application does not work".</td></tr>
 *   <tr><td>{@code tamperedPayloadIsRejected}</td>
 *       <td>The signature not being checked, or being checked after the fields are read —
 *           identity substitution by editing the payload.</td></tr>
 *   <tr><td>{@code tokenFromAnotherSecretIsRejected}</td>
 *       <td>The key not entering the signature; a token from a foreign instance would be
 *           accepted.</td></tr>
 *   <tr><td>{@code malformedTokenIsRejectedWithoutThrowing}</td>
 *       <td>Garbage in the CONNECT header crashing the interceptor instead of producing a
 *           refusal. An exception here means a dropped connection rather than a legible error.</td></tr>
 * </table>
 */
class TokenServiceTest {

    private final TokenService tokens = new TokenService("test-secret-long-enough-to-be-accepted");

    @ParameterizedTest
    @ValueSource(strings = {
        "Anna|Nowak",
        "|",
        "a|b|c|9999999999",
        // Deliberately not translated: these are test DATA, not prose. Polish diacritics and
        // Japanese cover two- and three-byte UTF-8 sequences, which is the point.
        "Zażółć gęślą jaźń",
        "名前",
        "emoji 🙂",
        "  spaces  ",
    })
    @DisplayName("a display name round-trips regardless of its characters")
    void displayNameWithSeparatorSurvivesRoundTrip(String displayName) {
        var principal = new TokenService.Principal("user-1", displayName, "#4C5FD5");

        Optional<TokenService.Principal> verified = tokens.verify(tokens.issue(principal));

        assertThat(verified).contains(principal);
    }

    @Test
    @DisplayName("a token with a modified payload is rejected")
    void tamperedPayloadIsRejected() {
        String token = tokens.issue(new TokenService.Principal("user-1", "Anna", "#4C5FD5"));
        String tampered = "X" + token.substring(1);

        assertThat(tokens.verify(tampered)).isEmpty();
    }

    @Test
    @DisplayName("a token signed with a different key is rejected")
    void tokenFromAnotherSecretIsRejected() {
        String foreign = new TokenService("a-different-secret-long-enough-to-pass")
            .issue(new TokenService.Principal("user-1", "Anna", "#4C5FD5"));

        assertThat(tokens.verify(foreign)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", ".", "nodots", "a.b", "....", "%%%.%%%"})
    @DisplayName("a syntactically invalid token gives a refusal, not an exception")
    void malformedTokenIsRejectedWithoutThrowing(String token) {
        assertThat(tokens.verify(token)).isEmpty();
    }

    @Test
    @DisplayName("a missing token gives a refusal")
    void nullTokenIsRejected() {
        assertThat(tokens.verify(null)).isEmpty();
    }

    // ------------------------------------------------------------------
    // CP16 — the secret itself
    // ------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    @DisplayName("a server with no configured secret refuses to start")
    void missingSecretRefusesToStart(String secret) {
        // The failure mode this replaces was silent and total. The secret used to fall back to
        // a value checked into this repository, so a deployment that forgot the variable ran
        // on a key every reader of the repo knows — and since the token carries the user id,
        // knowing the key is enough to mint a token for ANY user. The server started, served,
        // and looked healthy the whole time.
        assertThatThrownBy(() -> new TokenService(secret))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("collabedit.auth.secret")
            // The message has to say what to do, not just what is wrong: this fires at startup,
            // usually for somebody deploying rather than the person who wrote the check.
            .hasMessageContaining("openssl rand");
    }

    @Test
    @DisplayName("a secret too short to be worth signing with is refused")
    void tooShortSecretRefusesToStart() {
        // HMAC-SHA256 accepts a key of any length, so without this check
        // `collabedit.auth.secret=x` starts a server whose tokens are trivially forgeable —
        // which passes the "is it set?" test while providing no security at all.
        assertThatThrownBy(() -> new TokenService("short"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("at least 32");
    }
}
