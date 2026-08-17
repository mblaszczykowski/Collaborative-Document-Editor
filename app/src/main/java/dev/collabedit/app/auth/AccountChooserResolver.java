package dev.collabedit.app.auth;

import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;

final class AccountChooserResolver implements OAuth2AuthorizationRequestResolver {
    private static final String OIDC_PROVIDER = "google";

    private final DefaultOAuth2AuthorizationRequestResolver delegate;

    AccountChooserResolver(ClientRegistrationRepository registrations, String authorizationBaseUri) {
        this.delegate =
            new DefaultOAuth2AuthorizationRequestResolver(registrations, authorizationBaseUri);
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
        return chooserFor(delegate.resolve(request));
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String registrationId) {
        return chooserFor(delegate.resolve(request, registrationId));
    }

    private static OAuth2AuthorizationRequest chooserFor(OAuth2AuthorizationRequest resolved) {
        if (resolved == null) {
            return null;
        }
        Object registrationId = resolved.getAttribute(OAuth2ParameterNames.REGISTRATION_ID);
        if (!OIDC_PROVIDER.equals(registrationId)) {
            return resolved;
        }
        Map<String, Object> parameters = new LinkedHashMap<>(resolved.getAdditionalParameters());
        parameters.put("prompt", "select_account");
        return OAuth2AuthorizationRequest.from(resolved)
            .additionalParameters(parameters)
            .build();
    }
}
