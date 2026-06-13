package dev.collabedit.app.config;

import dev.collabedit.app.auth.DocumentAccessGuard;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Wires {@link DocumentAccessGuard} in front of every REST endpoint (CP16).
 *
 * <p>Scoped to {@code /api/**} rather than everything. Actuator health is what CI waits on
 * before starting the browser tests, and Spring Boot's error dispatch has no document to
 * authorize against — putting either behind a rule about documents would be enforcing a
 * question that does not apply.
 *
 * <p>That exclusion is also the reason the architecture test scopes itself to
 * {@code dev.collabedit.app}: the guard covers our endpoints, and the test proves the set of
 * "our endpoints" and the set of "endpoints the guard can see" are the same set.
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final DocumentAccessGuard guard;

    public WebMvcConfig(DocumentAccessGuard guard) {
        this.guard = guard;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(guard).addPathPatterns("/api/**");
    }
}
