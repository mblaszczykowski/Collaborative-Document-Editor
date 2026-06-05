package dev.collabedit.app.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Uwierzytelnianie — <b>świadomie uproszczone</b> i tak opisane w README.
 *
 * <p>Token to podpisany HMAC-SHA256 ładunek {@code userId|displayName|color|wygaśnięcie}.
 * Nie ma tu rejestracji, haseł, odświeżania ani unieważniania. Zakresem tego projektu jest
 * spójność danych rozproszonych, nie zarządzanie tożsamością, a rozbudowany moduł auth
 * zabrałby czas problemowi rdzeniowemu, nic nie demonstrując.
 *
 * <p>Napisane ręcznie na JDK zamiast biblioteki JWT z dwóch powodów: to jest kilkadziesiąt
 * linii bez zależności, a jawny kod podpisu lepiej pokazuje, gdzie dokładnie w uścisku dłoni
 * WebSocketa wpina się uwierzytelnienie (ramka {@code CONNECT}, nie każda wiadomość).
 *
 * <p><b>Czego tu brakuje na produkcję</b> — wprost, żeby nikt nie wziął tego za wzorzec:
 * rotacji klucza, unieważniania tokenów, powiązania tokenu z sesją, ochrony przed odtworzeniem,
 * i sekretu spoza konfiguracji.
 */
@Service
public class TokenService {

    private static final Duration LIFETIME = Duration.ofHours(12);
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final SecretKeySpec key;

    public TokenService(@Value("${collabedit.auth.secret:rozwojowy-sekret-nie-do-produkcji}") String secret) {
        this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    public record Principal(String userId, String displayName, String color) {
    }

    public String issue(Principal principal) {
        String payload = String.join("|",
            principal.userId(),
            principal.displayName(),
            principal.color(),
            Long.toString(Instant.now().plus(LIFETIME).getEpochSecond()));
        String encoded = ENCODER.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return encoded + "." + ENCODER.encodeToString(sign(encoded));
    }

    public Optional<Principal> verify(String token) {
        if (token == null) {
            return Optional.empty();
        }
        int separator = token.lastIndexOf('.');
        if (separator < 0) {
            return Optional.empty();
        }
        String encoded = token.substring(0, separator);
        byte[] presented;
        try {
            presented = DECODER.decode(token.substring(separator + 1));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        // Porównanie stałoczasowe — zwykłe equals na tablicach bajtów przecieka informację
        // o tym, ile początkowych bajtów podpisu się zgadza.
        if (!MessageDigest.isEqual(presented, sign(encoded))) {
            return Optional.empty();
        }

        String[] parts = new String(DECODER.decode(encoded), StandardCharsets.UTF_8).split("\\|");
        if (parts.length != 4) {
            return Optional.empty();
        }
        if (Long.parseLong(parts[3]) < Instant.now().getEpochSecond()) {
            return Optional.empty();
        }
        return Optional.of(new Principal(parts[0], parts[1], parts[2]));
    }

    private byte[] sign(String encodedPayload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return mac.doFinal(encodedPayload.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("nie da się podpisać tokenu", e);
        }
    }
}
