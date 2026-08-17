package dev.collabedit.crdt;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BlockStructureTest {
    private static RgaDocument replica(int id) {
        return new RgaDocument(id, new TreapBlockList());
    }

    private static String markedText(RgaDocument document, String type) {
        StringBuilder marked = new StringBuilder();
        String text = document.text();
        for (MarkSet.Span span : document.formatting()) {
            if (span.marks().containsKey(type)) {
                marked.append(text, span.from(), Math.min(span.to(), text.length()));
            }
        }
        return marked.toString();
    }

    @Test
    @DisplayName("a heading grows when you keep typing at the end of its line")
    void aHeadingGrowsWithTheLine() {
        RgaDocument document = replica(1);
        document.insert(0, "Title\nBody of the document");
        document.addMark(0, 5, MarkTypes.HEADING, "1");

        document.insert(5, " of the report");

        assertThat(markedText(document, MarkTypes.HEADING))
            .as("a heading that stops growing turns the rest of the line into body text, and the "
                + "user has to re-apply it after every word")
            .isEqualTo("Title of the report");
    }

    @Test
    @DisplayName("a heading does not swallow the paragraph under it")
    void aHeadingStopsAtTheNewline() {
        RgaDocument document = replica(1);
        document.insert(0, "Title\nBody");
        document.addMark(0, 5, MarkTypes.HEADING, "1");

        document.insert(6, "More ");

        assertThat(markedText(document, MarkTypes.HEADING)).isEqualTo("Title");
        assertThat(document.text()).isEqualTo("Title\nMore Body");
    }

    @Test
    @DisplayName("two people making the same line a heading agree on the level")
    void concurrentHeadingsConverge() {
        RgaDocument anna = replica(1);
        RgaDocument bartek = replica(2);

        Op seed = anna.insert(0, "Title\nBody");
        bartek.apply(seed);

        Op fromAnna = anna.addMark(0, 5, MarkTypes.HEADING, "1");
        Op fromBartek = bartek.addMark(0, 5, MarkTypes.HEADING, "2");
        anna.apply(fromBartek);
        bartek.apply(fromAnna);

        assertThat(anna.formatting()).isEqualTo(bartek.formatting());
    }

    @Test
    @DisplayName("two people moving the same paragraph produce two copies of it")
    void concurrentMovesOfTheSameParagraphDuplicateIt() {
        RgaDocument anna = replica(1);
        RgaDocument bartek = replica(2);

        Op seed = anna.insert(0, "A\nMOVE ME\nB");
        bartek.apply(seed);

        List<Op> fromAnna = new ArrayList<>();
        fromAnna.add(anna.delete(2, 8));
        fromAnna.add(anna.insert(anna.visibleLength(), "\nMOVE ME"));

        List<Op> fromBartek = new ArrayList<>();
        fromBartek.add(bartek.delete(2, 8));
        fromBartek.add(bartek.insert(0, "MOVE ME\n"));

        fromBartek.forEach(anna::apply);
        fromAnna.forEach(bartek::apply);

        assertThat(anna.text())
            .as("the replicas must still agree - this limit is about the RESULT being wrong for "
                + "the user, not about the algorithm losing its guarantee")
            .isEqualTo(bartek.text());

        int copies = anna.text().split("MOVE ME", -1).length - 1;
        assertThat(copies)
            .as("""
                Two copies, and this is the point of the test. Both deletions apply - deleting an \
                already-deleted character is a no-op - and both insertions apply, because they \
                are insertions of NEW characters that know nothing about each other. The merge \
                rule is behaving exactly as designed; the model is what has no way to express \
                "the same paragraph, moved". That is what Moving Elements in List CRDTs \
                (Kleppmann, 2020) is about, and what this model deliberately does not buy.""")
            .isEqualTo(2);
    }
}
