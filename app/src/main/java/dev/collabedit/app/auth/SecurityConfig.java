package dev.collabedit.app.auth;

import dev.collabedit.app.config.CollabEditProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {
    private final CollabEditProperties properties;
    private final org.springframework.core.env.Environment environment;

    public SecurityConfig(CollabEditProperties properties, org.springframework.core.env.Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    private boolean isPrivateManagementEndpoint(jakarta.servlet.http.HttpServletRequest request) {
        int managementPort = environment.getProperty("management.server.port", Integer.class, 0);
        int applicationPort = environment.getProperty("server.port", Integer.class, 8090);
        String path = request.getServletPath();
        return managementPort > 0 && managementPort != applicationPort
            && request.getLocalPort() == managementPort
            && (path.equals("/actuator") || path.startsWith("/actuator/"));
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           ClientRegistrationRepository registrations,
                                           OidcLoginSuccess oidcSuccess,
                                           OidcLoginFailure oidcFailure) throws Exception {
        http
            .authorizeHttpRequests(requests -> {
                if (environment.acceptsProfiles(org.springframework.core.env.Profiles.of("production"))) {
                    requests.requestMatchers(this::isPrivateManagementEndpoint).permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/actuator/**").denyAll();
                }
                requests.anyRequest().permitAll();
            })
            .csrf(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable);

        if (properties.auth().federatedLoginConfigured()) {
            http.oauth2Login(login -> login
                .authorizationEndpoint(endpoint -> endpoint.authorizationRequestResolver(
                    new AccountChooserResolver(registrations, "/oauth2/authorization")))
                .successHandler(oidcSuccess)
                .failureHandler(oidcFailure));
        }
        return http.build();
    }
}
