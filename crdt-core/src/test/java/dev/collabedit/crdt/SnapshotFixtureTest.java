package dev.collabedit.crdt;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The snapshot fixture the TypeScript decoder is checked against (CP24).
 *
 * <h2>Why a fixture and not another conformance vector</h2>
 * The vector corpus checks that both implementations <b>merge</b> the same way: it feeds
 * operations in and compares the resulting text. The snapshot format is a different agreement —
 * about <b>bytes</b> — and only one side writes it. A divergence there is invisible to every
 * vector: the Java side would keep producing snapshots it can read perfectly, and the client
 * would open documents wrong.
 *
 * <p>Which is a failure mode worth naming, because it is quiet. A decoder that misreads one field
 * does not throw; it produces a document that is subtly not the one on the server, and the first
 * edit merges that difference into everybody's copy.
 *
 * <h2>How this file behaves</h2>
 * The fixture is <b>committed</b>, and this test regenerates it only when it is missing. So:
 *
 * <ul>
 *   <li>a change to the format that is not intended fails <b>here</b>, because the committed
 *       bytes no longer decode to the expected state;</li>
 *   <li>a change that IS intended is made by deleting the fixture and running this once, which
 *       shows up in review as a changed binary — deliberately hard to do by accident;</li>
 *   <li>the TypeScript twin ({@code snapshot.test.ts}) reads the same two files.</li>
 * </ul>
 */
class SnapshotFixtureTest {

    private static final Path DIRECTORY = Path.of("..", "conformance", "snapshots");
    private static final Path BYTES = DIRECTORY.resolve("document-v3.bin");
    private static final Path EXPECTED = DIRECTORY.resolve("document-v3.expected.json");

    /**
     * A document exercising every branch of the format.
     *
     * <p>Deliberately including a deletion (a tombstone block), two mark types — one with a value
     * and one without — and text outside ASCII, because the length prefix counts <b>bytes</b>
     * while the client counts characters, and that is the classic way a binary format decoder
     * goes wrong on the second language it meets.
     */
    private static RgaDocument sampleDocument() {
        RgaDocument document = new RgaDocument(7, new TreapBlockList());
        document.insert(0, "Zażółć gęślą jaźń");
        document.insert(17, " and then some ASCII");
        document.delete(6, 6);
        document.addMark(0, 5, MarkTypes.BOLD, null);
        document.addMark(6, 11, MarkTypes.LINK, "https://example.test/a");
        return document;
    }

    @Test
    @DisplayName("the committed snapshot fixture still decodes to the state it was made from")
    void fixtureMatchesTheFormat() throws IOException {
        RgaDocument built = sampleDocument();
        byte[] encoded = SnapshotCodec.encode(built.snapshot());

        if (!Files.exists(BYTES)) {
            Files.createDirectories(DIRECTORY);
            Files.write(BYTES, encoded);
            Files.writeString(EXPECTED, expectedJson(built));
        }

        DocumentSnapshot decoded = SnapshotCodec.decode(Files.readAllBytes(BYTES));
        assertThat(decoded)
            .as("the committed fixture no longer decodes at all — the format version changed, and "
                + "every client that has not been updated is now opening documents from the log")
            .isNotNull();

        RgaDocument restored = RgaDocument.restore(1, new TreapBlockList(), decoded);
        assertThat(restored.text()).isEqualTo(built.text());
        assertThat(describe(restored)).isEqualTo(Files.readString(EXPECTED).strip());
    }

    /** Text and formatting, in the shape the TypeScript twin compares against. */
    private static String expectedJson(RgaDocument document) throws IOException {
        return describe(document);
    }

    private static String describe(RgaDocument document) throws IOException {
        Map<String, Object> shape = new LinkedHashMap<>();
        shape.put("text", document.text());
        shape.put("marks", document.formatting().stream()
            .map(span -> {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("from", span.from());
                entry.put("to", span.to());
                entry.put("marks", new java.util.TreeMap<>(span.marks()));
                return entry;
            })
            .toList());
        return new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(shape);
    }

    @Test
    @DisplayName("a snapshot in an unknown format is ignored rather than half-read")
    void unknownFormatIsIgnored() {
        byte[] encoded = SnapshotCodec.encode(new DocumentSnapshot(
            DocumentSnapshot.CURRENT_FORMAT_VERSION + 1, 5, Map.of(1, 4L),
            List.of(new Block(new Id(1, 0), "abcde", null, false)), List.of()));

        assertThat(SnapshotCodec.decode(encoded))
            .as("a cache in a format we do not know must lead to a rebuild from the log, not to a "
                + "document assembled out of misread fields")
            .isNull();
    }
}
