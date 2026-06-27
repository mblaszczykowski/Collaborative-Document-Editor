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

/**
 * The end of a sign-in: turn what the provider said into a row of ours, and hand the browser a
 * single-use code for it.
 *
 * <h2>Why this does not simply return the token</h2>
 * Because the browser is not here. The provider redirected the USER AGENT to this server, and the
 * thing that needs the token is the single-page application on another origin. The only channel
 * between them is a redirect, and a redirect target is not a private place — it lands in history,
 * in the {@code Referer} of the next request the page makes, and in anything reading
 * {@code location}. So what travels is a code that expires in a minute and dies on first use, and
 * the token is fetched over POST. See {@link LoginCodeStore}.
 *
 * <h2>What is taken from the provider, and what is not</h2>
 * The {@code sub} claim and the issuer's registration id together are the identity — see
 * {@link UserDirectory#upsertFederated}. The name is display only. The email is stored and not used
 * to identify anybody, because an email can be reassigned and a subject cannot.
 *
 * <p>Nothing else is kept. This application has no use for a provider access token, so it does not
 * hold one: an unused credential in a database is a liability with no upside, and the OpenID
 * Connect exchange has already told us the only thing being asked — who this is.
 */
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
            // Not reachable through a conformant provider — `sub` is mandatory in OpenID Connect
            // and GitHub always returns `id`. Refused rather than substituted, because the
            // alternatives are inventing an identity or keying on the email, and this file's whole
            // job is to not do the second one.
            log.warn("Provider {} returned no subject claim; refusing the sign-in", provider);
            response.sendRedirect(appUrlWith("error", "no-subject"));
            return;
        }

        String displayName = displayNameOf(principal, subject);
        String email = claim(principal, "email");

        TokenService.Principal identity =
            users.upsertFederated(provider, subject, displayName, email);
        String code = codes.issue(identity.userId());

        log.info("Signed in {} via {}", identity.userId(), provider);
        response.sendRedirect(appUrlWith("code", code));
    }

    /**
     * The provider's stable identifier for this account.
     *
     * <p>{@code sub} for anything speaking OpenID Connect. GitHub's OAuth endpoint is not an OIDC
     * provider and returns {@code id} instead, which is why this looks for both rather than
     * assuming — the alternative is a second success handler for one field.
     */
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

    /**
     * Something to call this person in the document.
     *
     * <p>Falls back through the fields providers actually populate, and ends at the subject rather
     * than at "Anonymous": a name that is unique is worse to read than a name that is shared, and a
     * presence list of four identical placeholders is what "Anonymous" produces.
     */
    private static String displayNameOf(OAuth2User principal, String subject) {
        for (String attribute : new String[] {"name", "login", "preferred_username", "email"}) {
            String value = claim(principal, attribute);
            if (value != null && !value.isBlank()) {
                // Not shortened here. It used to be, with a `substring(0, 64)` that splits a
                // surrogate pair when the 64th unit is half of one — and emoji in a provider's
                // display name is ordinary. The bound belongs to UserDirectory, which stores the
                // name and now applies it for every caller.
                return value;
            }
        }
        return "User " + subject.substring(0, Math.min(8, subject.length()));
    }

    private static String claim(OAuth2User principal, String name) {
        Object value = principal.getAttributes().get(name);
        return value == null ? null : String.valueOf(value);
    }

    /**
     * The application's address with one parameter attached.
     *
     * <p>Built from configuration and never from the request. Taking it from a {@code redirect_uri}
     * the caller supplied is how an authentication flow becomes an open redirect — the one place a
     * forwarding bug hands somebody else's freshly minted credential to an attacker.
     */
    private String appUrlWith(String parameter, String value) {
        String base = properties.auth().appUrl();
        String separator = base.contains("?") ? "&" : "?";
        return base + separator + parameter + "="
            + URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
