package dev.collabedit.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = {
    "collabedit.auth.google.client-id=test-client-id.apps.googleusercontent.com",
    "collabedit.auth.google.client-secret=test-client-secret",
})
@DisplayName("The provider is asked which account, not assumed")
class AccountChooserIT extends IntegrationTestBase {
    @LocalServerPort
    int port;

    private String authorizationRedirect(String provider) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/oauth2/authorization/" + provider))
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build();
        HttpResponse<String> response = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
            .send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode())
            .as("the authorization endpoint did not redirect, so there is nothing to inspect")
            .isEqualTo(302);
        return response.headers().firstValue("Location").orElseThrow();
    }

    @Test
    @DisplayName("Google is asked to show its account chooser")
    void googleIsAskedForTheChooser() throws Exception {
        String redirect = authorizationRedirect("google");

        assertThat(redirect)
            .as("""
                Without this the provider signs the user straight back in as whoever it already \
                knows. Sign out on a shared computer then becomes a control that appears to work \
                and hands the next person the account that was just signed out of.""")
            .contains("prompt=select_account");
    }

    @Test
    @DisplayName("rebuilding the request keeps everything the flow depends on")
    void theRestOfTheRequestSurvives() throws Exception {
        String redirect = authorizationRedirect("google");

        assertThat(redirect).startsWith("https://accounts.google.com/o/oauth2/v2/auth");
        assertThat(redirect)
            .as("the authorization-code flow - `token` here would be the deprecated implicit flow")
            .contains("response_type=code");
        assertThat(redirect)
            .as("""
                The `state` parameter is gone. It is the only thing binding the redirect back to \
                the browser that started the flow, and losing it is invisible: a sign-in with no \
                state still completes, so nothing else here would fail.""")
            .contains("state=");
        assertThat(redirect)
            .as("`openid` is what makes this OpenID Connect rather than plain OAuth, and therefore "
                + "the reason a `sub` claim comes back at all - UserDirectory stores it as identity")
            .contains("scope=openid");
        assertThat(redirect).contains("client_id=test-client-id.apps.googleusercontent.com");
    }
}
