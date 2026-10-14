package dev.collabedit.app.auth;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.collabedit.app.config.CollabEditProperties;
import org.junit.jupiter.api.Test;

class DeploymentSecurityValidationTest {
    private static CollabEditProperties configuration(boolean devLogin, String appUrl) {
        return new CollabEditProperties(null, null, null, null,
            new CollabEditProperties.Auth(devLogin, appUrl, null, null));
    }

    @Test
    void exposedDeploymentRejectsDevelopmentLoginAndDemoSigningSecret() {
        assertThatThrownBy(() -> new DeploymentSecurityValidation(
            configuration(true, "https://editor.example"), "private-test-secret"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("Development login");
        assertThatThrownBy(() -> new DeploymentSecurityValidation(
            configuration(false, "https://editor.example"), "local-development-secret-not-for-production"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("private signing secret");
    }

    @Test
    void productionRequiresHttpsAndAcceptsPrivateCredentials() {
        assertThatThrownBy(() -> new DeploymentSecurityValidation(
            configuration(false, "http://editor.example"), "private-test-secret"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("HTTPS");
        assertThatCode(() -> new DeploymentSecurityValidation(
            configuration(false, "https://editor.example"), "private-test-secret"))
            .doesNotThrowAnyException();
    }
}
