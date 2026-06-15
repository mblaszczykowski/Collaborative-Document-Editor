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
 * Authentication — <b>deliberately simplified</b>, and described that way in the README.
 *
 * <p>A token is an HMAC-SHA256-signed payload of {@code userId|displayName|color|expiry}. There is
 * no registration, no passwords, no refresh and no revocation. This project's scope is distributed
 * data consistency, not identity management, and an elaborate auth module would take time away from
 * the core problem while demonstrating nothing.
 *
 * <p>Written by hand on the JDK instead of pulling in a JWT library for two reasons: it is a few
 * dozen lines with no dependencies, and explicit signing code shows better where exactly
 * authentication hooks into the WebSocket handshake (the {@code CONNECT} frame, not every message).
 *
 * <p><b>What is missing here for production</b> — stated plainly, so nobody takes this as a model:
 * key rotation, token revocation, binding a token to a session, and replay protection.
 *
 * <p>The secret <b>left</b> that list in CP16: it no longer has a default, so the artifact does not
 * carry a known key inside it — details at the constructor.
 */
@Service
public class TokenService {

    private static final Duration LIFETIME = Duration.ofHours(12);
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final SecretKeySpec key;

    /**
     * Minimum length of a usable signing secret.
     *
     * <p>HMAC-SHA256 accepts a key of any length, so nothing except this check stops
     * {@code collabedit.auth.secret=x} from starting a server whose tokens are trivially
     * forgeable. 32 bytes matches the output width of the hash: shorter adds no security the
     * construction does not already provide, and much shorter is guessable.
     */
    private static final int MIN_SECRET_LENGTH = 32;

    /**
     * The secret has <b>no default</b>, and that is the point.
     *
     * <p>It used to fall back to a value checked into this repository. Any deployment that
     * forgot to set the variable therefore ran on a secret every reader of the repo knows —
     * and since the token carries the user id, knowing the secret is enough to mint a token
     * for <b>any</b> user, which is total authentication bypass. The failure was silent: a
     * server with a forgeable secret starts, serves, and looks healthy.
     *
     * <p>Refusing to start is the right shape of answer because the alternatives are worse.
     * Generating a random secret per instance would break horizontal scaling outright — two
     * instances could not validate each other's tokens, and {@link
     * dev.collabedit.app.cluster.ClusterBus} assumes any instance can serve any session
     * (ADR-0004). Warning and continuing is what the old code effectively did.
     *
     * <p>The cost is real and is not hidden: {@code ./mvnw -pl app spring-boot:run} now fails
     * without an environment variable. Docker Compose and CI set it; the README documents the
     * export. One line of friction in exchange for there being no known secret baked into the
     * artifact at all.
     */
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

    /**
     * Encodes a single payload field.
     *
     * <p>The field separator is {@code |}, and {@code displayName} comes straight from the user —
     * without this encoding, a name containing {@code |} split the payload into more parts and
     * {@code verify} rejected <b>its own, correctly signed token</b>. The symptom was a login that
     * "works" (REST returns a token) followed by a {@code CONNECT} frame rejected for no
     * comprehensible reason.
     *
     * <p>Base64url is sufficient here and free: its alphabet ({@code A-Z a-z 0-9 - _}) by
     * definition excludes the separator, so the split is unambiguous whatever the content.
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
        // A constant-time comparison — a plain equals on byte arrays leaks how many leading
        // bytes of the signature match.
        if (!MessageDigest.isEqual(presented, sign(encoded))) {
            return Optional.empty();
        }

        // limit -1: without it, an empty trailing field would be silently dropped and a
        // structurally valid payload would look corrupt.
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
            // The payload passed signature verification but cannot be read — meaning the token
            // was issued by an older version with a different payload format. We reject it like
            // any invalid token: the user logs in again and gets a token in the new format.
            // Versioning the format would be a cost with no benefit given the token's 12-hour
            // lifetime.
            return Optional.empty();
        }
    }

    private byte[] sign(String encodedPayload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return mac.doFinal(encodedPayload.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("cannot sign the token", e);
        }
    }
}
