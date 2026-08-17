package dev.collabedit.crdt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MarkTest {
    private static RgaDocument document(int replicaId) {
        return new RgaDocument(replicaId, new TreapBlockList());
    }

    private static Map<String, String> marksAt(RgaDocument document, int index) {
        for (MarkSet.Span span : document.formatting()) {
            if (index >= span.from() && index < span.to()) {
                return span.marks();
            }
        }
        return Map.of();
    }

    @Test
    @DisplayName("text typed into the middle of a bold range inherits it - even when the author had not seen it")
    void textInsertedIntoMarkedRangeInheritsMark() {
        RgaDocument ania = document(1);
        RgaDocument bartek = document(2);

        Op.Insert base = ania.insert(0, "abcdef");
        bartek.apply(base);

        Op.AddMark bold = ania.addMark(1, 5, MarkTypes.BOLD, null);

        Op.Insert inserted = bartek.insert(3, "XY");

        ania.apply(inserted);
        bartek.apply(bold);

        assertThat(ania.text()).isEqualTo("abcXYdef");
        assertThat(bartek.text()).isEqualTo(ania.text());

        assertThat(marksAt(ania, 3))
            .as("text typed into the middle of a bold range did not inherit it - a hole in the formatting")
            .containsEntry(MarkTypes.BOLD, null);
        assertThat(marksAt(bartek, 3)).isEqualTo(marksAt(ania, 3));
        assertThat(bartek.formatting()).isEqualTo(ania.formatting());
    }

    @Test
    @DisplayName("bold grows when you type immediately after it")
    void expandingMarkGrowsAtEnd() {
        RgaDocument document = document(1);
        document.insert(0, "abcdef");
        document.addMark(1, 3, MarkTypes.BOLD, null);

        assertThat(marksAt(document, 2)).containsKey(MarkTypes.BOLD);
        assertThat(marksAt(document, 3)).doesNotContainKey(MarkTypes.BOLD);

        document.insert(3, "X");

        assertThat(document.text()).isEqualTo("abcXdef");
        assertThat(marksAt(document, 3))
            .as("the bold did not cover text appended at its end")
            .containsKey(MarkTypes.BOLD);
    }

    @Test
    @DisplayName("a link does NOT grow when you type immediately after it")
    void nonExpandingMarkDoesNot() {
        RgaDocument document = document(1);
        document.insert(0, "abcdef");
        document.addMark(1, 3, MarkTypes.LINK, "https://example.test");

        document.insert(3, "X");

        assertThat(marksAt(document, 2)).containsEntry(MarkTypes.LINK, "https://example.test");
        assertThat(marksAt(document, 3))
            .as("the link covered text appended after it - the link address covers content that is not part of it")
            .doesNotContainKey(MarkTypes.LINK);
    }

    @Test
    @DisplayName("formatting does not grow backwards")
    void markDoesNotGrowBackwards() {
        RgaDocument document = document(1);
        document.insert(0, "abcdef");
        document.addMark(2, 4, MarkTypes.BOLD, null);

        document.insert(2, "X");

        assertThat(document.text()).isEqualTo("abXcdef");
        assertThat(marksAt(document, 2))
            .as("text typed before the bold became bold")
            .doesNotContainKey(MarkTypes.BOLD);
        assertThat(marksAt(document, 3)).containsKey(MarkTypes.BOLD);
    }

    @Test
    @DisplayName("concurrently applying and removing formatting converge on the same result")
    void concurrentAddAndRemoveConverge() {
        RgaDocument ania = document(1);
        RgaDocument bartek = document(2);

        Op.Insert base = ania.insert(0, "abcdef");
        bartek.apply(base);

        Op.AddMark bold = ania.addMark(1, 4, MarkTypes.BOLD, null);
        Op.RemoveMark unbold = bartek.removeMark(1, 4, MarkTypes.BOLD);

        ania.apply(unbold);
        bartek.apply(bold);

        assertThat(bartek.formatting())
            .as("the replicas see different formatting of the same text - no total order")
            .isEqualTo(ania.formatting());

        RgaDocument third = document(3);
        third.apply(base);
        third.apply(unbold);
        third.apply(bold);
        assertThat(third.formatting()).isEqualTo(ania.formatting());
    }

    @Test
    @DisplayName("formatting survives the deletion of neighbouring text")
    void markSurvivesDeletionOfNeighbourhood() {
        RgaDocument document = document(1);
        document.insert(0, "abcdef");
        document.addMark(2, 4, MarkTypes.BOLD, null);

        document.delete(0, 2);

        assertThat(document.text()).isEqualTo("cdef");
        assertThat(marksAt(document, 0))
            .as("the formatting disappeared after text NEXT TO it was deleted")
            .containsKey(MarkTypes.BOLD);
        assertThat(marksAt(document, 2)).doesNotContainKey(MarkTypes.BOLD);
    }

    @Test
    @DisplayName("a mark waits in the buffer for its anchors")
    void markWaitsForItsAnchors() {
        RgaDocument ania = document(1);
        RgaDocument bartek = document(2);

        Op.Insert base = ania.insert(0, "abcdef");
        Op.AddMark bold = ania.addMark(1, 4, MarkTypes.BOLD, null);

        assertThat(bartek.apply(bold)).isEqualTo(RgaDocument.ApplyResult.BUFFERED);
        assertThat(bartek.formatting())
            .as("a mark was applied without its anchors - formatting in a random place")
            .isEmpty();

        bartek.apply(base);

        assertThat(bartek.formatting())
            .as("the mark was not retried once its anchors arrived")
            .isEqualTo(ania.formatting());
        assertThat(bartek.pendingCount()).isZero();
    }

    @Test
    @DisplayName("adjacent segments with the same formatting are merged")
    void adjacentIdenticalSpansAreMerged() {
        RgaDocument document = document(1);
        document.insert(0, "abcdef");
        document.addMark(0, 3, MarkTypes.BOLD, null);
        document.addMark(3, 6, MarkTypes.BOLD, null);

        List<MarkSet.Span> spans = document.formatting();

        assertThat(spans)
            .as("two touching bold ranges produced two segments instead of one")
            .hasSize(1);
        assertThat(spans.getFirst().from()).isZero();
        assertThat(spans.getFirst().to()).isEqualTo(6);
    }

    @Test
    @DisplayName("a repeated mark is recognised as a duplicate")
    void duplicateMarkIsRecognised() {
        RgaDocument ania = document(1);
        RgaDocument bartek = document(2);

        bartek.apply(ania.insert(0, "abcdef"));
        Op.AddMark bold = ania.addMark(1, 4, MarkTypes.BOLD, null);

        assertThat(bartek.apply(bold)).isEqualTo(RgaDocument.ApplyResult.APPLIED);
        assertThat(bartek.apply(bold))
            .as("retransmitting a mark after reconnect is not recognised as a duplicate")
            .isEqualTo(RgaDocument.ApplyResult.DUPLICATE);
        assertThat(bartek.markCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("formatting survives a snapshot and a replica rebuild")
    void markSurvivesSnapshotRoundTrip() {
        RgaDocument original = document(1);
        original.insert(0, "abcdef");
        original.addMark(1, 4, MarkTypes.BOLD, null);
        original.addMark(2, 5, MarkTypes.LINK, "https://example.test");

        byte[] encoded = SnapshotCodec.encode(original.snapshot());
        DocumentSnapshot decoded = SnapshotCodec.decode(encoded);
        assertThat(decoded).isNotNull();

        RgaDocument restored = RgaDocument.restore(1, new TreapBlockList(), decoded);

        assertThat(restored.text()).isEqualTo(original.text());
        assertThat(restored.formatting())
            .as("an instance restart lost the formatting - the text came back, the appearance did not")
            .isEqualTo(original.formatting());
    }

    @Test
    @DisplayName("a replica rebuilt from a snapshot can resolve a conflict with a mark still to arrive")
    void restoredReplicaStillResolvesConflicts() {
        RgaDocument ania = document(1);
        RgaDocument bartek = document(2);

        Op.Insert base = ania.insert(0, "abcdef");
        bartek.apply(base);

        Op.AddMark bold = ania.addMark(1, 4, MarkTypes.BOLD, null);
        bartek.apply(bold);

        RgaDocument restored = RgaDocument.restore(
            2, new TreapBlockList(), SnapshotCodec.decode(SnapshotCodec.encode(bartek.snapshot())));

        Op.RemoveMark unbold = ania.removeMark(1, 4, MarkTypes.BOLD);
        restored.apply(unbold);
        bartek.apply(unbold);

        assertThat(restored.formatting())
            .as("a replica rebuilt from a snapshot resolved the conflict differently from one that was not")
            .isEqualTo(bartek.formatting());
        assertThat(restored.formatting()).isEqualTo(ania.formatting());
    }

    @Test
    @DisplayName("GC does not delete a tombstone that is a mark anchor")
    void garbageCollectionSparesMarkAnchors() {
        RgaDocument document = document(1);
        document.insert(0, "abcdef");

        document.addMark(4, 6, MarkTypes.LINK, "https://example.test");
        document.delete(5, 1);

        assertThat(document.text()).isEqualTo("abcde");
        assertThat(marksAt(document, 4)).containsKey(MarkTypes.LINK);

        document.gc(VersionVector.of(Map.of(1, 1000L)));

        assertThat(document.text()).isEqualTo("abcde");
        assertThat(marksAt(document, 4))
            .as("GC deleted a mark anchor - the formatting of visible text was lost while "
                + "tidying up memory")
            .containsEntry(MarkTypes.LINK, "https://example.test");
    }

    @Test
    @DisplayName("text typed where a deleted fragment was does NOT inherit its formatting")
    void textTypedOverDeletedRangeDoesNotInheritItsMark() {
        RgaDocument document = document(1);
        document.insert(0, "abcdef");
        document.addMark(1, 4, MarkTypes.BOLD, null);
        document.delete(1, 3);
        assertThat(document.text()).isEqualTo("aef");

        document.insert(1, "XYZ");

        assertThat(document.text()).isEqualTo("aXYZef");
        assertThat(marksAt(document, 1))
            .as("the behaviour changed - check whether that was a deliberate decision")
            .doesNotContainKey(MarkTypes.BOLD);
    }

    @Test
    @DisplayName("GC still collects tombstones nothing anchors to")
    void garbageCollectionStillCollectsUnreferenced() {
        RgaDocument document = document(1);
        document.insert(0, "abcdef");
        document.delete(4, 2);

        int before = document.tombstoneCount();
        assertThat(before).isPositive();

        int removed = document.gc(VersionVector.of(Map.of(1, 1000L)));

        assertThat(removed)
            .as("anchor protection blocked GC where there are no anchors at all")
            .isPositive();
        assertThat(document.text()).isEqualTo("abcd");
    }

    @Test
    @DisplayName("a mark's wire format round-trips in both directions")
    void markSurvivesWireRoundTrip() {
        RgaDocument document = document(1);
        document.insert(0, "abcdef");

        Op.AddMark added = document.addMark(1, 4, MarkTypes.LINK, "https://example.test/a?b=c");
        Op.RemoveMark removed = document.removeMark(2, 5, MarkTypes.BOLD);

        assertThat(OpCodec.decode(OpCodec.encode(added))).isEqualTo(added);
        assertThat(OpCodec.decode(OpCodec.encode(removed))).isEqualTo(removed);
    }

    @Test
    @DisplayName("two overlapping comments both keep their place - the newer does not hide the older")
    void overlappingIdentityMarksAreBothVisibleToPlacements() {
        RgaDocument document = document(1);
        document.insert(0, "The quick brown fox jumps");
        document.addMark(4, 9, MarkTypes.COMMENT, "THREAD-1");
        document.addMark(0, 25, MarkTypes.COMMENT, "THREAD-2");

        assertThat(document.formatting())
            .as("the render is expected to keep resolving one winner per type")
            .hasSize(1);

        assertThat(document.markPlacements(MarkTypes.COMMENT))
            .as("an older comment disappeared because a later one overlapped it")
            .containsOnlyKeys("THREAD-1", "THREAD-2")
            .containsEntry("THREAD-1", new MarkSet.Placement(4, 9))
            .containsEntry("THREAD-2", new MarkSet.Placement(0, 25));
    }

    @Test
    @DisplayName("an overlapping suggestion does not shrink another suggestion's range")
    void overlappingSuggestionsKeepTheirOwnRanges() {
        RgaDocument document = document(1);
        document.insert(0, "The quick brown fox");
        document.addMark(0, 9, MarkTypes.SUGGEST_DELETE, "S-A");
        document.addMark(4, 19, MarkTypes.SUGGEST_DELETE, "S-B");

        assertThat(document.markPlacements(MarkTypes.SUGGEST_DELETE))
            .containsEntry("S-A", new MarkSet.Placement(0, 9))
            .containsEntry("S-B", new MarkSet.Placement(4, 19));
    }

    @Test
    @DisplayName("a mark whose anchored text is deleted has no placement")
    void deletedTextTakesTheAnchorWithIt() {
        RgaDocument document = document(1);
        document.insert(0, "abcdef");
        document.addMark(1, 4, MarkTypes.COMMENT, "THREAD-1");
        document.delete(1, 3);

        assertThat(document.markPlacements(MarkTypes.COMMENT))
            .as("a thread whose text is gone must not report as anchored - that is the one case "
                + "the detached presentation exists for")
            .doesNotContainKey("THREAD-1");
    }

    @Test
    @DisplayName("removing the anchor of one comment does not detach the other")
    void removalOnlyTakesAwayTheMarkItCovers() {
        RgaDocument document = document(1);
        document.insert(0, "The quick brown fox jumps");
        document.addMark(4, 9, MarkTypes.COMMENT, "THREAD-1");
        document.addMark(0, 25, MarkTypes.COMMENT, "THREAD-2");
        document.removeMark(4, 9, MarkTypes.COMMENT);

        assertThat(document.markPlacements(MarkTypes.COMMENT))
            .as("the removal either took away too much or too little")
            .containsOnlyKeys("THREAD-2");
    }

    @Test
    @DisplayName("two operations carrying one identity report the range they jointly cover")
    void oneIdentitySpreadOverTwoOperationsIsUnioned() {
        RgaDocument document = document(1);
        document.insert(0, "The quick brown fox jumps");
        document.addMark(4, 9, MarkTypes.SUGGEST_DELETE, "S-A");
        document.addMark(10, 15, MarkTypes.SUGGEST_DELETE, "S-A");

        assertThat(document.markPlacements(MarkTypes.SUGGEST_DELETE))
            .containsExactly(entry("S-A", new MarkSet.Placement(4, 15)));
    }

    @Test
    @DisplayName("marks with no value carry no identity and are left out")
    void decorationMarksHaveNoPlacement() {
        RgaDocument document = document(1);
        document.insert(0, "abcdef");
        document.addMark(1, 4, MarkTypes.BOLD, null);

        assertThat(document.markPlacements(MarkTypes.BOLD)).isEmpty();
    }
}
