package dev.collabedit.app.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Testy tokenu. Bez Springa i bez Dockera — {@link TokenService} to czysta funkcja
 * podpisu, więc podnoszenie kontekstu byłoby kosztem bez korzyści.
 *
 * <table>
 *   <caption>Test → failure mode</caption>
 *   <tr><th>Test</th><th>Co wyłapuje</th></tr>
 *   <tr><td>{@code displayNameWithSeparatorSurvivesRoundTrip}</td>
 *       <td>Separator pól w nazwie użytkownika rozbija ładunek. Objaw jest mylący:
 *           logowanie zwraca token, po czym CONNECT odrzuca go jako nieważny —
 *           czyli „zalogowany, ale aplikacja nie działa".</td></tr>
 *   <tr><td>{@code tamperedPayloadIsRejected}</td>
 *       <td>Podpis nie jest sprawdzany albo jest sprawdzany po odczycie pól —
 *           podmiana tożsamości przez edycję ładunku.</td></tr>
 *   <tr><td>{@code tokenFromAnotherSecretIsRejected}</td>
 *       <td>Klucz nie wchodzi do podpisu; token z obcej instancji byłby akceptowany.</td></tr>
 *   <tr><td>{@code malformedTokenIsRejectedWithoutThrowing}</td>
 *       <td>Śmieci w nagłówku CONNECT wywalają interceptor zamiast dać odmowę.
 *           Wyjątek w tym miejscu to zerwane połączenie zamiast czytelnego błędu.</td></tr>
 * </table>
 */
class TokenServiceTest {

    private final TokenService tokens = new TokenService("test-secret-long-enough-to-be-accepted");

    @ParameterizedTest
    @ValueSource(strings = {
        "Anna|Nowak",
        "|",
        "a|b|c|9999999999",
        "Zażółć gęślą jaźń",
        "名前",
        "emoji 🙂",
        "  spacje  ",
    })
    @DisplayName("nazwa użytkownika przechodzi w obie strony niezależnie od znaków")
    void displayNameWithSeparatorSurvivesRoundTrip(String displayName) {
        var principal = new TokenService.Principal("user-1", displayName, "#4C5FD5");

        Optional<TokenService.Principal> verified = tokens.verify(tokens.issue(principal));

        assertThat(verified).contains(principal);
    }

    @Test
    @DisplayName("token ze zmienionym ładunkiem jest odrzucony")
    void tamperedPayloadIsRejected() {
        String token = tokens.issue(new TokenService.Principal("user-1", "Anna", "#4C5FD5"));
        String tampered = "X" + token.substring(1);

        assertThat(tokens.verify(tampered)).isEmpty();
    }

    @Test
    @DisplayName("token podpisany innym kluczem jest odrzucony")
    void tokenFromAnotherSecretIsRejected() {
        String foreign = new TokenService("a-different-secret-long-enough-to-pass")
            .issue(new TokenService.Principal("user-1", "Anna", "#4C5FD5"));

        assertThat(tokens.verify(foreign)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", ".", "bezkropki", "a.b", "....", "%%%.%%%"})
    @DisplayName("token niepoprawny składniowo daje odmowę, a nie wyjątek")
    void malformedTokenIsRejectedWithoutThrowing(String token) {
        assertThat(tokens.verify(token)).isEmpty();
    }

    @Test
    @DisplayName("brak tokenu daje odmowę")
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
