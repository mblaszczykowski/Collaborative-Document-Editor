package dev.collabedit.app.ws;

import com.fasterxml.jackson.databind.JsonNode;
import dev.collabedit.app.domain.DocumentRegistry;
import dev.collabedit.app.domain.DocumentService;
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
 * practical reason: the write path from ADR-0004 has five steps whose order matters, and spreading
 * them across annotated controller methods would stop that order being visible in one place.
 */
@Controller
public class DocumentStompController {

    private static final Logger log = LoggerFactory.getLogger(DocumentStompController.class);

    private final DocumentService documents;
    private final DocumentRegistry registry;
    private final PresenceService presence;
    private final SimpMessagingTemplate messaging;
    private final EditTracer tracer;
    private final dev.collabedit.app.config.CollabEditProperties properties;

    public DocumentStompController(DocumentService documents, DocumentRegistry registry,
                                   PresenceService presence, SimpMessagingTemplate messaging,
                                   EditTracer tracer,
                                   dev.collabedit.app.config.CollabEditProperties properties) {
        this.documents = documents;
        this.registry = registry;
        this.presence = presence;
        this.messaging = messaging;
        this.tracer = tracer;
        this.properties = properties;
    }

    /**
     * An operation from a client.
     *
     * <p>A rejection with the {@code RESYNC_REQUIRED} code is the situation from ADR-0005: the
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
        // The span is opened HERE rather than in the domain layer: this is the point at which the
        // operation enters the system and where the frame headers carrying the trace context sent
        // by the browser are still available.
        try (var span = tracer.startReceive(headers, documentId,
            SessionAttributes.replicaId(headers), opTypeName(op))) {
            span.attribute("collabedit.op.lamport", op.id().lamport());
            documents.submit(documentId, op, userId);
        } catch (DocumentService.ResyncRequiredException e) {
            log.info("Rejected operation {} in document {}: {}", op.id(), documentId, e.getMessage());
            sendError(headers, "RESYNC_REQUIRED", e.getMessage());
        } catch (RuntimeException e) {
            log.warn("Rejected operation {} in document {}: {}", op.id(), documentId, e.toString());
            sendError(headers, "OPERATION_REJECTED", e.getMessage());
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
     * has already seen is harmless (the idempotence from ADR-0001), so the cursor may be
     * conservative — better a few duplicates than one lost operation.
     */
    @MessageMapping("/doc/{documentId}/sync")
    public void onSyncRequest(@DestinationVariable("documentId") UUID documentId,
                              Messages.SyncRequest request,
                              SimpMessageHeaderAccessor headers) {
        List<StoredOperation> missing = documents.operationsSince(documentId, request.sinceServerSeq());

        // The version vector the client reports is used ONLY to compute causal stability for GC
        // (ADR-0005). The choice of which operations to send back is based on server_seq — these
        // are two different orderings, and mixing them is a classic mistake.
        if (request.version() != null && !request.version().isEmpty()) {
            int replicaId = SessionAttributes.replicaId(headers);
            registry.get(documentId).reportProgress(
                replicaId, VersionVector.of(request.version()), System.currentTimeMillis());
        }

        sendSync(headers, documentId, missing);
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
        // (ADR-0004 — there is no reason to multiply memory for a document with no clients).
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

        presence.join(
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
     * <p>It exists solely so that tombstone GC can happen at all (ADR-0005). Causal stability is
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

    /**
     * How much of a resynchronisation response fits in one frame — <b>in bytes</b> (CP28).
     *
     * <h2>What this replaces, and why counting operations was wrong</h2>
     * This used to be {@code SYNC_CHUNK = 300}, a count, justified by "an operation is on the order
     * of 60–100 bytes". That assumption holds for typing and fails for the one gesture every user
     * performs in their first minute: <b>pasting</b>. A pasted chapter is ONE operation of tens of
     * kilobytes, so three hundred of them is megabytes in a frame limited to 64 KiB — and the
     * failure mode is the one CP8 already found once, with the client that needs the data most
     * being the one that cannot receive it, silently.
     *
     * <p>The broadcast path moved to a byte budget in CP10 and the resynchronisation path did not.
     * The README has carried that as an open item ever since; this is it closed.
     *
     * <h2>Half the frame limit</h2>
     * The same margin the broadcast path uses. The budget is measured on the encoded operations,
     * and the frame adds an envelope on top — a JSON wrapper, STOMP headers, and whatever the
     * broker prepends. Budgeting to the exact limit means discovering the envelope's size in
     * production, one dropped session at a time.
     */
    private int syncBudgetBytes() {
        return properties.websocket().maxMessageSizeBytes() / 2;
    }

    /**
     * Sends back the missing operations — <b>in chunks</b>.
     *
     * <p>The first version sent them in one message, and that was a real bug, discovered only by
     * the load harness (CP8): the response for a 100k-character document exceeded the frame size
     * limit from ADR-0002, so <b>the client that needed the data most could not receive it</b>. The
     * symptom was some clients permanently stalling a few operations behind the server — with no
     * error in the log at all.
     *
     * <p>The order of the chunks does not matter for correctness (merging is commutative), and a
     * chunk lost in transit will be detected by the same mechanism as the original gap.
     */
    private void sendSync(SimpMessageHeaderAccessor headers, UUID documentId,
                          List<StoredOperation> operations) {
        long head = documents.headServerSeq(documentId);
        if (operations.isEmpty()) {
            messaging.convertAndSendToUser(headers.getSessionId(), "/queue/sync",
                new Messages.SyncResponse(List.of(), head), SessionAttributes.forUser(headers));
            return;
        }
        for (List<JsonNode> chunk : chunkByBytes(operations, syncBudgetBytes())) {
            messaging.convertAndSendToUser(headers.getSessionId(), "/queue/sync",
                new Messages.SyncResponse(chunk, head), SessionAttributes.forUser(headers));
        }
    }

    /**
     * Splits operations into frames that fit a byte budget (CP28).
     *
     * <p>Package-private and static so a unit test can look at the chunks directly. That is not
     * convenience: the integration test cannot catch this, because the test client raises its own
     * inbound limit to 1 MB — a limit it raises for a good reason (its DEFAULT 8 KiB buffer was
     * the flaw in the measurement rig that CP8 spent a checkpoint chasing), and which means an
     * oversized frame arrives there perfectly well and only fails in a browser.
     *
     * <p>An operation larger than the whole budget is sent alone and may still exceed the frame
     * limit. That is a real remaining limit rather than something to pretend about: nothing here
     * can split an operation, because its identity is what the merge algorithm is built on. The
     * client is what must not create one — see the paste path in {@code bridge.ts}.
     */
    static List<List<JsonNode>> chunkByBytes(List<StoredOperation> operations, int budget) {
        List<List<JsonNode>> chunks = new java.util.ArrayList<>();
        List<JsonNode> current = new java.util.ArrayList<>();
        int bytes = 0;

        for (StoredOperation stored : operations) {
            // Bytes, not characters. The budget is a FRAME size and a frame is bytes, while
            // `String.length()` counts UTF-16 units — for Polish text that is about 10% short,
            // for CJK three times short. The margin (half the frame limit) hid it; a document in
            // a non-Latin script would have found it in production instead.
            int size = OpCodec.encode(stored.op())
                .getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (!current.isEmpty() && bytes + size > budget) {
                chunks.add(current);
                current = new java.util.ArrayList<>();
                bytes = 0;
            }
            current.add(OpCodec.toJson(stored.op()));
            bytes += size;
        }
        if (!current.isEmpty()) {
            chunks.add(current);
        }
        return chunks;
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
