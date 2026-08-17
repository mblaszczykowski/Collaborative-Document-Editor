package dev.collabedit.crdt;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GcSafetyTest {
    private static final VersionVector EVERYTHING_STABLE =
        VersionVector.of(Map.of(1, 1_000_000L, 2, 1_000_000L, 9, 1_000_000L));

    @Test
    @DisplayName("a tombstone stays until the DELETION is causally stable, not the insertion")
    void collectingWaitsForTheDeletionToBeStable() {
        RgaDocument author = new RgaDocument(1, new TreapBlockList());
        Op.Insert text = author.insert(0, "abcdefghijklmnopqrstuvw");

        RgaDocument server = new RgaDocument(9, new TreapBlockList());
        server.apply(text);
        RgaDocument behind = new RgaDocument(2, new TreapBlockList());
        behind.apply(text);

        Op.Delete delete = author.delete(22, 1);
        server.apply(delete);

        VersionVector stable = VersionVector.meet(List.of(behind.version(), server.version()));
        assertThat(behind.text()).endsWith("w");
        assertThat(stable.get(1))
            .as("the scenario has to reach the state it is about: the INSERTION must already be "
                + "below the meet while the DELETION is not, or this test proves nothing")
            .isGreaterThanOrEqualTo(text.id().lamport() + text.span() - 1)
            .isLessThan(delete.id().lamport());

        server.gc(stable);

        assertThat(server.contains(new Id(1, 22)))
            .as("GC removed a character a connected replica is still displaying. It will anchor to "
                + "it, and the server will answer RESYNC_REQUIRED - the refusal the tombstone-collection design reserves "
                + "for a client absent longer than the seven-day window.")
            .isTrue();
    }

    @Test
    @DisplayName("a mark waiting in the buffer protects the anchors it will need")
    void aBufferedMarksAnchorIsNotCollected() {
        RgaDocument author = new RgaDocument(1, new TreapBlockList());
        Op.Insert base = author.insert(0, "abcdef");

        RgaDocument other = new RgaDocument(2, new TreapBlockList());
        other.apply(base);
        Op.Insert head = other.insert(0, "Z");
        Op.AddMark mark = other.addMark(0, 7, MarkTypes.LINK, "https://example.test");
        Op.Delete delete = author.delete(5, 1);

        RgaDocument server = new RgaDocument(9, new TreapBlockList());
        server.apply(base);
        assertThat(server.apply(mark))
            .as("the scenario needs the mark to be BUFFERED - if it applies, nothing is being tested")
            .isEqualTo(RgaDocument.ApplyResult.BUFFERED);
        server.apply(delete);

        server.gc(EVERYTHING_STABLE);
        server.apply(head);

        assertThat(server.pendingCount())
            .as("the mark is stuck in the buffer for good: GC collected the tombstone its end "
                + "anchor points at, so it can never resolve")
            .isZero();
        assertThat(server.formatting())
            .as("the link - or, in the product, a comment or a suggestion - disappeared as a side "
                + "effect of reclaiming memory, with no error anywhere")
            .isNotEmpty();
    }

    @Test
    @DisplayName("a block whose content was already dropped is not counted again when removed")
    void reclaimedCharactersAreNotCountedTwice() {
        RgaDocument author = new RgaDocument(1, new TreapBlockList());
        Op.Insert first = author.insert(0, "abc");

        RgaDocument other = new RgaDocument(2, new TreapBlockList());
        other.apply(first);
        Op.Insert second = other.insert(3, "xyz");

        RgaDocument document = new RgaDocument(9, new TreapBlockList());
        document.apply(first);
        document.apply(second);
        document.apply(author.delete(0, 3));

        RgaDocument mirror = new RgaDocument(2, new TreapBlockList());
        mirror.apply(first);
        mirror.apply(second);
        document.apply(mirror.delete(3, 3));

        int total = document.tombstoneCount();
        int reclaimed = document.gc(EVERYTHING_STABLE)
            + document.gc(EVERYTHING_STABLE)
            + document.gc(EVERYTHING_STABLE);

        assertThat(reclaimed)
            .as("GC reported reclaiming more characters than the document ever held. "
                + "crdt_gc_reclaimed_characters_total answers 'is GC working at all', "
                + "and a number that can exceed the document's size answers it in the reassuring "
                + "direction.")
            .isEqualTo(total);
    }

    @Test
    @DisplayName("a buffered deletion protects every character in its range, not only the first")
    void aBufferedDeletionProtectsItsWholeRange() {
        RgaDocument author = new RgaDocument(1, new TreapBlockList());
        Op.Insert first = author.insert(0, "abc");
        Op.Insert second = author.insert(3, "def");
        Op.Insert third = author.insert(6, "ghi");
        Op.Delete wholeRun = author.delete(0, 9);
        assertThat(wholeRun.targets())
            .as("the scenario depends on the deletion being ONE range over a merged block")
            .hasSize(1);

        RgaDocument middle = new RgaDocument(2, new TreapBlockList());
        middle.apply(first);
        middle.apply(second);
        Op.Delete middleOnly = middle.delete(3, 3);

        RgaDocument server = new RgaDocument(9, new TreapBlockList());
        server.apply(first);
        server.apply(second);
        server.apply(middleOnly);
        assertThat(server.apply(wholeRun))
            .as("the scenario needs the whole-run deletion to be BUFFERED - if it applies, "
                + "nothing is being tested")
            .isEqualTo(RgaDocument.ApplyResult.BUFFERED);

        server.gc(EVERYTHING_STABLE);

        server.apply(third);

        assertThat(server.pendingCount())
            .as("the buffered deletion can never apply: GC removed a block in the MIDDLE of its "
                + "range, so isRangeFullyPresent stops there for ever")
            .isZero();
        assertThat(server.text())
            .as("every operation has been delivered, and this replica still shows text every "
                + "other replica has deleted")
            .isEmpty();
    }
}
