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

@DisplayName("The development login is not served unless it is asked for")
class DevLoginDisabledIT extends IntegrationTestBase {
    @LocalServerPort
    int port;

    private HttpResponse<String> post(String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + path))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(10))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + path))
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("posting a name does not produce an identity")
    void theDevLoginIsRefusedByDefault() throws Exception {
        HttpResponse<String> refused = post("/api/login", "{\"displayName\":\"A Stranger\"}");

        assertThat(refused.statusCode())
            .as("""
                A name was enough to obtain a session. That is the mechanism federated identity \
                replaced, and while it answers, every role in this system rests on a claim nobody \
                checked - including `link` visibility, which grants EDITOR to anybody signed in.""")
            .isEqualTo(404);
        assertThat(refused.body())
            .as("the refusal leaked a token anyway")
            .doesNotContain("token");
    }

    @Test
    @DisplayName("404 rather than 403, so a closed door is not advertised")
    void theRefusalDoesNotAdvertiseTheEndpoint() throws Exception {
        HttpResponse<String> refused = post("/api/login", "{\"displayName\":\"A Stranger\"}");

        assertThat(refused.statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("the login screen is told there is no way in, rather than being left to guess")
    void theOptionsEndpointSaysSo() throws Exception {
        HttpResponse<String> options = get("/api/auth/options");

        assertThat(options.statusCode())
            .as("a visitor must be able to ask how to sign in before they have an identity")
            .isEqualTo(200);
        assertThat(options.body())
            .as("""
                The client builds its screen from this. Saying the development login is available \
                when it is not produces a button that answers 404, and saying a provider is \
                configured when it is not produces a redirect loop - which is worse, because it \
                has no error to show.""")
            .contains("\"devLoginEnabled\":false")
            .contains("\"providers\":[]");
    }
}
