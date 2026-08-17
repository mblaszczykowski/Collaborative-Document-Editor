package dev.collabedit.crdt;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RgaInterleavingTest {
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

        assertThat(anna.text()).isEqualTo("abc");
        assertThat(marek.text()).isEqualTo("xyz");

        anna.applyAll(fromMarek);
        marek.applyAll(fromAnna);

        assertThat(anna.text()).isEqualTo(marek.text());

        String merged = anna.text();

        assertThat(merged)
            .as("the CRDT decision: RGA is not maximally non-interleaving. "
                + "The solution is Fugue, deliberately rejected on budget. "
                + "The actual merge result: %s", merged)
            .doesNotContain("abc")
            .doesNotContain("xyz");

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

        Op annaOp = anna.insert(2, "r");
        Op marekOp = marek.insert(3, "s");

        anna.apply(marekOp);
        marek.apply(annaOp);

        assertThat(anna.text()).isEqualTo(marek.text());
        assertThat(anna.text())
            .as("both changes must survive - this is the project's entire thesis")
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

        Op deletion = anna.delete(2, 2);
        Op insertion = marek.insert(3, "XY");

        anna.apply(insertion);
        marek.apply(deletion);

        assertThat(anna.text()).isEqualTo(marek.text());
        assertThat(anna.text()).isEqualTo("abXYef");
    }
}
