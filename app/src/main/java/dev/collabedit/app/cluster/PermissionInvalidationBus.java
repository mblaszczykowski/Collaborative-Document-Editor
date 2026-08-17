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

@Component
public class PermissionInvalidationBus implements MessageListener {
    private static final Logger log = LoggerFactory.getLogger(PermissionInvalidationBus.class);

    public static final String CHANNEL = "collabedit:permissions:invalidate";

    private final String instanceId;

    private final StringRedisTemplate redis;
    private volatile Consumer<UUID> handler = documentId -> { };

    public PermissionInvalidationBus(StringRedisTemplate redis, InstanceIdentity identity) {
        this.redis = redis;
        this.instanceId = identity.id();
    }

    public String instanceId() {
        return instanceId;
    }

    public void onInvalidate(Consumer<UUID> handler) {
        this.handler = handler;
    }

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
