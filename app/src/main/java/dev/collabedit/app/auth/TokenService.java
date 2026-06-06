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
            field(principal.userId()),
            field(principal.displayName()),
            field(principal.color()),
            Long.toString(Instant.now().plus(LIFETIME).getEpochSecond()));
        String encoded = ENCODER.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return encoded + "." + ENCODER.encodeToString(sign(encoded));
    }

    /**
     * Koduje pojedyncze pole ładunku.
     *
     * <p>Separatorem pól jest {@code |}, a {@code displayName} pochodzi wprost od użytkownika
     * — bez tego kodowania nazwa zawierająca {@code |} rozbijała ładunek na więcej części
     * i {@code verify} odrzucał <b>własny, poprawnie podpisany token</b>. Objawem było
     * logowanie, które „działa" (REST zwraca token), po którym ramka {@code CONNECT} jest
     * odrzucana bez zrozumiałego powodu.
     *
     * <p>Base64url jest tu wystarczający i darmowy: jego alfabet ({@code A-Z a-z 0-9 - _})
     * z definicji nie zawiera separatora, więc podział jest jednoznaczny niezależnie od treści.
     */
    private static String field(String value) {
        return ENCODER.encodeToString(value.getBytes(StandardCharsets.UTF_8));
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

        // limit -1: bez niego puste pole na końcu zostałoby po cichu odrzucone i ładunek
        // o poprawnej strukturze wyglądałby jak uszkodzony.
        String[] parts = new String(DECODER.decode(encoded), StandardCharsets.UTF_8).split("\\|", -1);
        if (parts.length != 4) {
            return Optional.empty();
        }
        try {
            if (Long.parseLong(parts[3]) < Instant.now().getEpochSecond()) {
                return Optional.empty();
            }
            return Optional.of(new Principal(
                new String(DECODER.decode(parts[0]), StandardCharsets.UTF_8),
                new String(DECODER.decode(parts[1]), StandardCharsets.UTF_8),
                new String(DECODER.decode(parts[2]), StandardCharsets.UTF_8)));
        } catch (IllegalArgumentException e) {
            // Ładunek przeszedł weryfikację podpisu, ale nie da się go odczytać — czyli
            // token wystawiła starsza wersja o innym formacie ładunku. Odrzucamy jak każdy
            // nieważny token: użytkownik zaloguje się ponownie i dostanie token w nowym
            // formacie. Wersjonowanie formatu byłoby tu kosztem bez korzyści przy 12-godzinnym
            // czasie życia tokenu.
            return Optional.empty();
        }
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
