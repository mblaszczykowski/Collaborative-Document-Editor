package dev.collabedit.app.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Generated inserts are frame-sized and never split a character")
class InsertChunkingTest {
    private static final int MAX = DocumentService.MAX_INSERT_CHARACTERS;

    @Test
    @DisplayName("nothing is split when it already fits")
    void shortTextIsOnePiece() {
        assertThat(DocumentService.splitForInsert("hello")).containsExactly("hello");
    }

    @Test
    @DisplayName("empty text produces no operations at all")
    void emptyTextProducesNothing() {
        assertThat(DocumentService.splitForInsert("")).isEmpty();
    }

    @Test
    @DisplayName("every piece fits in one operation, and they reassemble exactly")
    void piecesFitAndReassemble() {
        String text = "x".repeat(MAX * 3 + 17);

        List<String> pieces = DocumentService.splitForInsert(text);

        assertThat(pieces).allSatisfy(piece ->
            assertThat(piece.length()).isBetween(1, MAX));
        assertThat(String.join("", pieces))
            .as("the pieces do not reassemble to the original - a restore would produce text that "
                + "is not the version it claims to restore")
            .isEqualTo(text);
    }

    @Test
    @DisplayName("a surrogate pair on the boundary is not cut in half")
    void surrogatePairsSurviveTheBoundary() {
        String text = "a".repeat(MAX - 1) + "😀" + "b".repeat(10);

        List<String> pieces = DocumentService.splitForInsert(text);

        assertThat(pieces).allSatisfy(piece -> {
            assertThat(Character.isHighSurrogate(piece.charAt(piece.length() - 1)))
                .as("a piece ends on an unpaired high surrogate: '%s'", piece)
                .isFalse();
            assertThat(Character.isLowSurrogate(piece.charAt(0)))
                .as("a piece starts on an unpaired low surrogate")
                .isFalse();
        });
        assertThat(String.join("", pieces)).isEqualTo(text);
        assertThat(pieces).anySatisfy(piece -> assertThat(piece).contains("😀"));
    }

    @Test
    @DisplayName("text that is entirely astral characters still terminates and stays whole")
    void allAstralTextIsSplitWithoutLoss() {
        String text = "😀".repeat(MAX * 2);

        List<String> pieces = DocumentService.splitForInsert(text);

        assertThat(String.join("", pieces)).isEqualTo(text);
        assertThat(pieces).allSatisfy(piece ->
            assertThat(piece.length() % 2)
                .as("a piece has an odd length, so it holds half of a character")
                .isZero());
    }
}
