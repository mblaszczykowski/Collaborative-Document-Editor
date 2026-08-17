package dev.collabedit.crdt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BlockContentDroppingTest {
    private static final VersionVector FULLY_STABLE =
        VersionVector.of(Map.of(1, 1_000_000L, 2, 1_000_000L));

    private static RgaDocument documentWithDeletedTail() {
        RgaDocument document = new RgaDocument(1);
        document.insert(0, "hello world");
        document.addMark(0, 5, MarkTypes.LINK, "https://example.test");
        document.delete(0, 5);
        return document;
    }

    @Test
    @DisplayName("a block that dropped its content keeps identity, length and position")
    void droppedContentBlockKeepsIdentityAndLength() {
        RgaDocument document = documentWithDeletedTail();
        String before = document.text();
        int lengthBefore = document.visibleLength();

        int reclaimed = document.gc(FULLY_STABLE);

        assertThat(reclaimed)
            .as("the anchored block kept its characters, so content-dropping reclaimed nothing")
            .isGreaterThan(0);
        assertThat(document.text())
            .as("visible text changed while reclaiming memory from invisible characters")
            .isEqualTo(before);
        assertThat(document.visibleLength()).isEqualTo(lengthBefore);

        assertThat(document.tombstoneCount())
            .as("the tombstone was removed, so the mark anchor it carries is unresolvable")
            .isGreaterThan(0);
    }

    @Test
    @DisplayName("formatting survives its anchor losing its characters")
    void formattingSurvivesContentDropping() {
        RgaDocument document = new RgaDocument(1);
        document.insert(0, "hello world");
        document.addMark(6, 11, MarkTypes.BOLD, "true");
        document.delete(0, 6);

        var before = document.formatting();
        document.gc(FULLY_STABLE);

        assertThat(document.formatting())
            .as("formatting changed while GC was only supposed to release memory")
            .isEqualTo(before);
    }

    @Test
    @DisplayName("an operation anchored inside a dropped block still integrates")
    void operationAnchoredInsideDroppedBlockStillIntegrates() {
        RgaDocument first = new RgaDocument(1);
        Op seed = first.insert(0, "hello world");

        RgaDocument second = new RgaDocument(2);
        second.apply(seed);

        Op anchoredInside = second.insert(3, "XYZ");

        first.addMark(0, 5, MarkTypes.LINK, "https://example.test");
        first.delete(0, 5);
        first.gc(FULLY_STABLE);

        RgaDocument.ApplyResult result = first.apply(anchoredInside);

        assertThat(result)
            .as("the operation was buffered or rejected, so its origin stopped resolving when "
                + "the characters went away - the identity was supposed to be enough")
            .isEqualTo(RgaDocument.ApplyResult.APPLIED);
        assertThat(first.text())
            .as("text inserted against a dropped block landed in the wrong place")
            .isEqualTo("XYZ world");
    }

    @Test
    @DisplayName("a visible block refuses to drop its content")
    void visibleBlockRefusesToDropItsContent() {
        Block visible = new Block(new Id(1, 1), "abc", null, false);
        assertThatThrownBy(visible::dropContent)
            .as("dropping a live block's text deletes characters nobody deleted, silently")
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("reading dropped content fails loudly instead of returning nothing")
    void readingDroppedContentFailsLoudly() {
        Block dropped = Block.withoutContent(new Id(1, 1), 3, null, new Id(2, 9));

        assertThat(dropped.hasContent()).isFalse();
        assertThat(dropped.length()).isEqualTo(3);
        assertThat(dropped.lastId()).isEqualTo(new Id(1, 3));
        assertThat(dropped.containsId(new Id(1, 3))).isTrue();
        assertThat(dropped.containsId(new Id(1, 4))).isFalse();

        assertThatThrownBy(dropped::text).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("a dropped block can still be split, by length alone")
    void droppedBlockSplitsByLength() {
        Block dropped = Block.withoutContent(new Id(1, 1), 5, null, new Id(2, 9));
        Block[] halves = dropped.splitAt(2);

        assertThat(halves[0].length()).isEqualTo(2);
        assertThat(halves[1].length()).isEqualTo(3);
        assertThat(halves[1].start()).isEqualTo(new Id(1, 3));
        assertThat(halves[1].originLeft()).isEqualTo(new Id(1, 2));
        assertThat(halves[0].hasContent()).isFalse();
        assertThat(halves[1].hasContent()).isFalse();
    }

    @Test
    @DisplayName("dropped content survives a snapshot round trip")
    void droppedContentSurvivesASnapshotRoundTrip() {
        RgaDocument document = documentWithDeletedTail();
        document.gc(FULLY_STABLE);

        DocumentSnapshot snapshot = document.snapshot();
        byte[] encoded = SnapshotCodec.encode(snapshot);
        DocumentSnapshot decoded = SnapshotCodec.decode(encoded);

        assertThat(decoded).isNotNull();
        RgaDocument restored = RgaDocument.restore(1, new ArrayBlockList(), decoded);

        assertThat(restored.text())
            .as("a restored document either resurrected deleted text or lost some")
            .isEqualTo(document.text());
        assertThat(restored.tombstoneCount()).isEqualTo(document.tombstoneCount());
        assertThat(restored.formatting()).isEqualTo(document.formatting());
    }

    @Test
    @DisplayName("a snapshot in the previous format is ignored rather than misread")
    void olderSnapshotFormatIsIgnoredRatherThanMisread() {
        RgaDocument document = new RgaDocument(1);
        document.insert(0, "hello");
        DocumentSnapshot current = document.snapshot();
        DocumentSnapshot pretendingToBeOld = new DocumentSnapshot(
            2, current.clock(), current.maxSeen(), current.blocks(), current.marks(),
            current.gcWatermark());

        assertThat(SnapshotCodec.decode(SnapshotCodec.encode(pretendingToBeOld)))
            .as("a snapshot from an older format was accepted; the operation-log decision says ignore, not migrate")
            .isNull();
    }
}
