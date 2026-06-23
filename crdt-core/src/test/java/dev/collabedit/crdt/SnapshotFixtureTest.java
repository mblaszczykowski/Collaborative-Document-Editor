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
    private static final Path BYTES = DIRECTORY.resolve("document-v5.bin");
    private static final Path EXPECTED = DIRECTORY.resolve("document-v5.expected.json");

    /**
     * A document exercising every branch of the format.
     *
     * <p>Deliberately including a deletion (a tombstone block), two mark types — one with a value
     * and one without — and text outside ASCII, because the length prefix counts <b>bytes</b>
     * while the client counts characters, and that is the classic way a binary format decoder
     * goes wrong on the second language it meets.
     *
     * <p>Since version 4 it also runs {@link RgaDocument#gc}, which is what produces a block with
     * <b>no content</b> — the branch format version 3 was raised for and the one branch neither
     * side had ever exercised against the other. That branch is also where the two lengths differ
     * in kind: a block with content writes a count of UTF-8 <i>bytes</i>, one without writes a
     * count of <i>characters</i>. They agree today, and now something says so.
     *
     * <p>What is deliberately NOT here is a block split between the halves of an astral character.
     * The document's text would then contain a lone surrogate, which cannot survive being written
     * to the expected-JSON file — the fixture would corrupt in exactly the way it exists to detect.
     * That branch is covered on the Java side by {@code SnapshotCodecTest} and on the client side
     * by a decoder test that builds the bytes by hand.
     */
    private static RgaDocument sampleDocument() {
        RgaDocument document = new RgaDocument(7, new TreapBlockList());
        // Deliberately not translated: this string is test DATA, chosen because its Polish
        // diacritics are multi-byte in UTF-8 — which is the whole point of the case.
        document.insert(0, "Zażółć gęślą jaźń");
        document.insert(17, " and then some ASCII");
        document.insert(37, " 😀 astral");
        document.delete(6, 6);
        document.addMark(0, 5, MarkTypes.BOLD, null);
        document.addMark(6, 11, MarkTypes.LINK, "https://example.test/a");
        // Everything this replica has seen is causally stable, because it is the only replica —
        // so the tombstone above becomes collectable and gives up its content.
        document.gc(document.version());
        return document;
    }

    @Test
    @DisplayName("the committed snapshot fixture still decodes to the state it was made from")
    void fixtureMatchesTheFormat() throws IOException {
        RgaDocument built = sampleDocument();
        byte[] encoded = SnapshotCodec.encode(built.snapshot());

        if (!Files.exists(BYTES)) {
            // A MISSING fixture is a failure, not an instruction to write one — unless somebody
            // says otherwise on the command line.
            //
            // Regenerating silently made this test unable to fail in the one situation it most
            // needs to: with the fixture absent, it encoded, wrote, and then compared against what
            // it had just written, so the encoder-pinning assertion below asserted nothing. That is
            // not hypothetical — the v5 fixture was untracked for a while, so every local run took
            // this branch and every CI run would have hit the conformance drift gate instead, which
            // reports "untracked files" rather than "your fixture is gone".
            //
            // Intentional regeneration is still one command: delete the files and pass
            // -Dconformance.regenerate=true. Requiring the flag is what makes it a decision.
            if (!Boolean.getBoolean("conformance.regenerate")) {
                throw new AssertionError(
                    BYTES + " is missing. The fixture is committed on purpose: it is what pins the "
                        + "snapshot format across the Java and TypeScript implementations, and "
                        + "regenerating it from the current encoder would pin whatever the encoder "
                        + "does today. Restore it from version control, or — if the format change "
                        + "is intended — rerun with -Dconformance.regenerate=true and commit the "
                        + "new bytes as a reviewable change.");
            }
            Files.createDirectories(DIRECTORY);
            Files.write(BYTES, encoded);
            Files.writeString(EXPECTED, expectedJson(built));
        }

        // The ENCODER is pinned too, not only the decoder. Comparing the freshly encoded bytes with
        // the committed ones is what catches a change to `encode` that `decode` still happens to
        // accept — a reordered field, an added trailing one, a different length unit with a
        // tolerant reader. Without this the test proved only "our decoder reads our old bytes",
        // and the TypeScript twin proved the same thing about the same old bytes, so an encoder
        // drift would have shipped green from both sides.
        //
        // This assertion is only safe because version 4 writes the version vector in replica order;
        // before that, `Map.copyOf` made the output differ between JVM runs.
        assertThat(encoded)
            .as("the encoder no longer produces the committed fixture — if that is intended, delete "
                + "conformance/snapshots/document-v5.* and run this test once")
            .isEqualTo(Files.readAllBytes(BYTES));

        DocumentSnapshot decoded = SnapshotCodec.decode(Files.readAllBytes(BYTES));
        assertThat(decoded)
            .as("the committed fixture no longer decodes at all — the format version changed, and "
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
            List.of(new Block(new Id(1, 0), "abcde", null, false)), List.of(), Map.of()));

        assertThat(SnapshotCodec.decode(encoded))
            .as("a cache in a format we do not know must lead to a rebuild from the log, not to a "
                + "document assembled out of misread fields")
            .isNull();
    }
}
