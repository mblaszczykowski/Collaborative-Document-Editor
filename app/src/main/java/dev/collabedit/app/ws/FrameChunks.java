package dev.collabedit.app.ws;

import com.fasterxml.jackson.databind.JsonNode;
import dev.collabedit.app.config.CollabEditProperties;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Splitting encoded operations into frames that fit a byte budget — <b>the only copy</b>.
 *
 * <h2>Why this class exists</h2>
 * There were two of these. {@code BroadcastWiring} had a {@code FRAME_BUDGET_BYTES = 32 * 1024}
 * pinned in the source, and {@code DocumentStompController.syncBudgetBytes} derived the identical
 * number from {@code max-message-size-bytes / 2}. Both javadocs recited the same derivation — "half
 * of the transport decision's 64 KiB, leaving room for the envelope" — so the two agreed by coincidence, and
 * raising {@code max-message-size-bytes} in the configuration would have moved one and not the
 * other. A number that is documented as derived and is actually a literal is worse than either,
 * because the documentation tells the next reader that changing the setting is enough.
 *
 * <p>They also disagreed on something already: only the broadcast path accounted for the per-entry
 * envelope, so the two chunkers budgeted differently for the same operations. That is now one rule
 * applied in both places, which also means {@code SyncChunkingTest} covers the broadcast path — the
 * path where CP10 introduced the need for chunking in the first place.
 *
 * <h2>Why bytes rather than a count of operations</h2>
 * Counting operations is wrong <b>in principle</b>, not merely at the wrong value: an operation
 * carries text of arbitrary length, so pasting a large fragment is one operation weighing tens of
 * kilobytes. A limit of "300 operations" would then mean a multi-megabyte frame — and the failure
 * mode is silent, which is what CP8 spent a checkpoint discovering: the frame simply never arrives,
 * the client that needed the data most is the one that cannot receive it, and nothing is logged on
 * either side.
 *
 * <p>Package-private and static. A shared chunker does not need an interface, a bean or a strategy
 * — it needs to exist once.
 */
final class FrameChunks {

    private FrameChunks() {
    }

    /**
     * Headroom for a single entry's envelope: {@code {"op":…},} and the STOMP framing around the
     * list.
     *
     * <p>An estimate rather than a measurement, and deliberately generous. The exact envelope
     * differs between the two callers — a broadcast wraps each operation in
     * {@code {"op": …}} while a sync response sends the bare object — and budgeting to the precise
     * byte on either would mean discovering the difference in production, one dropped session at a
     * time. Over-reserving costs an extra frame occasionally; under-reserving costs a frame that
     * never arrives.
     */
    private static final int ENTRY_OVERHEAD_BYTES = 32;

    /**
     * How much of one frame may be filled with operations.
     *
     * <h2>Half the frame limit, and where the halving comes from</h2>
     * The transport decision sets 64 KiB as the single-message limit. The budget is measured on the encoded
     * operations, and the frame adds an envelope on top of them — a JSON wrapper, STOMP headers,
     * and whatever the broker prepends — whose exact size we do not compute. Budgeting to the
     * exact limit means discovering the envelope's size in production.
     *
     * <p>Derived from the configured limit rather than pinned, so that raising
     * {@code max-message-size-bytes} raises this too. That is the half {@code BroadcastWiring} did
     * not do.
     */
    static int budgetBytes(CollabEditProperties properties) {
        return properties.websocket().maxMessageSizeBytes() / 2;
    }

    /**
     * Splits encoded operations into frames.
     *
     * <p>An operation larger than the whole budget is sent alone and may still exceed the frame
     * limit. That is a real remaining limit rather than something to pretend about: nothing here
     * can split an operation, because its identity is what the merge algorithm is built on, and
     * dropping it would leave the client missing text with nothing to retry. The client is what
     * must not create one — see the paste path in {@code bridge.ts}, and the {@code @Size} bounds
     * on the REST endpoints that generate operations server-side.
     *
     * @param encoded the operations, already serialised — measured and emitted from the same
     * object, so a full resynchronisation of a large document does not serialise
     * every operation twice
     */
    static List<List<JsonNode>> byBytes(List<JsonNode> encoded, int budget) {
        List<List<JsonNode>> chunks = new ArrayList<>();
        List<JsonNode> current = new ArrayList<>();
        int bytes = 0;

        for (JsonNode node : encoded) {
            // Bytes, not characters. The budget is a FRAME size and a frame is bytes, while
            // `String.length` counts UTF-16 units — for Polish text that is about 10% short, for
            // CJK three times short. The margin above hid it; a document in a non-Latin script
            // would have found it in production instead.
            int size = node.toString().getBytes(StandardCharsets.UTF_8).length
                + ENTRY_OVERHEAD_BYTES;
            if (!current.isEmpty() && bytes + size > budget) {
                chunks.add(current);
                current = new ArrayList<>();
                bytes = 0;
            }
            current.add(node);
            bytes += size;
        }
        if (!current.isEmpty()) {
            chunks.add(current);
        }
        return chunks;
    }
}
