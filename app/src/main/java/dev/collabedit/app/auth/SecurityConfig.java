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

    public SecurityConfig(CollabEditProperties properties) {
        this.properties = properties;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           ClientRegistrationRepository registrations,
                                           OidcLoginSuccess oidcSuccess,
                                           OidcLoginFailure oidcFailure) throws Exception {
        http
            .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
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
