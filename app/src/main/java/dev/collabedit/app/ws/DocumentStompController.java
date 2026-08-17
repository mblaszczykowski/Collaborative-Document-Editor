package dev.collabedit.app.ws;

import com.fasterxml.jackson.databind.JsonNode;
import dev.collabedit.app.config.CollabEditProperties;
import dev.collabedit.app.domain.DocumentRegistry;
import dev.collabedit.app.domain.DocumentService;
import dev.collabedit.app.observability.DocumentMetrics;
import dev.collabedit.app.observability.EditTracer;
import dev.collabedit.app.persist.StoredOperation;
import dev.collabedit.crdt.Op;
import dev.collabedit.crdt.OpCodec;
import dev.collabedit.crdt.VersionVector;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

@Controller
public class DocumentStompController {
    private static final Logger log = LoggerFactory.getLogger(DocumentStompController.class);

    private final DocumentService documents;
    private final DocumentRegistry registry;
    private final PresenceService presence;
    private final SessionLifecycle sessions;
    private final SimpMessagingTemplate messaging;
    private final EditTracer tracer;
    private final CollabEditProperties properties;
    private final DocumentMetrics metrics;

    public DocumentStompController(DocumentService documents, DocumentRegistry registry,
                                   PresenceService presence, SessionLifecycle sessions,
                                   SimpMessagingTemplate messaging, EditTracer tracer,
                                   CollabEditProperties properties, DocumentMetrics metrics) {
        this.documents = documents;
        this.registry = registry;
        this.presence = presence;
        this.sessions = sessions;
        this.messaging = messaging;
        this.tracer = tracer;
        this.properties = properties;
        this.metrics = metrics;
    }

    @MessageMapping("/doc/{documentId}/op")
    public void onOperation(@DestinationVariable("documentId") UUID documentId,
                            Messages.ClientOp message,
                            SimpMessageHeaderAccessor headers) {
        Op op;
        try {
            op = OpCodec.fromJson(message.op());
        } catch (RuntimeException e) {
            sendError(headers, "BAD_OPERATION", "cannot read the operation: " + e.getMessage());
            return;
        }

        String userId = SessionAttributes.userId(headers);

        String liveOwner = sessions.userOfReplica(op.id().replicaId());
        if (liveOwner != null && !liveOwner.equals(userId)) {
            log.warn("Refused operation {} in document {}: replica {} is in use by another "
                + "connected session", op.id(), documentId, op.id().replicaId());
            metrics.recordReplicaIdConflict();
            sendError(headers, "REPLICA_ID_CONFLICT",
                "replica id " + op.id().replicaId() + " is in use by another connected session");
            return;
        }
        try (var span = tracer.startReceive(headers, documentId,
            SessionAttributes.replicaId(headers), opTypeName(op))) {
            span.attribute("collabedit.op.lamport", op.id().lamport());
            try {
                documents.submit(documentId, op, userId);
            } catch (DocumentService.ResyncRequiredException e) {
                span.fail(e);
                log.info("Rejected operation {} in document {}: {}",
                    op.id(), documentId, e.getMessage());
                sendError(headers, "RESYNC_REQUIRED", e.getMessage());
            } catch (RuntimeException e) {
                span.fail(e);
                log.warn("Rejected operation {} in document {}: {}",
                    op.id(), documentId, e.toString());
                sendError(headers, "OPERATION_REJECTED", e.getMessage());
            }
        }
    }

    private static String opTypeName(Op op) {
        return switch (op) {
            case Op.Insert ignored -> "insert";
            case Op.Delete ignored -> "delete";
            case Op.AddMark ignored -> "mark.add";
            case Op.RemoveMark ignored -> "mark.remove";
        };
    }

    @MessageMapping("/doc/{documentId}/sync")
    public void onSyncRequest(@DestinationVariable("documentId") UUID documentId,
                              Messages.SyncRequest request,
                              SimpMessageHeaderAccessor headers) {
        long head = documents.headServerSeq(documentId);

        int cap = properties.persistence().maxSyncOperations();
        List<StoredOperation> missing =
            documents.operationsSince(documentId, request.sinceServerSeq(), cap + 1);

        if (request.version() != null && !request.version().isEmpty()) {
            documents.recordProgress(documentId, SessionAttributes.replicaId(headers),
                VersionVector.of(request.version()));
        }

        if (missing.size() > cap) {
            log.info("Refusing a resynchronisation of document {} from server_seq {}: more than {} "
                    + "operations to send - the client is told to re-open through the snapshot",
                documentId, request.sinceServerSeq(), cap);
            sendError(headers, "SNAPSHOT_REQUIRED",
                "too far behind to resynchronise operation by operation - re-open the document "
                    + "through GET /api/documents/{id}/snapshot");
            return;
        }

        sendSync(headers, head, missing);
    }

    @MessageMapping("/doc/{documentId}/join")
    public void onJoin(@DestinationVariable("documentId") UUID documentId, SimpMessageHeaderAccessor headers) {
        registry.get(documentId);

        messaging.convertAndSendToUser(headers.getSessionId(), "/queue/session",
            new Messages.SessionInfo(
                SessionAttributes.replicaId(headers), SessionAttributes.userId(headers)),
            SessionAttributes.forUser(headers));

        sessions.joined(
            headers.getSessionId(),
            documentId,
            SessionAttributes.replicaId(headers),
            SessionAttributes.userId(headers),
            SessionAttributes.displayName(headers),
            SessionAttributes.color(headers));
    }

    @MessageMapping("/doc/{documentId}/progress")
    public void onProgress(@DestinationVariable("documentId") UUID documentId,
                           Messages.SyncRequest report,
                           SimpMessageHeaderAccessor headers) {
        if (report.version() == null || report.version().isEmpty()) {
            return;
        }
        documents.recordProgress(documentId, SessionAttributes.replicaId(headers),
            VersionVector.of(report.version()));
    }

    private void sendSync(SimpMessageHeaderAccessor headers, long head,
                          List<StoredOperation> operations) {
        if (operations.isEmpty()) {
            messaging.convertAndSendToUser(headers.getSessionId(), "/queue/sync",
                new Messages.SyncResponse(List.of(), head, true), SessionAttributes.forUser(headers));
            return;
        }
        List<JsonNode> encoded =
            operations.stream().<JsonNode>map(stored -> OpCodec.toJson(stored.op())).toList();
        List<List<JsonNode>> chunks =
            FrameChunks.byBytes(encoded, FrameChunks.budgetBytes(properties));
        for (int i = 0; i < chunks.size(); i++) {
            boolean last = i == chunks.size() - 1;
            messaging.convertAndSendToUser(headers.getSessionId(), "/queue/sync",
                new Messages.SyncResponse(chunks.get(i), head, last),
                SessionAttributes.forUser(headers));
        }
    }

    @MessageMapping("/doc/{documentId}/presence")
    public void onPresence(@DestinationVariable("documentId") UUID documentId,
                           Messages.ClientPresence update,
                           SimpMessageHeaderAccessor headers) {
        presence.updateCursor(headers.getSessionId(), documentId, update);
    }

    private void sendError(SimpMessageHeaderAccessor headers, String code, String message) {
        messaging.convertAndSendToUser(headers.getSessionId(), "/queue/errors",
            new Messages.ErrorMessage(code, message), SessionAttributes.forUser(headers));
    }
}
