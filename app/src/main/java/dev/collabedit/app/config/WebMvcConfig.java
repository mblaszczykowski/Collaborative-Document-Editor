package dev.collabedit.app.config;

import dev.collabedit.app.api.DocumentSnapshotController;
import dev.collabedit.app.auth.DocumentAccessGuard;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

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

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
            .allowedOriginPatterns(
                properties.websocket().allowedOriginPatterns().toArray(String[]::new))
            .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
            .allowedHeaders("*")
            .exposedHeaders(DocumentSnapshotController.THROUGH_SEQ_HEADER);
    }
}
