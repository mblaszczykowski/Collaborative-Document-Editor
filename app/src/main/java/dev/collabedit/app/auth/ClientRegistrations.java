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

@Configuration
public class ClientRegistrations {
    private static final Logger log = LoggerFactory.getLogger(ClientRegistrations.class);

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
                .scope("openid", "profile", "email")
                .build());
        }
        if (auth.github().configured()) {
            registrations.add(CommonOAuth2Provider.GITHUB
                .getBuilder("github")
                .clientId(auth.github().clientId())
                .clientSecret(auth.github().clientSecret())
                .build());
        }

        if (registrations.isEmpty()) {
            log.info("No identity provider is configured. Federated sign-in is unavailable; "
                + "set GOOGLE_CLIENT_ID/SECRET or GITHUB_CLIENT_ID/SECRET to enable it.");
            return registrationId -> null;
        }
        log.info("Federated sign-in available via {}", auth.providers());
        return new InMemoryClientRegistrationRepository(registrations);
    }
}
