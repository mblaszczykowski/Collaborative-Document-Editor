package dev.collabedit.app.history;

import static org.assertj.core.api.Assertions.assertThat;

import dev.collabedit.crdt.Op;
import dev.collabedit.crdt.RgaDocument;
import dev.collabedit.crdt.TreapBlockList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The operations a restore generates, and the two ways they can be wrong.
 *
 * <h2>What this catches</h2>
 * A restore does not replay history — it computes the DIFFERENCE between the document now and the
 * version being restored, as a delete plus an insert. Two failure modes follow from that shape, and
 * both are silent:
 *
 * <ol>
 *   <li><b>An operation nothing can deliver.</b> The insert is bounded by the size of the DOCUMENT
 *       rather than by anything a caller typed, so restoring a version of a document that was
 *       emptied re-inserts all of it. As one operation that is undeliverable — nothing downstream
 *       can split an operation — and the response still reports success.</li>
 *   <li><b>Half a character.</b> The common prefix and suffix are found by comparing UTF-16 code
 *       units, and identity in this CRDT is per code unit too, so the scan can stop <i>between</i>
 *       the halves of a surrogate pair. The delete and the insert then each carry a lone surrogate,
 *       which {@code String.getBytes(UTF_8)} turns into {@code '?'} — so the snapshot stops agreeing
 *       with the log, the one thing the operation-log design says cannot happen.</li>
 * </ol>
 *
 * <p>A unit test, because both are properties of the arithmetic over two strings. The stores are
 * {@code null} deliberately: {@code restoreOperations} takes the replica as an argument and reads
 * neither of them, and passing real ones would mean standing up Postgres to observe string
 * manipulation. {@code HistoryIT} and {@code VersionComparisonIT} cover the endpoint.
 */
@DisplayName("A restore's operations are deliverable and never split a character")
class RestoreDiffTest {

    private static final HistoryService HISTORY = new HistoryService(null, null);

    private static RgaDocument documentSaying(String text) {
        RgaDocument document = new RgaDocument(1, new TreapBlockList());
        if (!text.isEmpty()) {
            document.insert(0, text);
        }
        return document;
    }

    /** Applies what a restore would generate, and returns the text it produces. */
    private static String restoredText(String from, String to) {
        RgaDocument document = documentSaying(from);
        List<Op> generated = HISTORY.restoreOperations(
            document, new HistoryService.State(to, List.of()));
        assertThat(generated).allSatisfy(op -> {
            if (op instanceof Op.Insert insert) {
                assertThat(insert.text().length())
                    .as("a generated insert is too large to fit in one frame")
                    .isLessThanOrEqualTo(dev.collabedit.app.domain.DocumentService.MAX_INSERT_CHARACTERS);
                assertThat(Character.isHighSurrogate(insert.text().charAt(insert.text().length() - 1)))
                    .as("a generated insert ends on an unpaired high surrogate: it carries half a "
                        + "character into the operation log")
                    .isFalse();
                assertThat(Character.isLowSurrogate(insert.text().charAt(0)))
                    .as("a generated insert starts on an unpaired low surrogate")
                    .isFalse();
            }
        });
        return document.text();
    }

    @Test
    @DisplayName("an ordinary rollback produces exactly the target text")
    void ordinaryRollback() {
        assertThat(restoredText("the original text BROKEN", "the original text"))
            .isEqualTo("the original text");
    }

    @Test
    @DisplayName("restoring into an emptied document produces exactly the target text")
    void restoreIntoAnEmptiedDocument() {
        String target = "a".repeat(20_000);

        assertThat(restoredText("", target)).isEqualTo(target);
    }

    @Test
    @DisplayName("two versions differing inside an emoji do not split it")
    void emojiOnTheDiffBoundaryIsNotSplit() {
        // "a😀" and "a😃" share 'a' AND the high surrogate \uD83D, differing only on the low half.
        // A code-unit scan stops at prefix = 2, in the middle of the character.
        assertThat(restoredText("a😀", "a😃")).isEqualTo("a😃");
    }

    @Test
    @DisplayName("an emoji at the trailing end of the common region is not split either")
    void emojiOnTheSuffixBoundaryIsNotSplit() {
        // Shared trailing low surrogate, differing high half — the mirror of the case above.
        assertThat(restoredText("😀z", "🨀z")).isEqualTo("🨀z");
    }

    @Test
    @DisplayName("a large emoji document restores whole, across many chunks")
    void largeAstralDocumentRestoresWhole() {
        String target = "😀".repeat(12_000);

        assertThat(restoredText("", target)).isEqualTo(target);
    }
}
