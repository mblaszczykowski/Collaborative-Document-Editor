package dev.collabedit.crdt;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * The generator of the conformance vector corpus — the bridge between the Java implementation and
 * the TypeScript one.
 *
 * <p><b>Why this exists.</b> the CRDT decision accepts a deliberate debt: the algorithm exists in two
 * implementations, because the client has to edit offline. Two implementations of one specification
 * are two opportunities for a bug and a certainty that sooner or later they will diverge —
 * especially since the divergence only shows up as "it looks different on mine than on yours",
 * which is the worst possible kind of bug.
 *
 * <p><b>How it works.</b> This test writes JSON files into the {@code conformance/} directory
 * containing a sequence of operations and the expected final text. The TypeScript suite replays
 * exactly the same vectors. CI runs both sides, so a divergence in the algorithm cannot pass
 * unnoticed.
 *
 * <p><b>Why the vectors live in the repository rather than being generated on the fly.</b> Because
 * they are also documentation of the wire format, and a change in them has to be visible in a pull
 * request diff. A vector that changes on every run protects against nothing.
 */
class ConformanceVectorGeneratorTest {

    private static final Path OUTPUT = Path.of("..", "conformance");

    @Test
    void generateVectors() throws IOException {
        Files.createDirectories(OUTPUT);
        List<String> written = new ArrayList<>();

        written.add(write("001-sequential-typing",
            "One replica types continuously. Checks basic insertion and run-length compression.",
            sequentialTyping()));

        written.add(write("002-concurrent-same-position",
            "Two replicas insert in the same place. The operations are supplied in the reverse "
                + "order to how they were generated — the result must be identical.",
            concurrentSamePosition()));

        written.add(write("003-delete-before-insert-arrives",
            "A deletion arrives BEFORE the insertion it concerns. The implementation has to "
                + "buffer it and apply it later, rather than ignoring it or throwing.",
            deleteBeforeInsert()));

        written.add(write("004-duplicated-operations",
            "Every operation supplied twice. Checks idempotence — without it a retransmission "
                + "after reconnect duplicates text.",
            duplicatedOperations()));

        written.add(write("005-backward-typing-interleaving",
            "Backward typing by two replicas. Pins the KNOWN RGA LIMITATION: "
                + "the result is an interleaving. The TS implementation must interleave THE SAME "
                + "WAY — agreement matters more than whether the result is pleasing.",
            backwardTyping()));

        written.add(write("006-duplicate-while-buffered",
            "An operation delivered TWICE while its origin is still missing, then the origin. "
                + "Distinct from 004: there the duplicate lands in a document that already holds "
                + "the operation, so a structure-based check catches it. Here both copies go into "
                + "the PENDING buffer, and a replica that only checks the structure integrates "
                + "both when the origin arrives — duplicating the text AND putting one identifier "
                + "in two blocks, which makes a later delete tombstone only one of the copies.",
            duplicateWhileBuffered()));

        written.add(write("201-mark-inherited-by-concurrent-insert",
            "Text typed into the middle of a bold range by a replica that has not seen the bold "
                + "yet. It MUST inherit the formatting — this is the entire reason a mark is a "
                + "range between anchors rather than a character attribute (CP13).",
            markInheritedByConcurrentInsert()));

        written.add(write("202-mark-expansion-differs-by-type",
            "Bold GROWS when typing at its end, a link does NOT. The entire difference is which "
                + "side the end anchor sits on — the TS implementation must choose the same.",
            markExpansionDiffersByType()));

        written.add(write("203-concurrent-add-and-remove-mark",
            "Concurrently applying and removing the same formatting. Settled by the total order "
                + "(lamport, replicaId) — the same one RGA's insertion rule uses.",
            concurrentAddAndRemoveMark()));

        written.add(write("204-mark-before-its-anchors",
            "A mark arrives BEFORE the text it concerns. It has to wait in the buffer and be "
                + "applied once its anchors arrive, rather than landing in a random place.",
            markBeforeItsAnchors()));

        written.add(write("205-overlapping-marks-of-many-types",
            "Overlapping ranges of three mark types plus a removal of their intersection. Checks "
                + "the computation of formatting segments and the merging of adjacent ones.",
            overlappingMarks()));

        // Random scenarios with fixed seeds: repeatable, but varied.
        for (int seed = 1; seed <= 15; seed++) {
            written.add(write(String.format("1%02d-random-seed-%d", seed, seed),
                "Random scenario, seed " + seed
                    + ": 3 replicas, independent edits, delivery in random order.",
                randomScenario(seed)));
        }

        // Random scenarios INCLUDING MARK OPERATIONS.
        //
        // Added because the corpus had a hole shaped exactly like the newest layer of the
        // algorithm: randomScenario above emits only inserts and deletes, so every random vector
        // in the corpus exercised the character sequence and nothing else. The whole mark layer —
        // anchor resolution, the (lamport, replicaId) winner rule, segment merging — was checked
        // on the TypeScript side by five hand-written vectors and by nothing that varies.
        //
        // Hand-written vectors catch the cases somebody thought of. These catch the ones nobody
        // did, which is the entire reason the plain-text family exists and the reason its absence
        // here was worth closing.
        for (int seed = 1; seed <= 15; seed++) {
            written.add(write(String.format("3%02d-random-marks-seed-%d", seed, seed),
                "Random scenario with formatting, seed " + seed
                    + ": 3 replicas, inserts, deletes, marks and mark removals, "
                    + "delivery in random order.",
                randomRichTextScenario(seed)));
        }

        assertThat(written).hasSize(41);

        // A self-check: every vector written must be reproducible by the same implementation that
        // generated it. A vector Java itself does not pass cannot be a reference for TypeScript.
        for (String name : written) {
            verifyVector(OUTPUT.resolve(name));
        }
    }

    // ------------------------------------------------------------------
    // Scenarios
    // ------------------------------------------------------------------

    private static List<Op> sequentialTyping() {
        RgaDocument document = new RgaDocument(1);
        List<Op> ops = new ArrayList<>();
        String text = "Collabedit";
        for (int i = 0; i < text.length(); i++) {
            ops.add(document.insert(i, String.valueOf(text.charAt(i))));
        }
        return ops;
    }

    private static List<Op> concurrentSamePosition() {
        RgaDocument anna = new RgaDocument(1);
        Op base = anna.insert(0, "AB");
        RgaDocument marek = new RgaDocument(2);
        marek.apply(base);

        Op fromAnna = anna.insert(1, "hello");
        Op fromMarek = marek.insert(1, "world");

        // The reverse of the generation order — this is the point of the test.
        return List.of(fromMarek, fromAnna, base);
    }

    private static List<Op> deleteBeforeInsert() {
        RgaDocument document = new RgaDocument(1);
        Op insert = document.insert(0, "abcdef");
        Op delete = document.delete(2, 2);
        return List.of(delete, insert);
    }

    private static List<Op> duplicatedOperations() {
        RgaDocument anna = new RgaDocument(1);
        Op base = anna.insert(0, "xyz");
        RgaDocument marek = new RgaDocument(2);
        marek.apply(base);
        Op fromMarek = marek.insert(3, "123");
        Op deletion = marek.delete(0, 1);

        return List.of(base, fromMarek, base, deletion, fromMarek, deletion);
    }

    /**
     * The origin arrives LAST, after two copies of the operation that depends on it.
     *
     * <p>The order is the point: a vector is replayed exactly as listed, so putting the origin at
     * the end is what forces both copies through the pending buffer. It is reachable in the
     * product — the resynchronisation path re-sends operations by design, including ones the
     * client is currently buffering because their origin is in a later chunk.
     */
    private static List<Op> duplicateWhileBuffered() {
        RgaDocument anna = new RgaDocument(1);
        Op origin = anna.insert(0, "AB");
        Op child = anna.insert(2, "cd");
        return List.of(child, child, origin);
    }

    private static List<Op> backwardTyping() {
        RgaDocument anna = new RgaDocument(1);
        RgaDocument marek = new RgaDocument(2);
        List<Op> ops = new ArrayList<>();
        for (char c : "cba".toCharArray()) {
            ops.add(anna.insert(0, String.valueOf(c)));
        }
        for (char c : "zyx".toCharArray()) {
            ops.add(marek.insert(0, String.valueOf(c)));
        }
        return ops;
    }

    // --- formatting scenarios (CP13) ---

    private static List<Op> markInheritedByConcurrentInsert() {
        RgaDocument ania = new RgaDocument(1);
        RgaDocument bartek = new RgaDocument(2);
        Op base = ania.insert(0, "abcdef");
        bartek.apply(base);

        Op bold = ania.addMark(1, 5, MarkTypes.BOLD, null);
        // Bartek has NOT seen the bold yet.
        Op inserted = bartek.insert(3, "XY");

        return List.of(base, inserted, bold);
    }

    private static List<Op> markExpansionDiffersByType() {
        RgaDocument document = new RgaDocument(1);
        List<Op> ops = new ArrayList<>();
        ops.add(document.insert(0, "abcdef"));
        ops.add(document.addMark(1, 3, MarkTypes.BOLD, null));
        ops.add(document.addMark(4, 6, MarkTypes.LINK, "https://example.test"));
        // Insertions immediately after each range — bold should cover them, a link should not.
        ops.add(document.insert(3, "X"));
        ops.add(document.insert(document.visibleLength(), "Y"));
        return ops;
    }

    private static List<Op> concurrentAddAndRemoveMark() {
        RgaDocument ania = new RgaDocument(1);
        RgaDocument bartek = new RgaDocument(2);
        Op base = ania.insert(0, "abcdefgh");
        bartek.apply(base);

        Op bold = ania.addMark(1, 6, MarkTypes.BOLD, null);
        Op unbold = bartek.removeMark(3, 8, MarkTypes.BOLD);

        // The reverse of the generation order — the result must be identical.
        return List.of(unbold, bold, base);
    }

    private static List<Op> markBeforeItsAnchors() {
        RgaDocument document = new RgaDocument(1);
        Op base = document.insert(0, "abcdef");
        Op bold = document.addMark(1, 4, MarkTypes.ITALIC, null);
        return List.of(bold, base);
    }

    private static List<Op> overlappingMarks() {
        RgaDocument document = new RgaDocument(1);
        List<Op> ops = new ArrayList<>();
        ops.add(document.insert(0, "abcdefghij"));
        ops.add(document.addMark(0, 6, MarkTypes.BOLD, null));
        ops.add(document.addMark(3, 9, MarkTypes.ITALIC, null));
        ops.add(document.addMark(2, 5, MarkTypes.LINK, "https://example.test/x"));
        ops.add(document.removeMark(4, 7, MarkTypes.ITALIC));
        return ops;
    }

    private static List<Op> randomScenario(long seed) {
        Random random = new Random(seed);
        ReplicaSet replicas = new ReplicaSet(3, ArrayBlockList::new);

        for (int step = 0; step < 25; step++) {
            int replica = random.nextInt(3);
            int length = replicas.get(replica).visibleLength();
            if (length == 0 || random.nextInt(4) > 0) {
                replicas.insert(replica, random.nextInt(length + 1),
                    String.valueOf((char) ('a' + random.nextInt(6))));
            } else {
                int position = random.nextInt(length);
                replicas.delete(replica, position, Math.min(1 + random.nextInt(2), length - position));
            }
            if (step % 5 == 4) {
                replicas.deliverSome(random, 3);
            }
        }

        List<Op> ops = new ArrayList<>(replicas.emitted().stream().map(ReplicaSet.Emitted::op).toList());
        Collections.shuffle(ops, random);
        return ops;
    }

    /**
     * A random scenario that also formats.
     *
     * <p>Kept separate from {@link #randomScenario} rather than folded into it, so the existing
     * fifteen vectors do not churn: a vector that changes for a reason unrelated to the algorithm
     * makes the CI check "the corpus is up to date" noisy, and a noisy check gets ignored.
     *
     * <p>Ranges are chosen defensively (a mark needs at least two visible characters and a
     * non-empty range) because the generator is not what is under test here — an
     * {@code IndexOutOfBoundsException} while BUILDING a vector would say nothing about either
     * implementation.
     */
    private static List<Op> randomRichTextScenario(long seed) {
        Random random = new Random(seed);
        ReplicaSet replicas = new ReplicaSet(3, ArrayBlockList::new);
        String[] types = {MarkTypes.BOLD, MarkTypes.ITALIC, MarkTypes.LINK};

        for (int step = 0; step < 30; step++) {
            int replica = random.nextInt(3);
            int length = replicas.get(replica).visibleLength();

            int choice = random.nextInt(10);
            if (length < 2 || choice < 5) {
                replicas.insert(replica, random.nextInt(length + 1),
                    String.valueOf((char) ('a' + random.nextInt(6))));
            } else if (choice < 7) {
                int position = random.nextInt(length);
                replicas.delete(replica, position, Math.min(1 + random.nextInt(2), length - position));
            } else {
                int from = random.nextInt(length - 1);
                int to = from + 1 + random.nextInt(length - from - 1 + 1);
                String type = types[random.nextInt(types.length)];
                if (choice < 9) {
                    replicas.addMark(replica, from, to, type);
                } else {
                    replicas.removeMark(replica, from, to, type);
                }
            }

            if (step % 5 == 4) {
                replicas.deliverSome(random, 3);
            }
        }

        List<Op> ops = new ArrayList<>(replicas.emitted().stream().map(ReplicaSet.Emitted::op).toList());
        Collections.shuffle(ops, random);
        return ops;
    }

    // ------------------------------------------------------------------
    // Writing and verification
    // ------------------------------------------------------------------

    private static String write(String name, String description, List<Op> operations) throws IOException {
        RgaDocument fresh = new RgaDocument(999);
        fresh.applyAll(operations);
        assertThat(fresh.pendingCount())
            .as("vector %s leaves operations in the buffer — it is incomplete", name)
            .isZero();

        ObjectNode root = OpCodec.mapper().createObjectNode();
        root.put("name", name);
        root.put("description", description);
        ArrayNode ops = root.putArray("operations");
        operations.forEach(op -> ops.add(OpCodec.toJson(op)));
        root.put("expectedText", fresh.text());

        // The expected formatting is ALWAYS written, including for vectors with no marks — as an
        // empty array in that case. A conditionally present field would mean the TS implementation
        // has to guess whether its absence means "no formatting" or "an old vector", and the
        // difference between those two would then be undetectable.
        ArrayNode formatting = root.putArray("expectedFormatting");
        for (MarkSet.Span span : fresh.formatting()) {
            ObjectNode entry = formatting.addObject();
            entry.put("from", span.from());
            entry.put("to", span.to());
            ObjectNode marks = entry.putObject("marks");
            span.marks().forEach((type, value) -> {
                if (value == null) {
                    marks.putNull(type);
                } else {
                    marks.put(type, value);
                }
            });
        }

        String fileName = name + ".json";
        Path target = OUTPUT.resolve(fileName);
        String produced =
            OpCodec.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n";

        // WRITTEN ONLY WHEN MISSING, compared otherwise — the same shape SnapshotFixtureTest uses
        // for the encoder, and for the same reason.
        //
        // This used to write unconditionally. That makes the corpus a RECORDING of whatever the
        // algorithm currently does rather than a reference it is checked against: run the suite
        // with a defect present and the expected text is quietly replaced by the defect's output,
        // after which every vector agrees with it and the TypeScript twin agrees with the vectors.
        // CI catches the drift (`ci.yml` fails on a dirty `conformance/`), so the corpus itself was
        // never in danger — but the developer running the tests locally, which is when a defect is
        // actually present, got a green build and a silently rewritten reference.
        //
        // Regenerating on purpose is still one step: delete the file (or the directory) and run
        // this test once. That is a deliberate act with a visible diff, which is the whole
        // difference.
        if (Files.exists(target)) {
            assertThat(produced)
                .as("the vector %s no longer matches the committed reference. If the algorithm "
                    + "changed on purpose, delete conformance/%s and run this test once — "
                    + "otherwise this is a regression, and the TypeScript suite replays this "
                    + "corpus, so accepting it silently would make both implementations agree "
                    + "about the wrong answer.", fileName, fileName)
                .isEqualTo(Files.readString(target));
        } else {
            Files.writeString(target, produced);
        }
        return fileName;
    }

    /** The formatting as JSON text — for comparison against the stored reference. */
    private static String describeFormatting(RgaDocument document) {
        ArrayNode formatting = OpCodec.mapper().createArrayNode();
        for (MarkSet.Span span : document.formatting()) {
            ObjectNode entry = formatting.addObject();
            entry.put("from", span.from());
            entry.put("to", span.to());
            ObjectNode marks = entry.putObject("marks");
            span.marks().forEach((type, value) -> {
                if (value == null) {
                    marks.putNull(type);
                } else {
                    marks.put(type, value);
                }
            });
        }
        return formatting.toString();
    }

    private static void verifyVector(Path path) throws IOException {
        var root = OpCodec.mapper().readTree(Files.readString(path));
        List<Op> operations = new ArrayList<>();
        root.get("operations").forEach(node -> operations.add(OpCodec.fromJson(node)));

        // Verified on BOTH BlockList implementations — a vector is meant to be a reference for
        // of the algorithm rather than of one particular data structure.
        for (BlockList storage : List.of(new ArrayBlockList(), new TreapBlockList())) {
            RgaDocument document = new RgaDocument(999, storage);
            document.applyAll(operations);
            assertThat(document.text())
                .as("vector %s does not reproduce on %s", path.getFileName(),
                    storage.getClass().getSimpleName())
                .isEqualTo(root.get("expectedText").asText());

            assertThat(describeFormatting(document))
                .as("vector %s produces different FORMATTING on %s", path.getFileName(),
                    storage.getClass().getSimpleName())
                .isEqualTo(root.get("expectedFormatting").toString());
        }
    }
}
