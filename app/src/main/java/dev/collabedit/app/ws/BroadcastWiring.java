package dev.collabedit.app.ws;

import com.fasterxml.jackson.databind.JsonNode;
import dev.collabedit.app.auth.PermissionService;
import dev.collabedit.app.config.CollabEditProperties;
import dev.collabedit.app.domain.DocumentService;
import dev.collabedit.crdt.OpCodec;
import jakarta.annotation.PostConstruct;
import java.util.List;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

@Component
public class BroadcastWiring {
    private final DocumentService documents;
    private final PermissionService permissions;
    private final SimpMessagingTemplate messaging;

    private final CollabEditProperties properties;

    public BroadcastWiring(DocumentService documents, PermissionService permissions,
                           SimpMessagingTemplate messaging, CollabEditProperties properties) {
        this.documents = documents;
        this.permissions = permissions;
        this.messaging = messaging;
        this.properties = properties;
    }

    @PostConstruct
    void wire() {
        documents.onFastPath((documentId, ops) -> {
            List<JsonNode> encoded =
                ops.stream().<JsonNode>map(OpCodec::toJson).toList();
            for (List<JsonNode> chunk
: FrameChunks.byBytes(encoded, FrameChunks.budgetBytes(properties))) {
                messaging.convertAndSend("/topic/doc/" + documentId + "/ops",
                    chunk.stream().map(Messages.OpBroadcast::new).toList());
            }
        });

        documents.onAcknowledged((documentId, stored) -> {
            List<Messages.OpAck> payload = stored.stream()
                .map(entry -> new Messages.OpAck(
                    entry.op().id().replicaId(),
                    entry.op().id().lamport(),
                    entry.serverSeq()))
                .toList();
            messaging.convertAndSend("/topic/doc/" + documentId + "/acks", payload);
        });

        permissions.onAccessChanged(documentId ->
            messaging.convertAndSend("/topic/doc/" + documentId + "/access",
                new Messages.AccessChanged()));
    }
}
