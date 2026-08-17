package dev.collabedit.app.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    @DisplayName("a server with no configured secret refuses to start")
    void missingSecretRefusesToStart(String secret) {
        assertThatThrownBy(() -> new TokenService(secret))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("collabedit.auth.secret")
            .hasMessageContaining("openssl rand");
    }

    @Test
    @DisplayName("a secret too short to be worth signing with is refused")
    void tooShortSecretRefusesToStart() {
        assertThatThrownBy(() -> new TokenService("short"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("at least 32");
    }
}
