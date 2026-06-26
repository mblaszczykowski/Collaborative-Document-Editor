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

/**
 * A sign-in that did not finish, returned to the application rather than to a Spring error page.
 *
 * <h2>Why this exists at all</h2>
 * The default renders an error at the API's origin. For a single-page application that is a dead
 * end: the person is left on a blank page at an address that is not the product, with a back button
 * that re-submits an authorization code which has already been spent. Sending them back to the
 * login screen with something to read is the difference between "that did not work, try again" and
 * "the application is broken".
 *
 * <p>The cases are ordinary rather than exotic — closing the provider's consent screen is one, and
 * a clock far enough out of step to fail the nonce check is another.
 *
 * <h2>Why the reason is a fixed word</h2>
 * The redirect carries {@code error=sign-in-failed} and nothing else. {@code AuthenticationException}
 * messages describe our side of the exchange — a token endpoint that answered badly, a claim that
 * did not validate, an issuer mismatch — and putting them in a URL tells whoever sent the user here
 * about the deployment rather than about their mistake. The detail goes to the log, where the person
 * running this can read it and the person signing in cannot.
 */
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
