package dev.collabedit.app.config;

import dev.collabedit.app.api.DocumentSnapshotController;
import dev.collabedit.app.auth.DocumentAccessGuard;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
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
    private final CollabEditProperties properties;

    public WebMvcConfig(DocumentAccessGuard guard, CollabEditProperties properties) {
        this.guard = guard;
        this.properties = properties;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(guard).addPathPatterns("/api/**");
    }

    /**
     * The allowed browser origins for REST — <b>from configuration</b>, and the same list the
     * WebSocket handshake uses.
     *
     * <h2>Why this is not six annotations any more</h2>
     * Six controllers each carried
     * {@code @CrossOrigin(originPatterns = {"http://localhost:*", "http://127.0.0.1:*"})}, pinned
     * in the source. {@code application.yml} documents exactly that mistake for the other
     * transport: pinning the patterns in {@code WebSocketConfig} meant a hosted deployment failed
     * at the handshake with no way to fix it short of a rebuild. That was fixed for the socket and
     * not for REST, so a hosted instance got a working WebSocket and browser-blocked REST — which
     * surfaces as an unexplained CORS error rather than as anything pointing at a setting.
     *
     * <p>One list, one place, and adding a controller no longer means remembering an annotation.
     * The default stays restrictive for the reason stated in {@code application.yml}: an allow-list
     * defaulting to {@code *} works everywhere, including everywhere it should not.
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
            .allowedOriginPatterns(
                properties.websocket().allowedOriginPatterns().toArray(String[]::new))
            .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
            .allowedHeaders("*")
            // A browser hides every response header a page did not ask for, and the snapshot
            // endpoint's sequence number travels in one. It was declared on that controller's own
            // annotation; with the annotations gone it has to be declared here or the client reads
            // a snapshot it cannot label.
            .exposedHeaders(DocumentSnapshotController.THROUGH_SEQ_HEADER);
    }
}
