package dev.collabedit.app.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * How the server splits text it generates into operations.
 *
 * <h2>The two failure modes this catches</h2>
 * <ol>
 *   <li><b>An operation too large to deliver.</b> Nothing downstream can split an operation — its
 *       identity is what the merge algorithm is built on — so an insert of arbitrary size becomes
 *       an operation no frame can carry: written to the log, replayed on every load, and delivered
 *       to nobody, silently. Three server-side paths generate inserts and one of them, the version
 *       restore, is bounded by the size of the DOCUMENT rather than by anything a caller typed.</li>
 *   <li><b>A lone surrogate in the operation log.</b> Identity in this CRDT is per UTF-16 code
 *       unit, so an emoji is two characters — and a naive cut every 8192 units lands between them
 *       roughly once per 8192 emoji. {@code SnapshotCodec} spells out what that costs downstream:
 *       {@code String.getBytes(UTF_8)} replaces a lone surrogate with {@code '?'}, so the snapshot
 *       disagrees with the log, which the operation-log design says cannot happen. Snapshot format
 *       v4 exists to survive this arriving from a block split; the log has no such escape hatch.</li>
 * </ol>
 *
 * <p>A unit test rather than an integration one: the rule is a property of a pure function over a
 * string, and standing up Postgres to observe it would make the check slower without making it
 * stronger. {@code HistoryIT} exercises the same function through a real restore.
 */
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
        // Not one empty piece: Op.Insert refuses empty text, so a piece here would be an
        // IllegalArgumentException on a path that should simply do nothing.
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
            .as("the pieces do not reassemble to the original — a restore would produce text that "
                + "is not the version it claims to restore")
            .isEqualTo(text);
    }

    @Test
    @DisplayName("a surrogate pair on the boundary is not cut in half")
    void surrogatePairsSurviveTheBoundary() {
        // The emoji straddles the cut: 8191 filler units, then a two-unit character.
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
        // And the emoji is intact in ONE piece rather than merely reassembling across two.
        assertThat(pieces).anySatisfy(piece -> assertThat(piece).contains("😀"));
    }

    @Test
    @DisplayName("text that is entirely astral characters still terminates and stays whole")
    void allAstralTextIsSplitWithoutLoss() {
        // Every cut lands between the halves of a pair, so the back-off applies on every boundary.
        // Written because the back-off is the one place this loop could fail to advance.
        String text = "😀".repeat(MAX * 2);

        List<String> pieces = DocumentService.splitForInsert(text);

        assertThat(String.join("", pieces)).isEqualTo(text);
        assertThat(pieces).allSatisfy(piece ->
            assertThat(piece.length() % 2)
                .as("a piece has an odd length, so it holds half of a character")
                .isZero());
    }
}
