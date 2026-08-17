package dev.collabedit.app.auth;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class TokenService {
    private static final Duration LIFETIME = Duration.ofHours(12);
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final SecretKeySpec key;

    private static final int MIN_SECRET_LENGTH = 32;

    public TokenService(@Value("${collabedit.auth.secret:}") String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("""
                collabedit.auth.secret is not set.

                There is deliberately no default: a fallback secret checked into the repository \
                means anyone who can read it can mint a token for any user.

                For local development:
                    export COLLABEDIT_AUTH_SECRET=$(openssl rand -base64 32)

                Docker Compose sets it for you. In a real deployment it must come from the \
                environment or a secret store, never from a file in the image.""");
        }
        if (secret.length() < MIN_SECRET_LENGTH) {
            throw new IllegalStateException(
                "collabedit.auth.secret is only " + secret.length() + " characters; at least "
                    + MIN_SECRET_LENGTH + " are required. Generate one with: "
                    + "openssl rand -base64 32");
        }
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
        if (!MessageDigest.isEqual(presented, sign(encoded))) {
            return Optional.empty();
        }

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
            return Optional.empty();
        }
    }

    private byte[] sign(String encodedPayload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return mac.doFinal(encodedPayload.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("cannot sign the token", e);
        }
    }

    static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
