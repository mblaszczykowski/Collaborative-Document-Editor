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
 * Uwierzytelnienie żądań REST (CP12).
 *
 * <h2>Dlaczego dopiero teraz</h2>
 * Do CP11 REST wystawiał listę dokumentów, tworzenie i odczyt treści — <b>bez żadnej
 * kontroli dostępu</b>. Przy jednym wspólnym dokumencie było to spójne z całą resztą:
 * model brzmiał „każdy może wszystko". Od CP11 {@code GET /api/documents/{id}} zwraca treść
 * DOWOLNEGO dokumentu każdemu, kto zna identyfikator — czyli ta sama podatność, którą
 * {@link dev.collabedit.app.ws.DocumentAccessInterceptor} zamyka po stronie WebSocketa,
 * tylko inną drogą.
 *
 * <p>Zamykanie jednej z dwóch dróg do tych samych danych jest gorsze niż niezamykanie żadnej,
 * bo daje fałszywe poczucie, że problem został rozwiązany.
 *
 * <h2>Dlaczego własny filtr, a nie Spring Security</h2>
 * Ten sam powód, dla którego {@link TokenService} jest napisany ręcznie (patrz tamtejszy
 * javadoc): cały mechanizm to kilkadziesiąt linii, a jawny kod pokazuje, gdzie dokładnie
 * wpina się sprawdzenie. Spring Security wniósłby łańcuch filtrów, konfigurację i własne
 * pojęcia — dla trzech reguł dostępu jest to koszt bez korzyści. W produkcji: Spring Security,
 * bo tam reguł jest kilkadziesiąt i to one, a nie mechanizm, są trudne.
 *
 * <p>Filtr <b>nie odrzuca</b> żądań bez tokenu — ustala jedynie tożsamość. Decyzję o odmowie
 * podejmuje kontroler, bo tylko on wie, czy dana ścieżka wymaga dostępu do konkretnego
 * dokumentu, czy jest publiczna (jak {@code /api/login}).
 */
@Component
public class TokenAuthFilter extends OncePerRequestFilter {

    /** Atrybut żądania z tożsamością; {@code null}, gdy token nieobecny albo nieważny. */
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

    /** Tożsamość z żądania albo puste, gdy żądanie jest nieuwierzytelnione. */
    public static Optional<TokenService.Principal> principalOf(HttpServletRequest request) {
        Object attribute = request.getAttribute(PRINCIPAL);
        return attribute instanceof TokenService.Principal principal
            ? Optional.of(principal)
            : Optional.empty();
    }
}
