package dev.collabedit.app.auth;

import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;

/**
 * Asks the provider to show its account chooser, so that signing out is followed by a real choice.
 *
 * <h2>The problem</h2>
 * Signing out of this application discards a token and nothing else — it cannot, and should not,
 * end the user's session at Google. So the next click on "Continue with Google" re-authenticated
 * the SAME account instantly, with no consent screen and no chooser. Two consequences, and the
 * second is the one that matters:
 *
 * <ul>
 *   <li>somebody with more than one account could not reach the other one; and</li>
 *   <li><b>on a shared computer, "Sign out" followed by "Continue with Google" handed the next
 *       person the previous person's account.</b> That is the exact scenario the account menu was
 *       built for, so the control's own justification was what it failed to deliver.</li>
 * </ul>
 *
 * <p>{@code prompt=select_account} is the parameter OpenID Connect defines for this: it tells the
 * provider to ask who is signing in, even when it already knows. It does not re-prompt for a
 * password and it does not weaken anything — the provider still decides.
 *
 * <h2>Why a resolver, and why not simply set the parameter on every registration</h2>
 * The parameter is per-provider, and only one of the two providers here understands it. GitHub's
 * OAuth endpoint is not an OpenID Connect provider and has no {@code prompt}; it would be an
 * unknown query parameter, and "probably ignored" is not something to rely on in the one flow whose
 * failure mode is being unable to sign in at all. So the customisation is applied by registration
 * id, which is a decision that needs to see the resolved request — hence wrapping the default
 * resolver rather than configuring it with a blind customiser.
 *
 * <p>The GitHub gap is real and is left open rather than papered over: a user wanting to switch
 * GitHub accounts has to do it at GitHub. Recorded in the README's limitations.
 */
final class AccountChooserResolver implements OAuth2AuthorizationRequestResolver {

    /** The registration ids whose providers understand {@code prompt}. */
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

    /**
     * @param resolved may be {@code null} — the delegate answers that for any request that is not a
     *     sign-in, which is most of them, and the contract requires passing it through untouched
     */
    private static OAuth2AuthorizationRequest chooserFor(OAuth2AuthorizationRequest resolved) {
        if (resolved == null) {
            return null;
        }
        Object registrationId = resolved.getAttribute(OAuth2ParameterNames.REGISTRATION_ID);
        if (!OIDC_PROVIDER.equals(registrationId)) {
            return resolved;
        }
        // Copied rather than mutated: the map on a built request is immutable, and `from` is the
        // supported way to produce a modified copy.
        Map<String, Object> parameters = new LinkedHashMap<>(resolved.getAdditionalParameters());
        parameters.put("prompt", "select_account");
        return OAuth2AuthorizationRequest.from(resolved)
            .additionalParameters(parameters)
            .build();
    }
}
