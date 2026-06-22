package dev.collabedit.crdt;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The three garbage-collection defects an audit found, pinned as the exact scenarios that produced
 * them.
 *
 * <p>{@code RgaConvergenceProperties.gcNeverRemovesWhatAnotherReplicaStillShows} is the general
 * statement and it is the more valuable test; these are here because a property that fails prints a
 * shrunk counterexample, and a shrunk counterexample is not the same as a sentence saying what went
 * wrong. Each method below is one of those sentences.
 *
 * <table>
 * <caption>Test -&gt; the failure it prevents returning</caption>
 * <tr><th>Test</th><th>What it caught</th></tr>
 * <tr><td>{@code collectingWaitsForTheDeletionToBeStable}</td>
 * <td>GC testing the stability of the INSERTION, so a character deleted a second ago was
 * collectable while a peer still displayed it — {@code RESYNC_REQUIRED} on a
 * sub-second race.</td></tr>
 * <tr><td>{@code aBufferedMarksAnchorIsNotCollected}</td>
 * <td>GC ignoring the pending buffer, so a comment or suggestion anchor was destroyed while
 * the operation that needed it waited for its other anchor — silent, permanent, and the
 * operation leaked in {@code pending} for the life of the process.</td></tr>
 * <tr><td>{@code reclaimedCharactersAreNotCountedTwice}</td>
 * <td>A block whose content an earlier sweep dropped being counted again when it was finally
 * removed, so the the tombstone-collection design diagnostic reported more characters reclaimed than the
 * document ever held.</td></tr>
 * </table>
 */
class GcSafetyTest {

    private static final VersionVector EVERYTHING_STABLE =
        VersionVector.of(Map.of(1, 1_000_000L, 2, 1_000_000L, 9, 1_000_000L));

    /**
     * The character is old, the deletion is new, and one replica has not seen the deletion.
     *
     * <p>Under the condition GC used to apply — the insertion's timestamp against the inserting
     * replica's entry — the insertion is far below {@code stable} for reasons that have nothing to
     * do with the delete, so the tombstone was collected immediately. Everything about this
     * scenario is ordinary: a paragraph written a while ago, deleted now, and a peer 10 ms behind.
     */
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
        // `behind` deliberately does NOT receive the delete — it is still showing the character.

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
                + "it, and the server will answer RESYNC_REQUIRED — the refusal the tombstone-collection design reserves "
                + "for a client absent longer than the seven-day window.")
            .isTrue();
    }

    /**
     * A mark waiting for one anchor, whose other anchor is an otherwise collectable tombstone.
     *
     * <p>The document-level assertion is the one that matters: the formatting must come back once
     * the missing anchor arrives, and the buffer must drain. Both were false before {@code gc}
     * consulted {@code pending}.
     */
    @Test
    @DisplayName("a mark waiting in the buffer protects the anchors it will need")
    void aBufferedMarksAnchorIsNotCollected() {
        RgaDocument author = new RgaDocument(1, new TreapBlockList());
        Op.Insert base = author.insert(0, "abcdef");

        RgaDocument other = new RgaDocument(2, new TreapBlockList());
        other.apply(base);
        // 2@k at the head, deliberately NOT delivered to the server yet.
        Op.Insert head = other.insert(0, "Z");
        // Start anchor on the undelivered character, end anchor on the last one — which the author
        // is about to delete.
        Op.AddMark mark = other.addMark(0, 7, MarkTypes.LINK, "https://example.test");
        Op.Delete delete = author.delete(5, 1);

        RgaDocument server = new RgaDocument(9, new TreapBlockList());
        server.apply(base);
        assertThat(server.apply(mark))
            .as("the scenario needs the mark to be BUFFERED — if it applies, nothing is being tested")
            .isEqualTo(RgaDocument.ApplyResult.BUFFERED);
        server.apply(delete);

        server.gc(EVERYTHING_STABLE);
        server.apply(head);

        assertThat(server.pendingCount())
            .as("the mark is stuck in the buffer for good: GC collected the tombstone its end "
                + "anchor points at, so it can never resolve")
            .isZero();
        assertThat(server.formatting())
            .as("the link — or, in the product, a comment or a suggestion — disappeared as a side "
                + "effect of reclaiming memory, with no error anywhere")
            .isNotEmpty();
    }

    /**
     * Two sweeps over the same block: the first drops its content because it is still an origin,
     * the second removes it once the block it anchored is gone.
     */
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

    /**
     * A deletion waiting in the buffer needs its WHOLE range, not merely the character it starts
     * at.
     *
     * <p>The first version of the pending-buffer fix protected only {@code range.start}, on the
     * reasoning that the interior is unreachable without the start.
     * {@code RgaDocument.isRangeFullyPresent} walks a range <b>block by block</b>, so removing any
     * block after the first breaks the walk at that point and the deletion can never apply.
     *
     * <p>The result is the outcome the whole algorithm exists to prevent: every operation
     * eventually delivered, every other replica showing the text gone, and this one showing it
     * present with the deletion wedged in the buffer for good.
     */
    @Test
    @DisplayName("a buffered deletion protects every character in its range, not only the first")
    void aBufferedDeletionProtectsItsWholeRange() {
        // One replica types three runs that merge into a single run-length block, then deletes all
        // nine characters as ONE range.
        RgaDocument author = new RgaDocument(1, new TreapBlockList());
        Op.Insert first = author.insert(0, "abc");
        Op.Insert second = author.insert(3, "def");
        Op.Insert third = author.insert(6, "ghi");
        Op.Delete wholeRun = author.delete(0, 9);
        assertThat(wholeRun.targets())
            .as("the scenario depends on the deletion being ONE range over a merged block")
            .hasSize(1);

        // A second replica tombstones the middle three characters, so that part of the range
        // becomes an unreferenced, causally stable tombstone GC would otherwise be free to remove.
        RgaDocument middle = new RgaDocument(2, new TreapBlockList());
        middle.apply(first);
        middle.apply(second);
        Op.Delete middleOnly = middle.delete(3, 3);

        // The receiver never got the LAST run, so the whole-run deletion cannot apply yet.
        RgaDocument server = new RgaDocument(9, new TreapBlockList());
        server.apply(first);
        server.apply(second);
        server.apply(middleOnly);
        assertThat(server.apply(wholeRun))
            .as("the scenario needs the whole-run deletion to be BUFFERED — if it applies, "
                + "nothing is being tested")
            .isEqualTo(RgaDocument.ApplyResult.BUFFERED);

        server.gc(EVERYTHING_STABLE);

        // The missing run finally arrives, which is the moment the buffered deletion should drain.
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
