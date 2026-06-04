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
 * Generator korpusu wektorów konformancyjnych — mostu między implementacją w Javie
 * a implementacją w TypeScripcie.
 *
 * <p><b>Po co to jest.</b> ADR-0001 przyjmuje świadomy dług: algorytm istnieje w dwóch
 * implementacjach, bo klient musi edytować offline. Dwie implementacje jednej specyfikacji
 * to dwie okazje do błędu i pewność, że prędzej czy później się rozjadą — zwłaszcza że
 * rozjazd objawia się dopiero jako „u mnie wygląda inaczej niż u ciebie", czyli najgorszym
 * możliwym rodzajem błędu.
 *
 * <p><b>Jak to działa.</b> Ten test zapisuje do katalogu {@code conformance/} pliki JSON
 * zawierające ciąg operacji i oczekiwany tekst końcowy. Suite w TypeScripcie odtwarza
 * dokładnie te same wektory. CI uruchamia obie strony, więc rozjazd algorytmu nie może
 * przejść niezauważony.
 *
 * <p><b>Dlaczego wektory są w repo, a nie generowane w locie.</b> Bo są też dokumentacją
 * formatu przewodowego i zmiana w nich musi być widoczna w diffie pull requesta. Wektor,
 * który zmienia się przy każdym uruchomieniu, nie chroni przed niczym.
 */
class ConformanceVectorGeneratorTest {

    private static final Path OUTPUT = Path.of("..", "conformance");

    @Test
    void generateVectors() throws IOException {
        Files.createDirectories(OUTPUT);
        List<String> written = new ArrayList<>();

        written.add(write("001-sequential-typing",
            "Jedna replika pisze ciągiem. Sprawdza podstawowe wstawianie i kompresję run-length.",
            sequentialTyping()));

        written.add(write("002-concurrent-same-position",
            "Dwie repliki wstawiają w to samo miejsce. Operacje podane w kolejności odwrotnej "
                + "do wygenerowania — wynik musi być identyczny.",
            concurrentSamePosition()));

        written.add(write("003-delete-before-insert-arrives",
            "Usunięcie dociera PRZED wstawieniem, którego dotyczy. Implementacja musi je "
                + "zbuforować i zastosować później, zamiast zignorować lub rzucić wyjątkiem.",
            deleteBeforeInsert()));

        written.add(write("004-duplicated-operations",
            "Każda operacja podana dwukrotnie. Sprawdza idempotencję — bez niej retransmisja "
                + "po ponownym połączeniu dubluje tekst.",
            duplicatedOperations()));

        written.add(write("005-backward-typing-interleaving",
            "Pisanie wstecz przez dwie repliki. Utrwala ZNANE OGRANICZENIE RGA (ADR-0001): "
                + "wynik jest przeplotem. Implementacja w TS musi przeplatać TAK SAMO — "
                + "zgodność jest ważniejsza niż to, czy wynik się podoba.",
            backwardTyping()));

        // Losowe scenariusze o ustalonych ziarnach: powtarzalne, ale różnorodne.
        for (int seed = 1; seed <= 15; seed++) {
            written.add(write(String.format("1%02d-random-seed-%d", seed, seed),
                "Losowy scenariusz o ziarnie " + seed
                    + ": 3 repliki, niezależne edycje, dostawa w losowej kolejności.",
                randomScenario(seed)));
        }

        assertThat(written).hasSize(20);

        // Samosprawdzenie: każdy zapisany wektor musi być odtwarzalny przez tę samą
        // implementację, która go wygenerowała. Wektor, którego nie przechodzi nawet
        // Java, nie może być wzorcem dla TypeScriptu.
        for (String name : written) {
            verifyVector(OUTPUT.resolve(name));
        }
    }

    // ------------------------------------------------------------------
    // Scenariusze
    // ------------------------------------------------------------------

    private static List<Op> sequentialTyping() {
        RgaDocument document = new RgaDocument(1);
        List<Op> ops = new ArrayList<>();
        String text = "Inkline";
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

        // Kolejność odwrotna do wygenerowania — to jest sedno testu.
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

    // ------------------------------------------------------------------
    // Zapis i weryfikacja
    // ------------------------------------------------------------------

    private static String write(String name, String description, List<Op> operations) throws IOException {
        RgaDocument fresh = new RgaDocument(999);
        fresh.applyAll(operations);
        assertThat(fresh.pendingCount())
            .as("wektor %s zostawia operacje w buforze — jest niekompletny", name)
            .isZero();

        ObjectNode root = OpCodec.mapper().createObjectNode();
        root.put("name", name);
        root.put("description", description);
        ArrayNode ops = root.putArray("operations");
        operations.forEach(op -> ops.add(OpCodec.toJson(op)));
        root.put("expectedText", fresh.text());

        String fileName = name + ".json";
        Files.writeString(OUTPUT.resolve(fileName),
            OpCodec.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n");
        return fileName;
    }

    private static void verifyVector(Path path) throws IOException {
        var root = OpCodec.mapper().readTree(Files.readString(path));
        List<Op> operations = new ArrayList<>();
        root.get("operations").forEach(node -> operations.add(OpCodec.fromJson(node)));

        // Weryfikujemy na OBU implementacjach BlockList — wektor ma być wzorcem dla
        // algorytmu, a nie dla konkretnej struktury danych.
        for (BlockList storage : List.of(new ArrayBlockList(), new TreapBlockList())) {
            RgaDocument document = new RgaDocument(999, storage);
            document.applyAll(operations);
            assertThat(document.text())
                .as("wektor %s nie odtwarza się na %s", path.getFileName(),
                    storage.getClass().getSimpleName())
                .isEqualTo(root.get("expectedText").asText());
        }
    }
}
