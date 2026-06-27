package dev.collabedit.app.auth;

import dev.collabedit.app.config.CollabEditProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Spring Security, present for <b>authentication only</b>, and switched off for everything else.
 *
 * <h2>Why this class is mostly a list of things not to do</h2>
 * The structural-authorization decision rejected Spring Security and gave a reason that still
 * holds: authorization here is per endpoint and per STOMP frame, expressed as a capability an
 * endpoint declares and a role the guard resolves, and a second matcher-based rule set would mean
 * two systems deciding the same question. When a request is refused, exactly one thing should be
 * able to have refused it.
 *
 * <p>But authentication is a different problem, and OpenID Connect is not something to hand-roll:
 * the authorization-code exchange, the provider's key set, issuer and audience validation, the
 * nonce, the state parameter — all cryptography against a specification, all solved. Bringing in
 * {@code spring-boot-starter-oauth2-client} for that also brings the filter chain, whose default is
 * to require authentication for every request.
 *
 * <p>So this configuration's job is to accept the login machinery and decline the rest:
 * {@code permitAll} on every request, and {@link DocumentAccessGuard} and
 * {@code DocumentAccessInterceptor} go on being the only things that refuse anybody.
 *
 * <h2>What each line turns off, and why</h2>
 * <ul>
 *   <li><b>CSRF</b> — the API authenticates with a {@code Bearer} header that JavaScript must
 *       attach deliberately, never with an ambient cookie, so there is no cross-site request to
 *       forge. Leaving it on would reject every {@code POST} from the browser application for a
 *       threat this design does not have. The one cookie in play is the session Spring keeps
 *       between the redirect out and the redirect back, and it authorises nothing on its own.</li>
 *   <li><b>HTTP Basic and form login</b> — two more ways to authenticate that nothing uses, and
 *       both would answer an unauthenticated request with a prompt instead of letting it through
 *       to the guard, which answers 404 on purpose so a caller cannot learn what exists.</li>
 *   <li><b>Logout</b> — Spring's version clears its session, which is not where the identity
 *       lives. The session token is the credential and the browser discards it; a second logout
 *       that clears something else would be a control that looks like it works.</li>
 * </ul>
 *
 * <h2>When no provider is configured</h2>
 * With no client registrations, {@code oauth2Login} has nothing to offer and Spring's own
 * auto-configuration leaves the filter out — so the chain below still applies, the application
 * starts, and the only way in is the development login (which is off unless explicitly enabled;
 * see {@link CollabEditProperties.Auth}). That is the state a fresh clone is in, and it is why this
 * refuses to guess: an application that silently falls back to "anyone may name themselves" is the
 * thing federated identity was added to stop.
 */
@Configuration
public class SecurityConfig {

    private final CollabEditProperties properties;

    public SecurityConfig(CollabEditProperties properties) {
        this.properties = properties;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           ClientRegistrationRepository registrations,
                                           OidcLoginSuccess oidcSuccess,
                                           OidcLoginFailure oidcFailure) throws Exception {
        http
            // Every request passes. See the class javadoc: the guard is the authority, and two
            // authorities would make "why was I refused" unanswerable.
            .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
            .csrf(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable);

        // Only when something is configured. Calling oauth2Login() with no registrations installs
        // a login page and an entry point for providers that do not exist, and the first symptom is
        // a redirect loop rather than a message.
        if (properties.auth().federatedLoginConfigured()) {
            http.oauth2Login(login -> login
                // Ask the provider to show its account chooser. Signing out here cannot end the
                // session at Google, so without this the next sign-in silently returned the same
                // person — which on a shared computer means the previous user's account. See
                // AccountChooserResolver.
                .authorizationEndpoint(endpoint -> endpoint.authorizationRequestResolver(
                    new AccountChooserResolver(registrations, "/oauth2/authorization")))
                // The browser application never sees this callback: the handler finishes the
                // sign-in and redirects to the app with a single-use code. See OidcLoginSuccess.
                .successHandler(oidcSuccess)
                // A refusal goes back to the app as well, with a reason it can render. The default
                // is a Spring error page on the API's origin, which for a single-page application
                // is a dead end — the user is left on a white page at the wrong address.
                .failureHandler(oidcFailure));
        }
        return http.build();
    }
}
