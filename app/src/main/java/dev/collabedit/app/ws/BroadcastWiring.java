package dev.collabedit.app.ws;

import dev.collabedit.app.domain.DocumentService;
import dev.collabedit.crdt.Op;
import dev.collabedit.crdt.OpCodec;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * Connects the domain layer to the transport.
 *
 * <p>It exists so that {@link DocumentService} — where the ordering of steps from ADR-0004 is
 * written down — does not depend on {@code SimpMessagingTemplate}. That makes the write path
 * testable without standing up the whole WebSocket stack, and a change of transport (should
 * ADR-0002 ever be revised) touches one file rather than the heart of the logic.
 */
@Component
public class BroadcastWiring {

    private final DocumentService documents;
    private final SimpMessagingTemplate messaging;

    public BroadcastWiring(DocumentService documents, SimpMessagingTemplate messaging) {
        this.documents = documents;
        this.messaging = messaging;
    }

    /**
     * The byte budget for a single broadcast frame.
     *
     * <h2>Why bytes rather than a count of operations</h2>
     * Counting operations is wrong <b>in principle</b>, not merely at the wrong value: an operation
     * carries text of arbitrary length, so pasting a large fragment is one operation weighing tens
     * of kilobytes. A limit of "300 operations" would then mean a multi-megabyte frame. The only
     * quantity we actually care about bounding is the frame size — so we bound it directly.
     *
     * <h2>Where 32 KiB comes from</h2>
     * ADR-0002 sets 64 KiB as the single-message limit. Half of that leaves room for the STOMP and
     * JSON envelopes around the operations themselves, whose exact size we do not compute. A single
     * operation larger than the budget goes out in a frame of its own — nothing better can be done,
     * and the server's inbound limit would not have accepted a larger one anyway.
     *
     * <h2>Why splitting is here at all</h2>
     * With one-operation-per-frame broadcasting (up to CP10) the limit was unreachable, which is
     * why there was no splitting. Coalescing broadcasts changed that assumption — and with 50
     * clients typing at once a batch can grow arbitrarily. It is the same class of bug CP8 found in
     * the resynchronisation response (ADR-0002): the frame simply never arrives, with no trace on
     * the server side.
     */
    private static final int FRAME_BUDGET_BYTES = 32 * 1024;

    /** Headroom for a single entry's envelope: {@code {"op":...,"serverSeq":null},}. */
    private static final int ENTRY_OVERHEAD_BYTES = 32;

    @PostConstruct
    void wire() {
        // Step 2 from ADR-0004: broadcast after the merge, BEFORE the write.
        // This is the step responsible for what the user sees.
        documents.onFastPath((documentId, ops) -> {
            List<Messages.OpBroadcast> frame = new ArrayList<>();
            int bytes = 0;
            for (Op op : ops) {
                var json = OpCodec.toJson(op);
                int size = json.toString().getBytes(StandardCharsets.UTF_8).length
                    + ENTRY_OVERHEAD_BYTES;
                if (!frame.isEmpty() && bytes + size > FRAME_BUDGET_BYTES) {
                    messaging.convertAndSend("/topic/doc/" + documentId + "/ops", frame);
                    frame = new ArrayList<>();
                    bytes = 0;
                }
                frame.add(new Messages.OpBroadcast(json, null));
                bytes += size;
            }
            if (!frame.isEmpty()) {
                messaging.convertAndSend("/topic/doc/" + documentId + "/ops", frame);
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
