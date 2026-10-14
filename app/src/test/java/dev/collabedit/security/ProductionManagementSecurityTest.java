package dev.collabedit.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.collabedit.app.auth.OidcLoginFailure;
import dev.collabedit.app.auth.OidcLoginSuccess;
import dev.collabedit.app.auth.SecurityConfig;
import dev.collabedit.app.config.CollabEditProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringJUnitConfig(ProductionManagementSecurityTest.Setup.class)
class ProductionManagementSecurityTest {
    @Autowired SecurityFilterChain chain;
    @Autowired MockEnvironment environment;
    private MockMvc mvc;

    @Configuration
    @EnableWebSecurity
    @Import(SecurityConfig.class)
    static class Setup {
        @Bean MockEnvironment environment() {
            MockEnvironment environment = new MockEnvironment();
            environment.setActiveProfiles("production");
            return environment.withProperty("server.port", "8090").withProperty("management.server.port", "8091");
        }
        @Bean CollabEditProperties properties() { return new CollabEditProperties(null, null, null, null, null); }
        @Bean ClientRegistrationRepository registrations() { return id -> null; }
        @Bean OidcLoginSuccess success(CollabEditProperties properties) { return new OidcLoginSuccess(null, null, properties); }
        @Bean OidcLoginFailure failure(CollabEditProperties properties) { return new OidcLoginFailure(properties); }
    }

    @RestController
    static class Endpoints {
        @GetMapping("/actuator/metrics") String metrics() { return "metrics"; }
        @GetMapping("/actuator/health") String health() { return "healthy"; }
    }

    @BeforeEach
    void createMvc() {
        environment.setProperty("management.server.port", "8091");
        mvc = MockMvcBuilders.standaloneSetup(new Endpoints()).addFilters(new FilterChainProxy(chain)).build();
    }

    @Test
    void metricsAreDeniedOnApplicationPortAndAllowedOnPrivateManagementPort() throws Exception {
        mvc.perform(get("/actuator/metrics").servletPath("/actuator/metrics")
            .with(request -> { request.setLocalPort(8090); return request; })).andExpect(status().isForbidden());
        mvc.perform(get("/actuator/metrics").servletPath("/actuator/metrics")
            .with(request -> { request.setLocalPort(8091); return request; })).andExpect(status().isOk());
        mvc.perform(get("/actuator/health").servletPath("/actuator/health")
            .with(request -> { request.setLocalPort(8090); return request; })).andExpect(status().isOk());
    }

    @Test
    void forwardedPortCannotGrantManagementAccessAndSharedPortCannotBecomePrivate() throws Exception {
        mvc.perform(get("/actuator/metrics").servletPath("/actuator/metrics").header("X-Forwarded-Port", "8091")
            .with(request -> { request.setLocalPort(8090); request.setServerPort(8091); return request; }))
            .andExpect(status().isForbidden());
        environment.setProperty("management.server.port", "8090");
        mvc.perform(get("/actuator/metrics").servletPath("/actuator/metrics")
            .with(request -> { request.setLocalPort(8090); return request; })).andExpect(status().isForbidden());
    }
}
