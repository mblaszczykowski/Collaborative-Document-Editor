package dev.collabedit.app.auth;

import dev.collabedit.app.config.CollabEditProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class LoginController {
    public record LoginRequest(@Size(max = 4096) String displayName) {
    }

    public record LoginResponse(String token, String userId, String displayName, String color) {
    }

    private final TokenService tokens;
    private final UserDirectory users;
    private final LoginCodeStore codes;
    private final CollabEditProperties properties;

    public LoginController(TokenService tokens, UserDirectory users, LoginCodeStore codes,
                           CollabEditProperties properties) {
        this.tokens = tokens;
        this.users = users;
        this.codes = codes;
        this.properties = properties;
    }

    public record AuthOptions(List<String> providers, boolean devLoginEnabled) {
    }

    @GetMapping("/auth/options")
    @PublicEndpoint("Says how to sign in. Answering it requires no identity, which is the point - "
        + "it is what a visitor reads before they have one.")
    public ResponseEntity<AuthOptions> options() {
        return ResponseEntity.ok(new AuthOptions(
            properties.auth().providers(), properties.auth().devLoginEnabled()));
    }

    public record SessionRequest(@Size(max = 200) String code) {
    }

    @PostMapping("/session")
    @PublicEndpoint("Turns a completed sign-in into a token. The code IS the authorization; there "
        + "is no identity yet to check it against.")
    public ResponseEntity<LoginResponse> session(@Valid @RequestBody SessionRequest request) {
        return codes.redeem(request.code())
            .flatMap(users::find)
            .map(principal -> ResponseEntity.ok(new LoginResponse(
                tokens.issue(principal),
                principal.userId(), principal.displayName(), principal.color())))
            .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
    }

    @PostMapping("/login")
    @PublicEndpoint("Issues identity. There is nothing to authorize against yet - this is the "
        + "endpoint that produces the token every other endpoint checks.")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        if (!properties.auth().devLoginEnabled()) {
            return ResponseEntity.notFound().build();
        }
        String displayName = request.displayName() == null || request.displayName().isBlank()
            ? "Guest" : request.displayName().trim();

        TokenService.Principal principal = users.create(displayName);
        return ResponseEntity.ok(new LoginResponse(
            tokens.issue(principal),
            principal.userId(), principal.displayName(), principal.color()));
    }
}
