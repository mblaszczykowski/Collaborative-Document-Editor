package dev.collabedit.app.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class TokenAuthFilter extends OncePerRequestFilter {
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

    public static Optional<TokenService.Principal> principalOf(HttpServletRequest request) {
        Object attribute = request.getAttribute(PRINCIPAL);
        return attribute instanceof TokenService.Principal principal
            ? Optional.of(principal)
            : Optional.empty();
    }

    public static String requiredCallerId(HttpServletRequest request) {
        return principalOf(request)
            .map(TokenService.Principal::userId)
            .orElseThrow(() -> new AuthorizationWiringException(
                "an authenticated endpoint was reached with no principal - the access guard did "
                    + "not run for it"));
    }
}
