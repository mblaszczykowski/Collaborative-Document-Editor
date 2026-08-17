package dev.collabedit.app.cluster;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.collabedit.crdt.OpCodec;
import dev.collabedit.app.persist.StoredOperation;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class ClusterBus implements MessageListener {
    private static final Logger log = LoggerFactory.getLogger(ClusterBus.class);

    private final String instanceId;

    private final StringRedisTemplate redis;
    private volatile Consumer<ClusterMessage> handler = message -> { };

    private volatile Predicate<UUID> interested = id -> true;

    public ClusterBus(StringRedisTemplate redis, InstanceIdentity identity) {
        this.redis = redis;
        this.instanceId = identity.id();
    }

    public void onMessage(Consumer<ClusterMessage> handler) {
        this.handler = handler;
    }

    public void interestedIn(Predicate<UUID> predicate) {
        this.interested = predicate;
    }

    static UUID documentIdOf(String channel) {
        int start = "collabedit:doc:".length();
        int end = channel.lastIndexOf(":ops");
        if (!channel.startsWith("collabedit:doc:") || end <= start) {
            return null;
        }
        try {
            return UUID.fromString(channel.substring(start, end));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public record ClusterMessage(UUID documentId, List<StoredOperation> operations) {
    }

    public static String channel(UUID documentId) {
        return "collabedit:doc:" + documentId + ":ops";
    }

    public static final String CHANNEL_PATTERN = "collabedit:doc:*:ops";

    public void publish(UUID documentId, List<StoredOperation> operations) {
        if (operations.isEmpty()) {
            return;
        }
        ObjectNode payload = OpCodec.mapper().createObjectNode();
        payload.put("from", instanceId);
        payload.put("doc", documentId.toString());
        ArrayNode array = payload.putArray("ops");
        for (StoredOperation stored : operations) {
            ObjectNode entry = OpCodec.mapper().createObjectNode();
            entry.set("op", OpCodec.toJson(stored.op()));
            entry.put("seq", stored.serverSeq());
            entry.put("user", stored.userId());
            array.add(entry);
        }
        try {
            redis.convertAndSend(channel(documentId), payload.toString());
        } catch (RuntimeException e) {
            log.warn("Publishing to Redis failed for document {} - anti-entropy will repair it: {}",
                documentId, e.toString());
        }
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        UUID channelDocument = documentIdOf(new String(message.getChannel(), StandardCharsets.UTF_8));
        if (channelDocument == null || !interested.test(channelDocument)) {
            return;
        }

        List<StoredOperation> operations;
        try {
            JsonNode payload = OpCodec.mapper().readTree(message.getBody());
            if (instanceId.equals(payload.path("from").asText())) {
                return;
            }
            String claimed = payload.path("doc").asText(null);
            if (claimed != null && !channelDocument.toString().equals(claimed)) {
                log.warn("Cluster message on channel for document {} claims document {} in its "
                    + "body - using the channel and ignoring the claim", channelDocument, claimed);
            }

            JsonNode ops = payload.path("ops");
            if (!ops.isArray() || ops.isEmpty()) {
                log.warn("Cluster message for document {} carries no operations - skipping",
                    channelDocument);
                return;
            }

            operations = new ArrayList<>(ops.size());
            for (JsonNode entry : ops) {
                operations.add(new StoredOperation(
                    OpCodec.fromJson(entry.get("op")),
                    entry.get("seq").asLong(),
                    entry.path("user").asText(null),
                    Instant.now()));
            }
        } catch (Exception e) {
            log.warn("Unreadable cluster message for document {} - skipping", channelDocument, e);
            return;
        }

        try {
            handler.accept(new ClusterMessage(channelDocument, operations));
        } catch (RuntimeException e) {
            log.warn("Applying {} operations from the cluster for document {} failed - "
                + "anti-entropy will repair it", operations.size(), channelDocument, e);
        }
    }
}
