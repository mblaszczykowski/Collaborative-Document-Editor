package dev.collabedit.app.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authentication of REST requests (CP12).
 *
 * <h2>Why only now</h2>
 * Up to CP11 REST exposed the document list, document creation and content reads — <b>with no
 * access control at all</b>. With one shared document that was consistent with everything else: the
 * model read "anyone may do anything". Since CP11 {@code GET /api/documents/{id}} returns the
 * content of ANY document to anyone who knows the identifier — the same vulnerability
 * {@link dev.collabedit.app.ws.DocumentAccessInterceptor} closes on the WebSocket side, only by a
 * different route.
 *
 * <p>Closing one of two routes to the same data is worse than closing neither, because it gives a
 * false sense that the problem has been solved.
 *
 * <h2>Why a custom filter rather than Spring Security</h2>
 * The same reason {@link TokenService} is written by hand (see its javadoc): the whole mechanism is
 * a few dozen lines, and explicit code shows exactly where the check is wired in. Spring Security
 * would bring a filter chain, configuration and its own vocabulary — for three access rules that is
 * a cost with no benefit. In production: Spring Security, because there the rules number in the
 * dozens and it is the rules, not the mechanism, that are hard.
 *
 * <p>The filter <b>does not reject</b> requests without a token — it only establishes identity. The
 * decision to refuse belongs to the endpoint, because only it knows whether a given path needs
 * access to a specific document or is public (like {@code /api/login}).
 *
 * <p>Since CP16 that decision is no longer left to each controller to remember: every endpoint
 * declares what it needs and {@link DocumentAccessGuard} refuses to serve one that declares nothing
 *.
 */
@Component
public class TokenAuthFilter extends OncePerRequestFilter {

    /** The request attribute holding the identity; {@code null} when the token is absent or invalid. */
    public static final String PRINCIPAL = "collabedit.principal";

    private final TokenService tokens;

    public TokenAuthFilter(TokenService tokens) {
        this.tokens = tokens;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        bearerOf(request)
            .flatMap(tokens::verify)
            .ifPresent(principal -> request.setAttribute(PRINCIPAL, principal));
        chain.doFilter(request, response);
    }

    private static Optional<String> bearerOf(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return Optional.empty();
        }
        String token = header.substring(7).trim();
        return token.isEmpty() ? Optional.empty() : Optional.of(token);
    }

    /** The identity from the request, or empty when the request is unauthenticated. */
    public static Optional<TokenService.Principal> principalOf(HttpServletRequest request) {
        Object attribute = request.getAttribute(PRINCIPAL);
        return attribute instanceof TokenService.Principal principal
            ? Optional.of(principal)
            : Optional.empty();
    }

    /*
     * `callerId` — the nullable half of this pair — used to sit here, and its javadoc argued that
     * naming both makes the choice deliberate at the call site: `requiredCallerId` behind an
     * annotation the guard enforces, `callerId` for "a public endpoint, or a filter that treats
     * 'no caller' as 'no rows'".
     *
     * The argument is sound and the second case never arrived. Thirteen call sites use
     * `requiredCallerId`; nothing has ever called `callerId`, including the one @PublicEndpoint.
     * A pair whose halves are meant to force a decision does not force one when only ever half of
     * it exists — it just leaves a reader wondering which endpoints chose the other. If a
     * genuinely anonymous-tolerant endpoint appears, `principalOf` is right there and the decision
     * gets made in the open.
     */

    /**
     * The caller's identifier, insisting there is one.
     *
     * <p>Throwing is correct here rather than defensive: every call site is behind an annotation
     * the guard enforces, so reaching this without a principal means the guard did not run — a
     * wiring fault in our own code, which {@link DocumentAccessGuard} also answers with a 500 for
     * exactly the same reason. Returning null instead would push the fault downstream, into a
     * foreign key or an ownerless document, which is how CP16 found three separate bugs.
     *
     * <p>The type is {@link AuthorizationWiringException} and not {@code IllegalStateException},
     * which is what made the sentence above true. It threw the latter, and every one of those is
     * mapped to <b>409 with the message in the body</b> — so the fault this raises was reported as
     * the caller's mistake, stayed out of 5xx monitoring, and handed our internal wording to a
     * client. See that class.
     */
    public static String requiredCallerId(HttpServletRequest request) {
        return principalOf(request)
            .map(TokenService.Principal::userId)
            .orElseThrow(() -> new AuthorizationWiringException(
                "an authenticated endpoint was reached with no principal — the access guard did "
                    + "not run for it"));
    }
}
