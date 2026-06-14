package dev.collabedit.crdt;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Preserving author intent under concurrent typing — that is, what separates "a convergent
 * algorithm" from "a usable algorithm".
 *
 * <p>Convergence guarantees everyone will see <b>the same thing</b>. It does not guarantee that
 * thing will make sense. The classic pathology is <b>interleaving</b>: two people type in the same
 * place at once and the result is their texts interleaved character by character.
 *
 * <p>This class contains both the case RGA handles correctly and the case it <b>does not</b> — the
 * latter is here deliberately, as executable documentation of the algorithm's boundary (ADR-0001,
 * the "Known limitation" section).
 */
class RgaInterleavingTest {

    /** Two replicas synchronised on shared text, ready for concurrent editing. */
    private static RgaDocument[] twoReplicasSharing(String base) {
        RgaDocument first = new RgaDocument(1);
        Op.Insert seed = first.insert(0, base);
        RgaDocument second = new RgaDocument(2);
        second.apply(seed);
        return new RgaDocument[] {first, second};
    }

    @Test
    @DisplayName("forward typing: concurrent words stay contiguous")
    void forwardTypingStaysContiguous() {
        RgaDocument[] replicas = twoReplicasSharing("AB");
        RgaDocument anna = replicas[0];
        RgaDocument marek = replicas[1];

        // Both people type a word between A and B, character by character — as a human types.
        java.util.List<Op> fromAnna = new java.util.ArrayList<>();
        java.util.List<Op> fromMarek = new java.util.ArrayList<>();
        String annaWord = "hello";
        String marekWord = "world";
        for (int i = 0; i < annaWord.length(); i++) {
            fromAnna.add(anna.insert(1 + i, String.valueOf(annaWord.charAt(i))));
        }
        for (int i = 0; i < marekWord.length(); i++) {
            fromMarek.add(marek.insert(1 + i, String.valueOf(marekWord.charAt(i))));
        }

        anna.applyAll(fromMarek);
        marek.applyAll(fromAnna);

        assertThat(anna.text()).isEqualTo(marek.text());
        assertThat(anna.text())
            .as("each author's word must stay in one piece, result: %s", anna.text())
            .contains(annaWord)
            .contains(marekWord);
    }

    @Test
    @DisplayName("KNOWN RGA LIMITATION: backward typing interleaves the authors' texts")
    void backwardTypingInterleaves_knownLimitation() {
        // The scenario: both people repeatedly insert a character at THE SAME position, moving the
        // cursor back — so every new character lands BEFORE the previous one. All those characters
        // are siblings with the same origin, so RGA orders them purely by timestamp, mixing the
        // authors together.
        RgaDocument anna = new RgaDocument(1);
        RgaDocument marek = new RgaDocument(2);

        java.util.List<Op> fromAnna = new java.util.ArrayList<>();
        java.util.List<Op> fromMarek = new java.util.ArrayList<>();
        for (char c : "cba".toCharArray()) {
            fromAnna.add(anna.insert(0, String.valueOf(c)));
        }
        for (char c : "zyx".toCharArray()) {
            fromMarek.add(marek.insert(0, String.valueOf(c)));
        }

        // Locally each person sees their own text correctly.
        assertThat(anna.text()).isEqualTo("abc");
        assertThat(marek.text()).isEqualTo("xyz");

        anna.applyAll(fromMarek);
        marek.applyAll(fromAnna);

        // Convergence holds — the replicas agree on the result.
        assertThat(anna.text()).isEqualTo(marek.text());

        String merged = anna.text();

        // ...but the result is an interleaving rather than two contiguous words.
        // This assertion is DELIBERATELY inverted: it documents the defect instead of hiding it.
        // If it ever stops being true (for example after a move to Fugue), the test will fail, and
        // that is a signal to update ADR-0001 rather than a regression.
        assertThat(merged)
            .as("ADR-0001: RGA is not maximally non-interleaving. "
                + "The solution is Fugue, deliberately rejected on budget. "
                + "The actual merge result: %s", merged)
            .doesNotContain("abc")
            .doesNotContain("xyz");

        // Every character from both authors is present — this is not data loss, only the wrong
        // order. The distinction matters: the data is safe, what suffers is legibility.
        assertThat(merged).hasSize(6);
        for (char c : "abcxyz".toCharArray()) {
            assertThat(merged).contains(String.valueOf(c));
        }
    }

    @Test
    @DisplayName("editing the same character at once loses neither change")
    void concurrentEditOfSameCharacterKeepsBoth() {
        RgaDocument[] replicas = twoReplicasSharing("cat");
        RgaDocument anna = replicas[0];
        RgaDocument marek = replicas[1];

        // Anna turns "cat" into "cart", Marek turns it into "cats" at the same time.
        Op annaOp = anna.insert(2, "r");
        Op marekOp = marek.insert(3, "s");

        anna.apply(marekOp);
        marek.apply(annaOp);

        assertThat(anna.text()).isEqualTo(marek.text());
        assertThat(anna.text())
            .as("both changes must survive — this is the project's entire thesis")
            .contains("r")
            .contains("s")
            .hasSize(5);
    }

    @Test
    @DisplayName("a deletion and an insertion in the same place commute")
    void deleteCommutesWithConcurrentInsert() {
        RgaDocument[] replicas = twoReplicasSharing("abcdef");
        RgaDocument anna = replicas[0];
        RgaDocument marek = replicas[1];

        Op deletion = anna.delete(2, 2);        // deletes "cd"
        Op insertion = marek.insert(3, "XY");   // inserts between "c" and "d"

        anna.apply(insertion);
        marek.apply(deletion);

        assertThat(anna.text()).isEqualTo(marek.text());
        // The inserted text survives its neighbours being deleted — it does not vanish with them.
        assertThat(anna.text()).isEqualTo("abXYef");
    }
}
