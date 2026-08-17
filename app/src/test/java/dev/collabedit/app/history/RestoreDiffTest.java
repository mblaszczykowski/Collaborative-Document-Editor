package dev.collabedit.app.history;

import static org.assertj.core.api.Assertions.assertThat;

import dev.collabedit.crdt.Op;
import dev.collabedit.crdt.RgaDocument;
import dev.collabedit.crdt.TreapBlockList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
        assertThat(restoredText("a😀", "a😃")).isEqualTo("a😃");
    }

    @Test
    @DisplayName("an emoji at the trailing end of the common region is not split either")
    void emojiOnTheSuffixBoundaryIsNotSplit() {
        assertThat(restoredText("😀z", "🨀z")).isEqualTo("🨀z");
    }

    @Test
    @DisplayName("a large emoji document restores whole, across many chunks")
    void largeAstralDocumentRestoresWhole() {
        String target = "😀".repeat(12_000);

        assertThat(restoredText("", target)).isEqualTo(target);
    }
}
