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

/**
 * Distributing operations between backend instances over Redis pub/sub.
 *
 * <p><b>This is the fast and UNRELIABLE path</b> — deliberately. Redis pub/sub gives
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
 * immediately. That is exactly the property CRDT was chosen for in the CRDT decision, and here it stops
 * being theoretical.
 */
@Component
public class ClusterBus implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(ClusterBus.class);

    /** Messages from ourselves have to be filtered out — pub/sub does not do it for us. */
    private final String instanceId;

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
    private volatile Predicate<UUID> interested = id -> true;

    public ClusterBus(StringRedisTemplate redis, InstanceIdentity identity) {
        this.redis = redis;
        this.instanceId = identity.id();
    }


    public void onMessage(Consumer<ClusterMessage> handler) {
        this.handler = handler;
    }

    /** Sets the filter of documents this instance cares about. See {@link #interested}. */
    public void interestedIn(Predicate<UUID> predicate) {
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

        List<StoredOperation> operations;
        // The try covers the PARSE and nothing else, which is the whole correction. It used to wrap
        // handler.accept as well, so ANY downstream failure — a merge error, a Postgres failure
        // inside repairFrom, a NoSuchElementException from an empty ops array — was reported as
        // "Unreadable cluster message" and the stack trace was thrown away with e.toString.
        // A database outage during a gap repair therefore appeared in the log as a malformed
        // message from another instance: the wrong component, the wrong cause, and nothing to
        // follow.
        try {
            JsonNode payload = OpCodec.mapper().readTree(message.getBody());
            if (instanceId.equals(payload.path("from").asText())) {
                return;
            }
            // The document comes from the CHANNEL NAME, not from payload.get("doc").
            //
            // This class argues at length that the body must not be trusted before the channel has
            // been checked — and then built the ClusterMessage from the body anyway, so the id that
            // passed the interest filter and the id the operations were merged into were two
            // different values from two different sources. A message published to one document's
            // channel with another document's id in it would have been applied to the second while
            // being authorised as the first.
            //
            // The redundant field stays on the wire and is CHECKED rather than read, because a
            // mismatch is evidence about the publisher and silence about it would be the same
            // mistake in a quieter form.
            String claimed = payload.path("doc").asText(null);
            if (claimed != null && !channelDocument.toString().equals(claimed)) {
                log.warn("Cluster message on channel for document {} claims document {} in its "
                    + "body — using the channel and ignoring the claim", channelDocument, claimed);
            }

            JsonNode ops = payload.path("ops");
            if (!ops.isArray() || ops.isEmpty()) {
                // Guarded rather than left to throw. `publish` never sends an empty batch, so this
                // means a publisher that is not us or not this version — and the old code turned it
                // into a NoSuchElementException inside DocumentService.onClusterMessage
                // (getFirst on an empty list), logged under the one message that says the
                // opposite of what happened.
                log.warn("Cluster message for document {} carries no operations — skipping",
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
            // The exception itself, not e.toString: an unreadable message is rare enough that a
            // stack trace costs nothing and is the only thing that says WHERE the parse gave up.
            log.warn("Unreadable cluster message for document {} — skipping", channelDocument, e);
            return;
        }

        // A SEPARATE try, and a different message. A failure in here is a failure of the merge or
        // of the database — the anti-entropy sweep is what repairs it — and calling it
        // "unreadable" sends whoever is diagnosing it to look at the publisher.
        //
        // Caught rather than allowed to propagate: RedisMessageListenerContainer would log it
        // through its own error handler, which is one more indirection between the fault and its
        // description, and this is the place that knows what the work was.
        try {
            handler.accept(new ClusterMessage(channelDocument, operations));
        } catch (RuntimeException e) {
            log.warn("Applying {} operations from the cluster for document {} failed — "
                + "anti-entropy will repair it", operations.size(), channelDocument, e);
        }
    }
}
