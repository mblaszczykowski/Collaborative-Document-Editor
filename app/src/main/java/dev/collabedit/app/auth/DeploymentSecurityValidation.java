package dev.collabedit.app.auth;

import dev.collabedit.app.config.CollabEditProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Fail deployment startup rather than silently accepting demonstration credentials. */
@Component
@Profile("production")
public class DeploymentSecurityValidation {
    public DeploymentSecurityValidation(CollabEditProperties properties,
                                        @Value("${collabedit.auth.secret:}") String secret) {
        if (properties.auth().devLoginEnabled()) {
            throw new IllegalStateException("Development login must be disabled in the production profile.");
        }
        if (secret == null || secret.isBlank()
            || secret.equals("local-development-secret-not-for-production")) {
            throw new IllegalStateException("Production requires an explicit private signing secret.");
        }
        if (!properties.auth().appUrl().startsWith("https://")) {
            throw new IllegalStateException("Production requires an HTTPS application URL.");
        }
    }
}
