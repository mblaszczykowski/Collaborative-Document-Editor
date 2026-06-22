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

/**
 * The entry point for operations from clients.
 *
 * <p>The controller is deliberately thin — all the logic lives in {@link DocumentService}. The
 * practical reason: the write path from the cluster design has five steps whose order matters, and spreading
 * them across annotated controller methods would stop that order being visible in one place.
 */
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

    /**
     * An operation from a client.
     *
     * <p>A rejection with the {@code RESYNC_REQUIRED} code is the situation from the tombstone-collection design: the
     * client was offline for longer than the GC window and its operation points at a tombstone that
     * no longer exists. The information about where those characters sat is irrecoverably lost, so
     * <b>there is no honest way to merge it</b>. Appending the text to the end of the document
     * would be silent corruption of the content, and ignoring it silent loss of work. We refuse
     * explicitly.
     */
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

        // Refused HERE rather than in the domain layer, because the answer depends on which
        // sessions are connected and that is transport state. A live session holding this
        // identifier, under a different user, means the sender is minting operations as somebody
        // else — and the collision that follows on (replicaId, lamport) swallows the real owner's
        // work as a duplicate, silently.
        //
        // Nobody holding it is ACCEPTED, and that is the whole reason the question is about
        // liveness: an offline buffer replayed after the tab was closed arrives under an
        // identifier whose session is long gone, and it must be accepted. See
        // SessionLifecycle.userOfReplica.
        String liveOwner = sessions.userOfReplica(op.id().replicaId());
        if (liveOwner != null && !liveOwner.equals(userId)) {
            log.warn("Refused operation {} in document {}: replica {} is in use by another "
                + "connected session", op.id(), documentId, op.id().replicaId());
            metrics.recordReplicaIdConflict();
            sendError(headers, "REPLICA_ID_CONFLICT",
                "replica id " + op.id().replicaId() + " is in use by another connected session");
            return;
        }
        // The span is opened HERE rather than in the domain layer: this is the point at which the
        // operation enters the system and where the frame headers carrying the trace context sent
        // by the browser are still available.
        // The catch is INSIDE the resource block, and that is the whole point of the shape.
        // Java closes a try-with-resources resource before the catch clause runs, so with the
        // catch outside, the span had already ended by the time the exception was visible — and
        // EditSpan.fail, which exists to mark it, was unreachable from anywhere in the codebase.
        // A RESYNC_REQUIRED refusal and a clean merge were indistinguishable in Tempo, on the one
        // path a trace is most wanted for.
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

    /**
     * The operation type name for the trace attribute.
     *
     * <p>An exhaustive {@code switch} for the same reason as in {@code OperationStore}: a ternary
     * {@code instanceof} would keep compiling when a {@link Op} variant is added and would describe
     * new operations under the wrong name. The consequence is milder than there (misleading
     * diagnostic data rather than log corruption), but it is discovered just as late.
     */
    private static String opTypeName(Op op) {
        return switch (op) {
            case Op.Insert ignored -> "insert";
            case Op.Delete ignored -> "delete";
            case Op.AddMark ignored -> "mark.add";
            case Op.RemoveMark ignored -> "mark.remove";
        };
    }

    /**
     * A request for the missing operations after reconnecting.
     *
     * <p>The client states the last sequence number it definitely holds. Sending it operations it
     * has already seen is harmless (the idempotence from the CRDT decision), so the cursor may be
     * conservative — better a few duplicates than one lost operation.
     */
    @MessageMapping("/doc/{documentId}/sync")
    public void onSyncRequest(@DestinationVariable("documentId") UUID documentId,
                              Messages.SyncRequest request,
                              SimpMessageHeaderAccessor headers) {
        // The head is read BEFORE the operations, and the order is not free. Two separate queries:
        // anything committed between them is counted in the head but absent from the list, so the
        // client would set its cursor above operations it was never sent. Read this way round the
        // error can only go the other way — head <= the last operation returned — and a cursor
        // that is too low costs harmless duplicates, which is the trade this endpoint's javadoc
        // already makes explicitly.
        long head = documents.headServerSeq(documentId);

        // ONE MORE than we are prepared to send, and that extra row is the whole mechanism.
        //
        // `sinceServerSeq` comes from the client and was neither validated nor bounded: the query
        // behind it had no LIMIT, every row was decoded into an Op, and the chunker then re-encoded
        // each one — all of it live at once. That is an attack surface, and it is also the ORDINARY
        // path, because the shipped client sends 0 whenever it detects a gap. A 250k-operation
        // document paid its entire log for a routine reconnect.
        //
        // Reading cap+1 distinguishes "exactly the cap" from "more than the cap" without a second
        // query, and the difference decides between answering and refusing.
        int cap = properties.persistence().maxSyncOperations();
        List<StoredOperation> missing =
            documents.operationsSince(documentId, request.sinceServerSeq(), cap + 1);

        // The version vector the client reports is used ONLY to compute causal stability for GC
        //. The choice of which operations to send back is based on server_seq — these
        // are two different orderings, and mixing them is a classic mistake.
        //
        // Reported BEFORE the size decision below, deliberately: a client that has to fall back to
        // the snapshot endpoint has still told us what it has seen, and discarding that would stall
        // GC for the whole document on exactly the clients that are furthest behind.
        if (request.version() != null && !request.version().isEmpty()) {
            int replicaId = SessionAttributes.replicaId(headers);
            registry.get(documentId).reportProgress(
                replicaId, VersionVector.of(request.version()), System.currentTimeMillis());
        }

        if (missing.size() > cap) {
            // NOT a truncated answer. A partial response that looked complete would be the CP8
            // failure again — the client that needs the data most silently receiving less than it
            // asked for — and this endpoint has no way to say "there is more" that the client is
            // required to act on: SyncResponse.last exists to mark the final chunk, and a client
            // that received every chunk has no reason to doubt it.
            //
            // The snapshot endpoint is the right answer rather than a consolation. It is O(document
            // size) instead of O(log size), which is exactly the trade CP24 built it for, and a
            // document with this many operations above the client's cursor is precisely where the
            // two costs diverge most.
            log.info("Refusing a resynchronisation of document {} from server_seq {}: more than {} "
                    + "operations to send — the client is told to re-open through the snapshot",
                documentId, request.sinceServerSeq(), cap);
            sendError(headers, "SNAPSHOT_REQUIRED",
                "too far behind to resynchronise operation by operation — re-open the document "
                    + "through GET /api/documents/{id}/snapshot");
            return;
        }

        sendSync(headers, head, missing);
    }

    /**
     * Joining a document.
     *
     * <p>A separate message rather than a reaction to {@code SUBSCRIBE}, because a client
     * subscribes to several topics of one document (operations, acknowledgements, presence) and
     * reacting to a subscription would mean guessing which of them means "I am here".
     */
    @MessageMapping("/doc/{documentId}/join")
    public void onJoin(@DestinationVariable("documentId") UUID documentId, SimpMessageHeaderAccessor headers) {
        // Loading the replica here is NECESSARY, not an optimisation.
        //
        // An instance discards cluster messages about documents it does not hold in memory
        // (— there is no reason to multiply memory for a document with no clients).
        // Without this line, an instance a client had merely JOINED never loaded the replica, so it
        // consistently ignored every operation from the cluster and the client saw not one of
        // anybody else's changes.
        //
        // The bug was invisible to every earlier test, because each of them had all clients on ONE
        // instance. Only the two-instance chaos test (ClusterChaosIT) found it.
        registry.get(documentId);

        messaging.convertAndSendToUser(headers.getSessionId(), "/queue/session",
            new Messages.SessionInfo(
                SessionAttributes.replicaId(headers), SessionAttributes.userId(headers)),
            SessionAttributes.forUser(headers));

        // Through SessionLifecycle rather than straight into PresenceService, and that indirection
        // is the fix for a race rather than a layer. The inbound channel is a multi-threaded
        // executor with no preserveReceiveOrder, so this frame can be dispatched AFTER the same
        // session's teardown has already run — and PresenceService.join would then re-create an
        // entry that nothing will ever remove, because the disconnect it would have been removed by
        // has been and gone. The lifecycle refuses a join for a session it has already ended,
        // atomically with ending it.
        sessions.joined(
            headers.getSessionId(),
            documentId,
            SessionAttributes.replicaId(headers),
            SessionAttributes.userId(headers),
            SessionAttributes.displayName(headers),
            SessionAttributes.color(headers));
    }

    /**
     * A replica's periodic progress report.
     *
     * <p>It exists solely so that tombstone GC can happen at all. Causal stability is
     * the common part of the version vectors of every live replica — without regular reports the
     * server only knows the state as of the last resynchronisation, the GC boundary stands still,
     * and tombstones grow without bound.
     *
     * <p>A separate channel rather than attaching this to resynchronisation: resynchronisation
     * queries the database for missing operations, whereas a progress report is meant to be cheap
     * and frequent.
     */
    @MessageMapping("/doc/{documentId}/progress")
    public void onProgress(@DestinationVariable("documentId") UUID documentId,
                           Messages.SyncRequest report,
                           SimpMessageHeaderAccessor headers) {
        if (report.version() == null || report.version().isEmpty()) {
            return;
        }
        registry.get(documentId).reportProgress(
            SessionAttributes.replicaId(headers),
            VersionVector.of(report.version()),
            System.currentTimeMillis());

        // NOTE: we deliberately do NOT push operations here based on the reported cursor.
        //
        // It was tempting to compare the client's `sinceServerSeq` with the head of the log and
        // push the difference — but the client's cursor advances on ACKNOWLEDGEMENTS, which are
        // broadcast to everyone. A client that lost an operation's broadcast but received its
        // acknowledgement would therefore report an "up to date" cursor despite the missing
        // operation. The mechanism would look like it worked and would do nothing.
        //
        // Gap detection lives on the client and rests on an acknowledgement carrying the
        // operation's IDENTITY: the client checks whether it holds the operation whose
        // acknowledgement just arrived (see connection.ts). If not, it asks for a resync itself.
    }

    // syncBudgetBytes used to sit here, deriving "half of the transport decision's 64 KiB" from the
    // configuration — while BroadcastWiring had the same number as a literal and the same
    // derivation in its javadoc. Both now come from FrameChunks, which is the only place that
    // arithmetic exists.

    /**
     * Sends back the missing operations — <b>in chunks</b>.
     *
     * <p>The first version sent them in one message, and that was a real bug, discovered only by
     * the load harness (CP8): the response for a 100k-character document exceeded the frame size
     * limit from the transport decision, so <b>the client that needed the data most could not receive it</b>. The
     * symptom was some clients permanently stalling a few operations behind the server — with no
     * error in the log at all.
     *
     * <p>The order of the chunks does not matter for correctness (merging is commutative), but
     * <b>whether they all arrived does</b>. Only the last one is marked {@code last}, and only that
     * one moves the client's cursor.
     *
     * <p>This javadoc used to claim that "a chunk lost in transit will be detected by the same
     * mechanism as the original gap". It would not have been. The only gap detector on the client
     * fires when an ACKNOWLEDGEMENT arrives for an operation it does not hold, and operations in a
     * sync response were acknowledged long ago and are never acknowledged again. Every chunk
     * carried the same head and the client advanced on each one, so losing chunk 2 of 5 left it
     * believing it was up to date — permanently, on a document that then went quiet. That is the
     * CP8 failure ("the client that needed the data most could not get it, with no error anywhere")
     * reintroduced at chunk granularity by the CP8 fix itself.
     */
    private void sendSync(SimpMessageHeaderAccessor headers, long head,
                          List<StoredOperation> operations) {
        if (operations.isEmpty()) {
            messaging.convertAndSendToUser(headers.getSessionId(), "/queue/sync",
                new Messages.SyncResponse(List.of(), head, true), SessionAttributes.forUser(headers));
            return;
        }
        // Encoded ONCE, here, and the chunker measures the objects it emits. This used to call
        // OpCodec.encode to measure and OpCodec.toJson to emit, so a full resynchronisation of a
        // large document serialised every operation twice — double the CPU and double the transient
        // garbage, on the most fragile path in the protocol.
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
        presence.updateCursor(headers.getSessionId(), update);
    }

    private void sendError(SimpMessageHeaderAccessor headers, String code, String message) {
        messaging.convertAndSendToUser(headers.getSessionId(), "/queue/errors",
            new Messages.ErrorMessage(code, message), SessionAttributes.forUser(headers));
    }
}
