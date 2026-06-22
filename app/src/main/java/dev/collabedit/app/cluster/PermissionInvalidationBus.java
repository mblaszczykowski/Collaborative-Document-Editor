package dev.collabedit.app.cluster;

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
 * Telling the other instances that somebody's access to a document has changed.
 *
 * <h2>The one place in this system where eventual consistency is not good enough</h2>
 * Everything else here is built on the opposite assumption. The CRDT decision chooses a CRDT precisely so
 * that replicas may disagree for a while and still converge; the cluster design makes the cluster bus
 * <b>deliberately unreliable</b>, because a dropped operation is a latency problem rather than a
 * correctness problem. That reasoning is sound, and it does not extend here.
 *
 * <p>{@link dev.collabedit.app.auth.PermissionService} caches a resolved role for five seconds so
 * that a permission check on every STOMP frame is not a database query per keystroke. The cache is
 * invalidated whenever access changes — but the invalidation was <b>local to the instance</b>. On
 * a cluster, revoking someone's access left them writing to the document for up to the cache TTL
 * on every instance except the one that processed the revocation. The person clicking "remove
 * access" expects it to have worked, not to be about to work.
 *
 * <p>Five seconds is a small number, and that is not the point. The difference is in kind: a text
 * operation arriving late is still the same document, and a permission arriving late is a window
 * in which the system does something it was told to stop doing.
 *
 * <h2>Why this is still best-effort delivery, and why that is fine</h2>
 * Redis pub/sub can drop this message just as it can drop an operation, and there is no
 * anti-entropy sweep for permissions. The reason that is acceptable is the TTL itself:
 * <b>the cache expiry is the backstop</b>. A lost invalidation degrades to exactly the behaviour
 * that existed before this class — bounded by five seconds — rather than to an unbounded one.
 * Broadcasting narrows the window from "always five seconds" to "five seconds only if the message
 * is lost", and the failure mode is the old behaviour instead of a new one.
 *
 * <p>Making it reliable would mean a durable queue and acknowledgements from every instance, which
 * is a large amount of machinery to shorten a five-second window that already has a floor. That
 * would be the mirror of the mistake the comment split names: reaching for a mechanism because it is
 * available rather than because the problem needs it.
 *
 * <h2>Separate channel, separate class</h2>
 * Not folded into {@link ClusterBus}, even though both are Redis pub/sub. That one carries
 * operations for a specific document and is filtered by a channel name containing the document
 * id; this one is a single global channel and carries a decision about access. Sharing the
 * transport would mean sharing {@code interestedIn}, and an instance that does not hold a document
 * in memory <b>still has cached roles for it</b> — so the filter that makes {@link ClusterBus}
 * cheap would make this one wrong.
 */
@Component
public class PermissionInvalidationBus implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(PermissionInvalidationBus.class);

    public static final String CHANNEL = "collabedit:permissions:invalidate";

    /** Messages from this instance are already applied locally; pub/sub does not filter them. */
    private final String instanceId;

    private final StringRedisTemplate redis;
    private volatile Consumer<UUID> handler = documentId -> { };

    public PermissionInvalidationBus(StringRedisTemplate redis, InstanceIdentity identity) {
        this.redis = redis;
        this.instanceId = identity.id();
    }

    /** This instance's identifier, used to filter out its own messages. */
    public String instanceId() {
        return instanceId;
    }

    /** Registers what to do when another instance reports a change. */
    public void onInvalidate(Consumer<UUID> handler) {
        this.handler = handler;
    }

    /**
     * Announces that the roles cached for a document are stale.
     *
     * <p>Failure is swallowed for the same reason {@link ClusterBus#publish} swallows it, but the
     * consequence differs and is worth naming: there, a lost message costs propagation delay and
     * anti-entropy repairs it; here, a lost message costs the other instances up to one cache TTL
     * of stale access, and nothing repairs it because the TTL <i>is</i> the repair.
     */
    public void publish(UUID documentId) {
        try {
            redis.convertAndSend(CHANNEL, instanceId + " " + documentId);
        } catch (RuntimeException e) {
            log.warn("Could not broadcast permission invalidation for document {}; other "
                + "instances will see the change when their cache expires: {}", documentId, e.toString());
        }
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        int separator = body.indexOf(' ');
        if (separator < 0) {
            log.warn("Unreadable permission invalidation, ignoring: {}", body);
            return;
        }
        if (instanceId.equals(body.substring(0, separator))) {
            return;
        }
        try {
            UUID documentId = UUID.fromString(body.substring(separator + 1));
            handler.accept(documentId);
        } catch (IllegalArgumentException e) {
            log.warn("Permission invalidation with an unparseable document id, ignoring: {}", body);
        }
    }
}
