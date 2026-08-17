package dev.collabedit.app.cluster;

import com.fasterxml.jackson.core.JsonProcessingException;
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

@Component
public class PresenceBus implements MessageListener {
    private static final Logger log = LoggerFactory.getLogger(PresenceBus.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    public static final String CHANNEL = "collabedit:presence";

    private final String instanceId;
    private final StringRedisTemplate redis;

    private volatile Consumer<Announcement> handler = announcement -> { };

    public PresenceBus(StringRedisTemplate redis, InstanceIdentity identity) {
        this.redis = redis;
        this.instanceId = identity.id();
    }

    public record Announcement(String instanceId, UUID documentId, JsonNode participants) {
    }

    public String instanceId() {
        return instanceId;
    }

    public void onAnnouncement(Consumer<Announcement> handler) {
        this.handler = handler;
    }

    public boolean publish(UUID documentId, Object participants) {
        try {
            var payload = JSON.createObjectNode();
            payload.put("i", instanceId);
            payload.put("d", documentId.toString());
            payload.set("p", JSON.valueToTree(participants));
            redis.convertAndSend(CHANNEL, JSON.writeValueAsString(payload));
            return true;
        } catch (RuntimeException | JsonProcessingException e) {
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
                return;
            }
            handler.accept(new Announcement(from,
                UUID.fromString(payload.path("d").asText()), payload.path("p")));
        } catch (Exception e) {
            log.warn("Unreadable presence announcement, ignoring: {}", e.toString());
        }
    }
}
