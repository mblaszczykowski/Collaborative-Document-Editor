package dev.collabedit.app.auth;

import dev.collabedit.app.config.CollabEditProperties;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;

/**
 * The identity providers this deployment can actually complete a sign-in with — <b>built here
 * rather than declared in {@code application.yml}</b>.
 *
 * <h2>Why not the standard {@code spring.security.oauth2.client.registration} block</h2>
 * Because there is no way to write "this provider, only if somebody configured it". The obvious
 * attempt is a placeholder with an empty default:
 *
 * <pre>
 *   registration:
 *     google:
 *       client-id: ${GOOGLE_CLIENT_ID:}
 * </pre>
 *
 * <p>and it does not start. Spring Boot validates the block eagerly and refuses with <i>"Client id
 * of registration 'google' must not be empty"</i> — so a clone with no OAuth application of its own
 * would not boot at all, which is a worse first experience than not offering the button. Leaving the
 * block out entirely instead means a contributor has to know the full
 * {@code SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_CLIENT_ID} incantation to add one.
 *
 * <p>So the registrations are assembled from {@code collabedit.auth.*}, which has an ordinary
 * default of "absent", and a provider joins the list when its client id is present. The same
 * property is what {@code CollabEditProperties.Auth.providers()} derives the login screen's buttons
 * from, so the screen and the filter chain cannot disagree about what exists.
 *
 * <h2>Why the endpoints are not written out</h2>
 * {@link CommonOAuth2Provider} carries Google's and GitHub's authorization, token and user-info
 * URLs, the user-name attribute and the default scopes. Copying them into this repository would be
 * copying constants that belong to somebody else and change without telling us — and getting one
 * wrong produces an authentication that half works.
 *
 * <p>The exception is Google's scope list, set explicitly to include {@code openid}: that is the
 * scope which makes the exchange OpenID Connect rather than plain OAuth, and therefore the reason
 * an id token with a {@code sub} claim comes back at all. {@code sub} is the identity this
 * application stores ({@link UserDirectory#upsertFederated}), so the scope is load-bearing rather
 * than decorative.
 */
@Configuration
public class ClientRegistrations {

    private static final Logger log = LoggerFactory.getLogger(ClientRegistrations.class);

    /**
     * @return the configured registrations, or an EMPTY repository when there are none.
     *     <p>Empty rather than absent, and that is forced rather than chosen: merely having the
     *     starter on the classpath makes Spring build an {@code OAuth2AuthorizedClientManager},
     *     which requires this bean to exist — so returning {@code null} trades "client id must not
     *     be empty" for "no qualifying bean of type ClientRegistrationRepository", and the
     *     application still does not start.
     *     <p>An {@code InMemoryClientRegistrationRepository} cannot be empty either, hence the
     *     lambda. Nothing ever asks it a question: {@code SecurityConfig} only installs
     *     {@code oauth2Login} when at least one provider is configured, so with none the entry
     *     point that would look a registration up is not in the chain.
     */
    @Bean
    public ClientRegistrationRepository clientRegistrationRepository(
            CollabEditProperties properties) {
        CollabEditProperties.Auth auth = properties.auth();
        List<ClientRegistration> registrations = new ArrayList<>(2);

        if (auth.google().configured()) {
            registrations.add(CommonOAuth2Provider.GOOGLE
                .getBuilder("google")
                .clientId(auth.google().clientId())
                .clientSecret(auth.google().clientSecret())
                // openid, explicitly — see the class javadoc. CommonOAuth2Provider.GOOGLE already
                // asks for it, and it is restated because removing it would silently turn this into
                // plain OAuth and take the `sub` claim with it.
                .scope("openid", "profile", "email")
                .build());
        }
        if (auth.github().configured()) {
            // GitHub is NOT an OpenID Connect provider: no id token, no `sub`. Spring treats it as
            // a plain OAuth2 user-info provider, and OidcLoginSuccess falls back to the numeric
            // `id` attribute for the subject. Worth knowing before a third provider is added on the
            // assumption that `sub` is always there.
            registrations.add(CommonOAuth2Provider.GITHUB
                .getBuilder("github")
                .clientId(auth.github().clientId())
                .clientSecret(auth.github().clientSecret())
                .build());
        }

        if (registrations.isEmpty()) {
            log.info("No identity provider is configured. Federated sign-in is unavailable; "
                + "set GOOGLE_CLIENT_ID/SECRET or GITHUB_CLIENT_ID/SECRET to enable it.");
            // Knows about nothing, and is asked about nothing. See the javadoc above for why this
            // is a lambda and not null.
            return registrationId -> null;
        }
        log.info("Federated sign-in available via {}", auth.providers());
        return new InMemoryClientRegistrationRepository(registrations);
    }
}
