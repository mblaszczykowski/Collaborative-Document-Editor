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
 */
class SyncChunkingTest {

    /** Operations of a given size, as pasting produces them. */
    private static List<StoredOperation> operations(int count, int charactersEach) {
        return operations(count, charactersEach, "x");
    }

    private static List<StoredOperation> operations(int count, int charactersEach,
                                                    String character) {
        RgaDocument document = new RgaDocument(1);
        List<StoredOperation> stored = new ArrayList<>();
        String piece = character.repeat(charactersEach);
        for (int i = 0; i < count; i++) {
            Op op = document.insert(i * charactersEach, piece);
            stored.add(new StoredOperation(op, i + 1, null, java.time.Instant.EPOCH));
        }
        return stored;
    }

    private static int bytesOf(List<JsonNode> chunk) {
        return chunk.stream().mapToInt(node -> node.toString().length()).sum();
    }

    @Test
    @DisplayName("large operations are split across frames, however few of them there are")
    void largeOperationsAreSplitAcrossFrames() {
        int budget = 32 * 1024;
        List<List<JsonNode>> chunks = DocumentStompController.chunkByBytes(
            operations(20, 8_000), budget);

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
        List<List<JsonNode>> chunks = DocumentStompController.chunkByBytes(
            operations(1_000, 1), 32 * 1024);

        assertThat(chunks).hasSizeLessThan(8);
        assertThat(chunks.stream().mapToInt(List::size).sum()).isEqualTo(1_000);
    }

    @Test
    @DisplayName("one operation bigger than the whole budget is sent alone, not dropped")
    void anOversizedOperationIsStillSent() {
        List<List<JsonNode>> chunks = DocumentStompController.chunkByBytes(
            operations(1, 100_000), 32 * 1024);

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
        List<StoredOperation> ops = operations(20, 6_000, "\u4e2d");

        for (List<JsonNode> chunk : DocumentStompController.chunkByBytes(ops, budget)) {
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
        assertThat(DocumentStompController.chunkByBytes(List.of(), 32 * 1024)).isEmpty();
    }

    @Test
    @DisplayName("the encoded size is what is measured, not a guess about it")
    void theBudgetIsMeasuredOnTheEncodedForm() {
        List<StoredOperation> ops = operations(1, 5_000);
        int encoded = OpCodec.encode(ops.getFirst().op()).length();

        assertThat(encoded)
            .as("the old rule assumed 60-100 bytes per operation; this one is fifty times that, "
                + "and pasting is how a user produces it in the first minute")
            .isGreaterThan(5_000);
    }
}
