package dev.collabedit.app.auth;

import dev.collabedit.app.config.CollabEditProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

@Component
public class OidcLoginFailure implements AuthenticationFailureHandler {
    private static final Logger log = LoggerFactory.getLogger(OidcLoginFailure.class);

    private final CollabEditProperties properties;

    public OidcLoginFailure(CollabEditProperties properties) {
        this.properties = properties;
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                        AuthenticationException exception) throws IOException {
        log.warn("A federated sign-in failed: {}", exception.toString());

        String base = properties.auth().appUrl();
        response.sendRedirect(base + (base.contains("?") ? "&" : "?") + "error=sign-in-failed");
    }
}
