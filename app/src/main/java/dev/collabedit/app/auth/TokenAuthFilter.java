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

    /**
     * The caller's identifier, or {@code null} when the request is unauthenticated.
     *
     * <h2>Why the two methods below exist</h2>
     * {@code TokenAuthFilter.principalOf(request).map(p -> p.userId).orElse(…)} appeared SIX
     * times across four controllers — and with <b>two different endings</b>: three call sites used
     * {@code orElse(null)} and three used {@code orElseThrow}. Nothing said which was intended
     * where, so "what happens to an anonymous caller here" was answered by whichever line happened
     * to get copied.
     *
     * <p>Naming both makes the choice deliberate at the call site instead of accidental. The rule
     * that decides between them is the endpoint's own declaration: one guarded by
     * {@link RequiresDocumentAccess} or {@link RequiresAuthentication} has already had an anonymous
     * caller refused by {@link DocumentAccessGuard}, so it may say {@code requiredCallerId} and mean
     * it. One that tolerates anonymity — a public endpoint, or a filter that treats "no caller" as
     * "no rows" — says {@code callerId} and handles the null.
     */
    public static String callerId(HttpServletRequest request) {
        return principalOf(request).map(TokenService.Principal::userId).orElse(null);
    }

    /**
     * The caller's identifier, insisting there is one.
     *
     * <p>Throwing is correct here rather than defensive: every call site is behind an annotation
     * the guard enforces, so reaching this without a principal means the guard did not run — a
     * wiring fault in our own code, which {@link DocumentAccessGuard} also answers with a 500 for
     * exactly the same reason. Returning null instead would push the fault downstream, into a
     * foreign key or an ownerless document, which is how CP16 found three separate bugs.
     */
    public static String requiredCallerId(HttpServletRequest request) {
        return principalOf(request)
            .map(TokenService.Principal::userId)
            .orElseThrow(() -> new IllegalStateException(
                "an authenticated endpoint was reached with no principal — the access guard did "
                    + "not run for it"));
    }
}
