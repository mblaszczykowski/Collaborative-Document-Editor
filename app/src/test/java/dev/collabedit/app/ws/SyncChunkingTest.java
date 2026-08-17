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

class SyncChunkingTest {
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
                + "put all 160 KB of them in ONE frame against a 64 KiB limit - and the client "
                + "that needed them most was the one that could not receive them")
            .hasSizeGreaterThan(4);

        for (List<JsonNode> chunk : chunks) {
            assertThat(bytesOf(chunk)).isLessThanOrEqualTo(budget + 8_100);
        }
    }

    @Test
    @DisplayName("many small operations still travel in few frames")
    void smallOperationsAreNotFragmented() {
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
        assertThat(bytesOf(chunks.getFirst())).isGreaterThan(32 * 1024);
    }

    @Test
    @DisplayName("a document in a non-Latin script does not overflow the budget")
    void multiByteTextIsMeasuredInBytes() {
        int budget = 32 * 1024;
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
        int budget = 12 * 1024;

        assertThat(FrameChunks.byBytes(operations(2, 8_000), budget))
            .as("two operations far larger than the budget were put in one frame - which is what "
                + "the count-based rule did, and what made a resync of a large document exceed "
                + "the frame limit with no error anywhere")
            .hasSize(2);

        assertThat(FrameChunks.byBytes(operations(100, 1), budget))
            .as("a hundred tiny operations were split, so the budget is not being measured on the "
                + "encoded form either")
            .hasSize(1);
    }
}
