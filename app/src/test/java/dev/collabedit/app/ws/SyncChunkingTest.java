package dev.collabedit.app.ws;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import dev.collabedit.app.persist.StoredOperation;
import dev.collabedit.crdt.Op;
import dev.collabedit.crdt.OpCodec;
import dev.collabedit.crdt.RgaDocument;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The resynchronisation response is split by BYTES, not by operation count (CP28).
 *
 * <h2>Why this is a unit test and the integration test is not enough</h2>
 * {@code LargePasteIT} sends a large document through the whole stack and gets it back, and it
 * would have passed on the old code as well — because {@code TestStompClient} raises its inbound
 * limit to 1 MB. It raises it for a good reason: its DEFAULT 8 KiB buffer was the flaw in the
 * measurement rig that CP8 spent a checkpoint chasing, and which made "convergence stops being
 * repeatable above ten clients" look like a property of the system.
 *
 * <p>So the rig is deliberately more forgiving than a browser, and an oversized frame passes
 * through it unnoticed. The only place the rule can be checked is where the rule is.
 *
 * <h2>It now covers the BROADCAST path too, which is the point of the shared chunker</h2>
 * There were two chunkers: this one, whose budget was derived from
 * {@code max-message-size-bytes / 2}, and {@code BroadcastWiring}'s, whose budget was the literal
 * {@code 32 * 1024} with a javadoc reciting the same derivation. They agreed by coincidence and
 * would have diverged the moment the configured limit changed, and only the broadcast one
 * accounted for the per-entry envelope. {@link FrameChunks} is the single copy, so every assertion
 * below is now about both paths.
 *
 * <p>That unification is why some numbers here moved: the sync path did not use to reserve
 * {@code ENTRY_OVERHEAD_BYTES} per entry and now does. The properties being asserted are unchanged
 * — chunking responds to SIZE and not to count, bytes are counted rather than UTF-16 units, and an
 * operation too large for any frame is sent rather than dropped.
 */
class SyncChunkingTest {

    /** Operations of a given size, as pasting produces them. */
    private static List<JsonNode> operations(int count, int charactersEach) {
        return operations(count, charactersEach, "x");
    }

    private static List<JsonNode> operations(int count, int charactersEach, String character) {
        RgaDocument document = new RgaDocument(1);
        List<StoredOperation> stored = new ArrayList<>();
        String piece = character.repeat(charactersEach);
        for (int i = 0; i < count; i++) {
            Op op = document.insert(i * charactersEach, piece);
            stored.add(new StoredOperation(op, i + 1, null, java.time.Instant.EPOCH));
        }
        // Encoded here, because that is what the shared chunker takes: both callers serialise once
        // and the chunker measures the objects it emits, rather than encoding to measure and
        // encoding again to send.
        return stored.stream().<JsonNode>map(entry -> OpCodec.toJson(entry.op())).toList();
    }

    private static int bytesOf(List<JsonNode> chunk) {
        return chunk.stream().mapToInt(node -> node.toString().length()).sum();
    }

    @Test
    @DisplayName("large operations are split across frames, however few of them there are")
    void largeOperationsAreSplitAcrossFrames() {
        int budget = 32 * 1024;
        List<List<JsonNode>> chunks = FrameChunks.byBytes(operations(20, 8_000), budget);

        assertThat(chunks)
            .as("twenty operations is well under the old limit of 300, so the count-based rule "
                + "put all 160 KB of them in ONE frame against a 64 KiB limit — and the client "
                + "that needed them most was the one that could not receive them")
            .hasSizeGreaterThan(4);

        for (List<JsonNode> chunk : chunks) {
            assertThat(bytesOf(chunk)).isLessThanOrEqualTo(budget + 8_100);
        }
    }

    @Test
    @DisplayName("many small operations still travel in few frames")
    void smallOperationsAreNotFragmented() {
        // The old rule was not wrong about typing, and the new one must not be worse at it:
        // a thousand single-character operations are ~100 KB of JSON, so three or four frames.
        List<List<JsonNode>> chunks = FrameChunks.byBytes(operations(1_000, 1), 32 * 1024);

        assertThat(chunks).hasSizeLessThan(8);
        assertThat(chunks.stream().mapToInt(List::size).sum()).isEqualTo(1_000);
    }

    @Test
    @DisplayName("one operation bigger than the whole budget is sent alone, not dropped")
    void anOversizedOperationIsStillSent() {
        List<List<JsonNode>> chunks = FrameChunks.byBytes(operations(1, 100_000), 32 * 1024);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst()).hasSize(1);
        // It may still exceed the frame limit — nothing here can split an operation, because its
        // identity is what the merge algorithm is built on. Dropping it would be worse: the
        // client would be missing text with nothing to retry. The place that must not create one
        // is the client's paste path.
        assertThat(bytesOf(chunks.getFirst())).isGreaterThan(32 * 1024);
    }

    @Test
    @DisplayName("a document in a non-Latin script does not overflow the budget")
    void multiByteTextIsMeasuredInBytes() {
        int budget = 32 * 1024;
        // Every character is three bytes in UTF-8 and one UTF-16 unit, so String.length() reports
        // a third of the truth. With a count-of-characters budget these chunks would each be
        // three times the size they were budgeted for — hidden on Latin text, and found in
        // production by whoever writes in Chinese.
        List<JsonNode> ops = operations(20, 6_000, "\u4e2d");

        for (List<JsonNode> chunk : FrameChunks.byBytes(ops, budget)) {
            int bytes = chunk.stream()
                .mapToInt(node -> node.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                .sum();
            assertThat(bytes)
                .as("a chunk of %d bytes against a %d byte budget", bytes, budget)
                .isLessThanOrEqualTo(budget + 20_000);
        }
    }

    @Test
    @DisplayName("no operations means no frames beyond the empty answer the caller sends")
    void nothingToSendProducesNoChunks() {
        assertThat(FrameChunks.byBytes(List.of(), 32 * 1024)).isEmpty();
    }

    @Test
    @DisplayName("the encoded size is what is measured, not a guess about the operation count")
    void theBudgetIsMeasuredOnTheEncodedForm() {
        // This test used to assert that the JSON encoding of a 5000-character string is longer
        // than 5000 characters. That is true of every possible implementation of OpCodec, so it
        // could not fail — and it never called chunkByBytes, which is the thing it is named after.
        //
        // The property that matters is the one the old count-based rule got wrong: chunking has to
        // respond to the SIZE of an operation, not to how many there are. Two large operations
        // exceed the budget and must not share a frame, while a hundred tiny ones comfortably fit
        // in one.
        //
        // The numbers moved when the two chunkers became one: the sync path now reserves 32 bytes
        // of envelope per entry, as the broadcast path always did, so a hundred single-character
        // operations are ~9 KB rather than ~6 KB. The budget and the operation sizes are scaled to
        // keep the contrast — which is the thing being asserted — rather than the constants.
        int budget = 12 * 1024;

        assertThat(FrameChunks.byBytes(operations(2, 8_000), budget))
            .as("two operations far larger than the budget were put in one frame — which is what "
                + "the count-based rule did, and what made a resync of a large document exceed "
                + "the frame limit with no error anywhere")
            .hasSize(2);

        assertThat(FrameChunks.byBytes(operations(100, 1), budget))
            .as("a hundred tiny operations were split, so the budget is not being measured on the "
                + "encoded form either")
            .hasSize(1);
    }
}
