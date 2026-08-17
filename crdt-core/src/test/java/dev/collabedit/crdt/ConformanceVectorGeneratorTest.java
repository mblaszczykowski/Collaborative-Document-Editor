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
                + "order to how they were generated - the result must be identical.",
            concurrentSamePosition()));

        written.add(write("003-delete-before-insert-arrives",
            "A deletion arrives BEFORE the insertion it concerns. The implementation has to "
                + "buffer it and apply it later, rather than ignoring it or throwing.",
            deleteBeforeInsert()));

        written.add(write("004-duplicated-operations",
            "Every operation supplied twice. Checks idempotence - without it a retransmission "
                + "after reconnect duplicates text.",
            duplicatedOperations()));

        written.add(write("005-backward-typing-interleaving",
            "Backward typing by two replicas. Pins the KNOWN RGA LIMITATION: "
                + "the result is an interleaving. The TS implementation must interleave THE SAME "
                + "WAY - agreement matters more than whether the result is pleasing.",
            backwardTyping()));

        written.add(write("006-duplicate-while-buffered",
            "An operation delivered TWICE while its origin is still missing, then the origin. "
                + "Distinct from 004: there the duplicate lands in a document that already holds "
                + "the operation, so a structure-based check catches it. Here both copies go into "
                + "the PENDING buffer, and a replica that only checks the structure integrates "
                + "both when the origin arrives - duplicating the text AND putting one identifier "
                + "in two blocks, which makes a later delete tombstone only one of the copies.",
            duplicateWhileBuffered()));

        written.add(write("201-mark-inherited-by-concurrent-insert",
            "Text typed into the middle of a bold range by a replica that has not seen the bold "
                + "yet. It MUST inherit the formatting - this is the entire reason a mark is a "
                + "range between anchors rather than a character attribute.",
            markInheritedByConcurrentInsert()));

        written.add(write("202-mark-expansion-differs-by-type",
            "Bold GROWS when typing at its end, a link does NOT. The entire difference is which "
                + "side the end anchor sits on - the TS implementation must choose the same.",
            markExpansionDiffersByType()));

        written.add(write("203-concurrent-add-and-remove-mark",
            "Concurrently applying and removing the same formatting. Settled by the total order "
                + "(lamport, replicaId) - the same one RGA's insertion rule uses.",
            concurrentAddAndRemoveMark()));

        written.add(write("204-mark-before-its-anchors",
            "A mark arrives BEFORE the text it concerns. It has to wait in the buffer and be "
                + "applied once its anchors arrive, rather than landing in a random place.",
            markBeforeItsAnchors()));

        written.add(write("205-overlapping-marks-of-many-types",
            "Overlapping ranges of three mark types plus a removal of their intersection. Checks "
                + "the computation of formatting segments and the merging of adjacent ones.",
            overlappingMarks()));

        for (int seed = 1; seed <= 15; seed++) {
            written.add(write(String.format("1%02d-random-seed-%d", seed, seed),
                "Random scenario, seed " + seed
                    + ": 3 replicas, independent edits, delivery in random order.",
                randomScenario(seed)));
        }

        for (int seed = 1; seed <= 15; seed++) {
            written.add(write(String.format("3%02d-random-marks-seed-%d", seed, seed),
                "Random scenario with formatting, seed " + seed
                    + ": 3 replicas, inserts, deletes, marks and mark removals, "
                    + "delivery in random order.",
                randomRichTextScenario(seed)));
        }

        assertThat(written).hasSize(41);

        for (String name : written) {
            verifyVector(OUTPUT.resolve(name));
        }
    }

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

    private static List<Op> markInheritedByConcurrentInsert() {
        RgaDocument ania = new RgaDocument(1);
        RgaDocument bartek = new RgaDocument(2);
        Op base = ania.insert(0, "abcdef");
        bartek.apply(base);

        Op bold = ania.addMark(1, 5, MarkTypes.BOLD, null);
        Op inserted = bartek.insert(3, "XY");

        return List.of(base, inserted, bold);
    }

    private static List<Op> markExpansionDiffersByType() {
        RgaDocument document = new RgaDocument(1);
        List<Op> ops = new ArrayList<>();
        ops.add(document.insert(0, "abcdef"));
        ops.add(document.addMark(1, 3, MarkTypes.BOLD, null));
        ops.add(document.addMark(4, 6, MarkTypes.LINK, "https://example.test"));
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

    private static String write(String name, String description, List<Op> operations) throws IOException {
        RgaDocument fresh = new RgaDocument(999);
        fresh.applyAll(operations);
        assertThat(fresh.pendingCount())
            .as("vector %s leaves operations in the buffer - it is incomplete", name)
            .isZero();

        ObjectNode root = OpCodec.mapper().createObjectNode();
        root.put("name", name);
        root.put("description", description);
        ArrayNode ops = root.putArray("operations");
        operations.forEach(op -> ops.add(OpCodec.toJson(op)));
        root.put("expectedText", fresh.text());

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

        if (Files.exists(target)) {
            assertThat(produced)
                .as("the vector %s no longer matches the committed reference. If the algorithm "
                    + "changed on purpose, delete conformance/%s and run this test once - "
                    + "otherwise this is a regression, and the TypeScript suite replays this "
                    + "corpus, so accepting it silently would make both implementations agree "
                    + "about the wrong answer.", fileName, fileName)
                .isEqualTo(Files.readString(target));
        } else {
            Files.writeString(target, produced);
        }
        return fileName;
    }

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
