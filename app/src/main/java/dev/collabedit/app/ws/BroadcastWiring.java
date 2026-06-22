package dev.collabedit.app.ws;

import com.fasterxml.jackson.databind.JsonNode;
import dev.collabedit.app.config.CollabEditProperties;
import dev.collabedit.app.domain.DocumentService;
import dev.collabedit.crdt.OpCodec;
import jakarta.annotation.PostConstruct;
import java.util.List;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * Connects the domain layer to the transport.
 *
 * <p>It exists so that {@link DocumentService} — where the ordering of steps from the cluster design is
 * written down — does not depend on {@code SimpMessagingTemplate}. That makes the write path
 * testable without standing up the whole WebSocket stack, and a change of transport (should
 * the transport decision ever be revised) touches one file rather than the heart of the logic.
 */
@Component
public class BroadcastWiring {

    private final DocumentService documents;
    private final SimpMessagingTemplate messaging;

    /**
     * Injected for one value: the frame budget.
     *
     * <p>The budget used to be {@code FRAME_BUDGET_BYTES = 32 * 1024}, pinned here, with a javadoc
     * saying it was half of the transport decision's 64 KiB — while {@code DocumentStompController} derived the
     * identical number from {@code max-message-size-bytes / 2}. Two numbers documented as the same
     * derivation, only one of which followed the configuration: raising the limit moved the sync
     * path and left the broadcast path where it was. See {@link FrameChunks}.
     */
    private final CollabEditProperties properties;

    public BroadcastWiring(DocumentService documents, SimpMessagingTemplate messaging,
                           CollabEditProperties properties) {
        this.documents = documents;
        this.messaging = messaging;
        this.properties = properties;
    }

    /**
     * Why splitting is here at all.
     *
     * <p>With one-operation-per-frame broadcasting (up to CP10) the frame limit was unreachable,
     * which is why there was no splitting. Coalescing broadcasts changed that assumption — and with
     * 50 clients typing at once a batch can grow arbitrarily. It is the same class of bug CP8 found
     * in the resynchronisation response: the frame simply never arrives, with no trace
     * on the server side.
     *
     * <p>The rule itself lives in {@link FrameChunks} now, shared with the resynchronisation path,
     * which means {@code SyncChunkingTest} covers this path too.
     */
    @PostConstruct
    void wire() {
        // Step 2 from the cluster design: broadcast after the merge, BEFORE the write.
        // This is the step responsible for what the user sees.
        documents.onFastPath((documentId, ops) -> {
            List<JsonNode> encoded =
                ops.stream().<JsonNode>map(OpCodec::toJson).toList();
            for (List<JsonNode> chunk
: FrameChunks.byBytes(encoded, FrameChunks.budgetBytes(properties))) {
                messaging.convertAndSend("/topic/doc/" + documentId + "/ops",
                    chunk.stream().map(Messages.OpBroadcast::new).toList());
            }
        });

        // Step 4: the acknowledgement AFTER the write. Only this releases an operation from the
        // client's offline buffer and advances its resynchronisation cursor.
        //
        // No splitting here: an acknowledgement has a FIXED size (three numbers), and the write
        // batch is bounded by op-batch-max-size. A hundred acknowledgements is ~4 KB, an order of
        // magnitude under the limit — and unlike operations, nothing here is sized by content.
        documents.onAcknowledged((documentId, stored) -> {
            List<Messages.OpAck> payload = stored.stream()
                .map(entry -> new Messages.OpAck(
                    entry.op().id().replicaId(),
                    entry.op().id().lamport(),
                    entry.serverSeq()))
                .toList();
            messaging.convertAndSend("/topic/doc/" + documentId + "/acks", payload);
        });
    }
}
