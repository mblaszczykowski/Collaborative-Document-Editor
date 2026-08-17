package dev.collabedit.app.auth;

import dev.collabedit.app.config.CollabEditProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

@Component
public class OidcLoginSuccess implements AuthenticationSuccessHandler {
    private static final Logger log = LoggerFactory.getLogger(OidcLoginSuccess.class);

    private final UserDirectory users;
    private final LoginCodeStore codes;
    private final CollabEditProperties properties;

    public OidcLoginSuccess(UserDirectory users, LoginCodeStore codes,
                            CollabEditProperties properties) {
        this.users = users;
        this.codes = codes;
        this.properties = properties;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        OAuth2AuthenticationToken token = (OAuth2AuthenticationToken) authentication;
        String provider = token.getAuthorizedClientRegistrationId();
        OAuth2User principal = token.getPrincipal();

        String subject = subjectOf(principal);
        if (subject == null) {
            log.warn("Provider {} returned no subject claim; refusing the sign-in", provider);
            response.sendRedirect(appUrlWith("error", "no-subject"));
            return;
        }

        String displayName = displayNameOf(principal, subject);
        String email = claim(principal, "email");

        String code;
        TokenService.Principal identity;
        try {
            identity = users.upsertFederated(provider, subject, displayName, email);
            code = codes.issue(identity.userId());
        } catch (RuntimeException e) {
            log.error("Could not complete the sign-in for provider {}", provider, e);
            response.sendRedirect(appUrlWith("error", "sign-in-failed"));
            return;
        }

        log.info("Signed in {} via {}", identity.userId(), provider);
        response.sendRedirect(appUrlWith("code", code));
    }

    private static String subjectOf(OAuth2User principal) {
        if (principal instanceof OidcUser oidc && oidc.getSubject() != null) {
            return oidc.getSubject();
        }
        String sub = claim(principal, "sub");
        if (sub != null) {
            return sub;
        }
        Object githubId = principal.getAttributes().get("id");
        return githubId == null ? null : String.valueOf(githubId);
    }

    private static String displayNameOf(OAuth2User principal, String subject) {
        for (String attribute : new String[] {"name", "login", "preferred_username", "email"}) {
            String value = claim(principal, attribute);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "User " + subject.substring(0, Math.min(8, subject.length()));
    }

    private static String claim(OAuth2User principal, String name) {
        Object value = principal.getAttributes().get(name);
        return value == null ? null : String.valueOf(value);
    }

    private String appUrlWith(String parameter, String value) {
        String base = properties.auth().appUrl();
        String separator = base.contains("?") ? "&" : "?";
        return base + separator + parameter + "="
            + URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
