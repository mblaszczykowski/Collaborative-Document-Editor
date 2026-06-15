package dev.collabedit.app.cluster;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.collabedit.crdt.OpCodec;
import dev.collabedit.app.persist.StoredOperation;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Distributing operations between backend instances over Redis pub/sub.
 *
 * <p><b>This is the fast and UNRELIABLE path</b> — deliberately (ADR-0004). Redis pub/sub gives
 * at-most-once delivery: a Redis restart, a client buffer overflow or a brief partition lose a
 * message with no error and no trace. The configuration in {@code docker-compose.yml} deliberately
 * disables Redis persistence and sets {@code maxmemory-policy noeviction} so that this scenario can
 * be induced in the chaos test (CP8) rather than pretended away.
 *
 * <p>Safety does not come from messages never being lost, but from the fact that <b>losing a
 * message is not a correctness problem</b>:
 * <ul>
 *   <li>the authoritative copy is the operation log in Postgres, written <i>before</i> publishing,</li>
 *   <li>a replica that detects a gap in {@code server_seq} pulls the missing range from the database,</li>
 *   <li>a periodic sweep catches the case gap detection cannot: losing the <i>last</i> operation,
 *       with no subsequent one to reveal the gap.</li>
 * </ul>
 *
 * <p>The repair need be neither fast nor ordered — CRDT merging is commutative and idempotent, so
 * an operation pulled in 30 seconds after the fact gives the same result as one delivered
 * immediately. That is exactly the property CRDT was chosen for in ADR-0001, and here it stops
 * being theoretical.
 */
@Component
public class ClusterBus implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(ClusterBus.class);

    /** Messages from ourselves have to be filtered out — pub/sub does not do it for us. */
    private final String instanceId = UUID.randomUUID().toString();

    private final StringRedisTemplate redis;
    private volatile Consumer<ClusterMessage> handler = message -> { };

    /**
     * Whether this instance is interested in a given document at all.
     *
     * <p>Checked <b>before</b> reading the message body. The subscription is pattern-based (one
     * pattern for all documents — see {@code RedisConfig}), so every instance receives the traffic
     * of EVERY document in the cluster. With one document that made no difference; with a thousand
     * it means parsing the JSON of the whole cluster's traffic on every instance, only to discard
     * the result in most cases.
     *
     * <p>The document identifier is already in the CHANNEL NAME, so the filtering costs nothing —
     * provided it happens before parsing. That is the entire change.
     */
    private volatile java.util.function.Predicate<UUID> interested = id -> true;

    public ClusterBus(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public String instanceId() {
        return instanceId;
    }

    public void onMessage(Consumer<ClusterMessage> handler) {
        this.handler = handler;
    }

    /** Sets the filter of documents this instance cares about. See {@link #interested}. */
    public void interestedIn(java.util.function.Predicate<UUID> predicate) {
        this.interested = predicate;
    }

    /**
     * Extracts the document identifier from the channel name {@code collabedit:doc:{uuid}:ops}.
     *
     * @return {@code null} when the name does not match the pattern — in which case the message is
     *         skipped, because there is no telling what it refers to
     */
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

    /** A cluster message: one document's operations together with their assigned sequence numbers. */
    public record ClusterMessage(UUID documentId, List<StoredOperation> operations) {
    }

    public static String channel(UUID documentId) {
        return "collabedit:doc:" + documentId + ":ops";
    }

    public static final String CHANNEL_PATTERN = "collabedit:doc:*:ops";

    /**
     * Publishes operations that have just been <b>written</b>.
     *
     * <p>The "write before publish" ordering matters: an instance that receives an operation from
     * Redis immediately records its {@code server_seq} as reached. If publishing ran ahead of the
     * write and the write then failed, other instances would treat as reached a number that is not
     * in the database — and would never pull it in.
     */
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
            // The error is swallowed deliberately: publishing is a best-effort path. An
            // unavailable Redis means slower propagation between instances, not data loss — the
            // data is already in the operation log. The anti-entropy sweep will repair it.
            log.warn("Publishing to Redis failed for document {} — anti-entropy will repair it: {}",
                documentId, e.toString());
        }
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        // Filtering BEFORE parsing the body — the document identifier is in the channel name, so
        // there is no reason to read the payload of a message that will be discarded anyway. With
        // a thousand documents in the cluster this is the difference between parsing the whole
        // cluster's traffic on every instance and parsing only one's own.
        UUID channelDocument = documentIdOf(new String(message.getChannel(), StandardCharsets.UTF_8));
        if (channelDocument == null || !interested.test(channelDocument)) {
            return;
        }
        try {
            JsonNode payload = OpCodec.mapper().readTree(message.getBody());
            if (instanceId.equals(payload.path("from").asText())) {
                return;
            }
            UUID documentId = UUID.fromString(payload.get("doc").asText());
            List<StoredOperation> operations = new ArrayList<>();
            for (JsonNode entry : payload.get("ops")) {
                operations.add(new StoredOperation(
                    OpCodec.fromJson(entry.get("op")),
                    entry.get("seq").asLong(),
                    entry.path("user").asText(null),
                    java.time.Instant.now()));
            }
            handler.accept(new ClusterMessage(documentId, operations));
        } catch (Exception e) {
            log.warn("Unreadable cluster message — skipping: {}", e.toString());
        }
    }
}
