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

class SnapshotFixtureTest {
    private static final Path DIRECTORY = Path.of("..", "conformance", "snapshots");
    private static final Path BYTES = DIRECTORY.resolve("document-v5.bin");
    private static final Path EXPECTED = DIRECTORY.resolve("document-v5.expected.json");

    private static RgaDocument sampleDocument() {
        RgaDocument document = new RgaDocument(7, new TreapBlockList());
        document.insert(0, "Zażółć gęślą jaźń");
        document.insert(17, " and then some ASCII");
        document.insert(37, " 😀 astral");
        document.delete(6, 6);
        document.addMark(0, 5, MarkTypes.BOLD, null);
        document.addMark(6, 11, MarkTypes.LINK, "https://example.test/a");
        document.gc(document.version());
        return document;
    }

    @Test
    @DisplayName("the committed snapshot fixture still decodes to the state it was made from")
    void fixtureMatchesTheFormat() throws IOException {
        RgaDocument built = sampleDocument();
        byte[] encoded = SnapshotCodec.encode(built.snapshot());

        if (!Files.exists(BYTES)) {
            if (!Boolean.getBoolean("conformance.regenerate")) {
                throw new AssertionError(
                    BYTES + " is missing. The fixture is committed on purpose: it is what pins the "
                        + "snapshot format across the Java and TypeScript implementations, and "
                        + "regenerating it from the current encoder would pin whatever the encoder "
                        + "does today. Restore it from version control, or - if the format change "
                        + "is intended - rerun with -Dconformance.regenerate=true and commit the "
                        + "new bytes as a reviewable change.");
            }
            Files.createDirectories(DIRECTORY);
            Files.write(BYTES, encoded);
            Files.writeString(EXPECTED, expectedJson(built));
        }

        assertThat(encoded)
            .as("the encoder no longer produces the committed fixture - if that is intended, delete "
                + "conformance/snapshots/document-v5.* and run this test once")
            .isEqualTo(Files.readAllBytes(BYTES));

        DocumentSnapshot decoded = SnapshotCodec.decode(Files.readAllBytes(BYTES));
        assertThat(decoded)
            .as("the committed fixture no longer decodes at all - the format version changed, and "
                + "every client that has not been updated is now opening documents from the log")
            .isNotNull();

        assertThat(decoded.blocks())
            .as("the fixture stopped covering the content-dropped branch, which is the only part "
                + "of the format the two sides measure in different units")
            .anyMatch(block -> !block.hasContent());

        RgaDocument restored = RgaDocument.restore(1, new TreapBlockList(), decoded);
        assertThat(restored.text()).isEqualTo(built.text());
        assertThat(describe(restored)).isEqualTo(Files.readString(EXPECTED).strip());
    }

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
            List.of(new Block(new Id(1, 0), "abcde", null, false)), List.of(), Map.of()));

        assertThat(SnapshotCodec.decode(encoded))
            .as("a cache in a format we do not know must lead to a rebuild from the log, not to a "
                + "document assembled out of misread fields")
            .isNull();
    }
}
