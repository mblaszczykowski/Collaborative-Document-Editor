package dev.collabedit.crdt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A deleted block may drop its characters and stay in the structure (CP18).
 *
 * <table>
 *   <caption>Test → failure mode</caption>
 *   <tr><th>Test</th><th>What it catches</th></tr>
 *   <tr><td>{@code droppedContentBlockKeepsIdentityAndLength}</td>
 *       <td>A block that forgets its length forgets where every character after it lives —
 *           the document still reads correctly, and every cursor position past that point is
 *           wrong.</td></tr>
 *   <tr><td>{@code operationAnchoredInsideDroppedBlockStillIntegrates}</td>
 *       <td>An operation whose origin sits inside a tombstone GC has already emptied. This is
 *           the normal case, not the exotic one: the identity is exactly what was kept, so it
 *           must still resolve.</td></tr>
 *   <tr><td>{@code visibleBlockRefusesToDropItsContent}</td>
 *       <td>Dropping content from a live block, which deletes text nobody asked to delete —
 *           and would do so silently, since the block keeps its length.</td></tr>
 *   <tr><td>{@code readingDroppedContentFailsLoudly}</td>
 *       <td>A caller reading text through a path that does not check. Returning an empty string
 *           would remove characters from the document instead of throwing.</td></tr>
 *   <tr><td>{@code droppedContentSurvivesASnapshotRoundTrip}</td>
 *       <td>Format v3 losing the distinction, so a restored document either resurrects deleted
 *           text or loses the length that positions depend on.</td></tr>
 *   <tr><td>{@code olderSnapshotFormatIsIgnoredRatherThanMisread}</td>
 *       <td>A v2 snapshot read as v3: the byte after {@code deleted} means something different
 *           in each, so accepting it would produce a plausible, wrong document.</td></tr>
 * </table>
 */
class BlockContentDroppingTest {

    private static final VersionVector FULLY_STABLE =
        VersionVector.of(Map.of(1, 1_000_000L, 2, 1_000_000L));

    private static RgaDocument documentWithDeletedTail() {
        RgaDocument document = new RgaDocument(1);
        document.insert(0, "hello world");
        // A mark anchored in the deleted range is what forces the block to stay: without it,
        // GC would simply remove the block and the interesting path would never run.
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
            .as("the anchored block kept its characters, so CP18 reclaimed nothing")
            .isGreaterThan(0);
        assertThat(document.text())
            .as("visible text changed while reclaiming memory from invisible characters")
            .isEqualTo(before);
        assertThat(document.visibleLength()).isEqualTo(lengthBefore);

        // The block is still there. That is the point — it is the anchor and the origin.
        assertThat(document.tombstoneCount())
            .as("the tombstone was removed, so the mark anchor it carries is unresolvable")
            .isGreaterThan(0);
    }

    @Test
    @DisplayName("formatting survives its anchor losing its characters")
    void formattingSurvivesContentDropping() {
        // The whole reason anchored tombstones are protected. If dropping content broke this,
        // the symptom would appear at the NEXT snapshot rather than at the edit, and the
        // culprit would be long out of sight.
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
        // Replica 2 writes against characters that replica 1 has since deleted and emptied.
        // Its origin therefore points inside a block whose content is gone. Placing it needs
        // the identity and nothing else — which is exactly what CP18 keeps — so this is the
        // normal case rather than the exotic one.
        RgaDocument first = new RgaDocument(1);
        Op seed = first.insert(0, "hello world");

        RgaDocument second = new RgaDocument(2);
        second.apply(seed);

        Op anchoredInside = second.insert(3, "XYZ");

        // Only now does replica 1 delete and collect, so the block backing that origin loses
        // its characters before the remote operation ever arrives.
        first.addMark(0, 5, MarkTypes.LINK, "https://example.test");
        first.delete(0, 5);
        first.gc(FULLY_STABLE);

        RgaDocument.ApplyResult result = first.apply(anchoredInside);

        assertThat(result)
            .as("the operation was buffered or rejected, so its origin stopped resolving when "
                + "the characters went away — the identity was supposed to be enough")
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
        Block dropped = Block.withoutContent(new Id(1, 1), 3, null);

        assertThat(dropped.hasContent()).isFalse();
        assertThat(dropped.length()).isEqualTo(3);
        assertThat(dropped.lastId()).isEqualTo(new Id(1, 3));
        assertThat(dropped.containsId(new Id(1, 3))).isTrue();
        assertThat(dropped.containsId(new Id(1, 4))).isFalse();

        // An empty string here would be worse than an exception: it removes characters from
        // the document and nothing reports it.
        assertThatThrownBy(dropped::text).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("a dropped block can still be split, by length alone")
    void droppedBlockSplitsByLength() {
        Block dropped = Block.withoutContent(new Id(1, 1), 5, null);
        Block[] halves = dropped.splitAt(2);

        assertThat(halves[0].length()).isEqualTo(2);
        assertThat(halves[1].length()).isEqualTo(3);
        assertThat(halves[1].start()).isEqualTo(new Id(1, 3));
        // The right part anchors to the last character of the left. That invariant is what
        // makes splitting invisible to the merge algorithm, and it does not need the text.
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
        // v2 and v3 differ by one byte per block, right after `deleted`. Read as v3, a v2
        // snapshot would take the first byte of the text length as the has-content flag and
        // produce a plausible, wrong document — which is why the version is checked before
        // anything else is parsed.
        RgaDocument document = new RgaDocument(1);
        document.insert(0, "hello");
        DocumentSnapshot current = document.snapshot();
        DocumentSnapshot pretendingToBeOld = new DocumentSnapshot(
            2, current.clock(), current.maxSeen(), current.blocks(), current.marks());

        assertThat(SnapshotCodec.decode(SnapshotCodec.encode(pretendingToBeOld)))
            .as("a snapshot from an older format was accepted; ADR-0003 says ignore, not migrate")
            .isNull();
    }
}
