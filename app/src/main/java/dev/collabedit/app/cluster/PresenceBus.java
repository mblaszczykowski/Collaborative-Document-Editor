package dev.collabedit.app.cluster;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Who is in a document, across instances (CP26).
 *
 * <h2>Why presence gets its own channel</h2>
 * Three Redis channels now, and each one carries a different kind of fact:
 *
 * <ul>
 *   <li>{@link ClusterBus} — operations. Unreliable on purpose: a lost message costs propagation
 *       delay and the anti-entropy sweep repairs it (ADR-0004).</li>
 *   <li>{@link PermissionInvalidationBus} — access decisions. Unreliable, with the cache TTL as
 *       the backstop.</li>
 *   <li>this one — <b>soft state with an expiry</b>. Nothing repairs a lost presence message and
 *       nothing needs to: the next heartbeat carries the whole truth again, one second later.</li>
 * </ul>
 *
 * <p>That last property is what makes this channel the cheapest of the three to get right, and it
 * is worth naming because presence is the one place where the usual answer — "it converges" —
 * is wrong. A participant list does not converge; it expires.
 *
 * <h2>Full lists, not deltas</h2>
 * Within an instance, a cursor move is broadcast as a delta, because it is the most frequent event
 * in the system and a full list per keystroke costs traffic growing with the square of the number
 * of people. Across instances the trade goes the other way: a delta stream would need ordering and
 * loss detection to be worth anything, and a full list per instance per second needs neither. A
 * message is self-contained, so a lost one costs a second of staleness rather than a corrupted
 * list.
 */
@Component
public class PresenceBus implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(PresenceBus.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    public static final String CHANNEL = "collabedit:presence";

    private final String instanceId = UUID.randomUUID().toString();
    private final StringRedisTemplate redis;

    /** (instanceId, documentId, participants as the wire sees them). */
    private volatile Consumer<Announcement> handler = announcement -> { };

    public PresenceBus(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public record Announcement(String instanceId, UUID documentId, JsonNode participants) {
    }

    public String instanceId() {
        return instanceId;
    }

    public void onAnnouncement(Consumer<Announcement> handler) {
        this.handler = handler;
    }

    /**
     * Announces who this instance has in a document.
     *
     * @return {@code false} when the announcement could not be sent — the caller uses that to tell
     *         its clients the list they are looking at may be incomplete. That is the one place in
     *         this system where best-effort has to be shown to the user rather than hidden behind
     *         convergence: a participant list that is quietly short looks exactly like a document
     *         nobody else is in.
     */
    public boolean publish(UUID documentId, Object participants) {
        try {
            var payload = JSON.createObjectNode();
            payload.put("i", instanceId);
            payload.put("d", documentId.toString());
            payload.set("p", JSON.valueToTree(participants));
            redis.convertAndSend(CHANNEL, JSON.writeValueAsString(payload));
            return true;
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
            log.warn("Could not announce presence for document {}: {}", documentId, e.toString());
            return false;
        }
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            JsonNode payload = JSON.readTree(new String(message.getBody(), StandardCharsets.UTF_8));
            String from = payload.path("i").asText();
            if (instanceId.equals(from)) {
                // Our own announcement coming back — pub/sub does not filter the sender.
                return;
            }
            handler.accept(new Announcement(from,
                UUID.fromString(payload.path("d").asText()), payload.path("p")));
        } catch (Exception e) {
            log.warn("Unreadable presence announcement, ignoring: {}", e.toString());
        }
    }
}
