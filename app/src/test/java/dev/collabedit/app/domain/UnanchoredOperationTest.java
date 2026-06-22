package dev.collabedit.app.domain;

import static org.assertj.core.api.Assertions.assertThat;

import dev.collabedit.crdt.Anchor;
import dev.collabedit.crdt.Id;
import dev.collabedit.crdt.MarkTypes;
import dev.collabedit.crdt.Op;
import dev.collabedit.crdt.RgaDocument;
import dev.collabedit.crdt.TreapBlockList;
import dev.collabedit.crdt.VersionVector;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * An operation whose anchor GC has collected is refused — <b>whatever kind of operation it is</b>.
 *
 * <h2>The gap this closes</h2>
 * {@code DocumentReplica.isPermanentlyUnanchored} began with
 * {@code if (!(op instanceof Op.Insert insert)) return false;}, so it answered "mergeable" for a
 * DELETE whose target characters GC had removed and for a MARK whose anchors it had removed.
 *
 * <p>They are not mergeable, and the way they fail is the one the tombstone-collection design rejects in writing.
 * {@code RgaDocument.apply} cannot see the target, so it answers BUFFERED — it holds the operation
 * waiting for a cause. {@code DocumentReplica.merge} treats BUFFERED as news deliberately (an early
 * operation is complete, it is simply early), so the operation was <b>written to the log and
 * acknowledged to the client</b>, which released it from its offline buffer — while it never
 * applied to anything, and never could, because the characters it names no longer exist anywhere.
 * The tombstone-collection design's phrase for that outcome is "silently losing the user's work"; the refusal existed and
 * covered one of the four operation types.
 *
 * <p>It also grew the pending buffer permanently, one entry per such operation, which is the other
 * half of what {@code collabedit.persistence.max-pending-operations} now bounds.
 *
 * <h2>Why the condition stays narrow</h2>
 * Both halves are required — the identifier must be ABSENT <b>and</b> at or below the GC boundary.
 * Absence alone means nothing (an operation may simply have outrun its cause, which is the normal
 * case the buffer exists for), and being below the boundary alone means nothing either (the
 * character may still be alive, because GC removes only tombstones). The two "not refused" tests
 * below are the ones that would fail if the condition were widened — and widening it would mean
 * rejecting valid operations, that is, losing the user's work in the name of protecting it.
 */
@DisplayName("An operation anchored to collected characters is refused, for every operation type")
class UnanchoredOperationTest {

    /** A replica whose GC boundary has been advanced past a departed client's characters. */
    private record Fixture(DocumentReplica replica, Id collected, Id alive) {
    }

    /**
     * Builds a document where replica 2's characters are deleted and then collected, while
     * replica 3's stay visible.
     *
     * <p>The collection is driven through the real {@code gc(stable)} path rather than by setting a
     * boundary field, because the boundary now lives in {@code RgaDocument} and travels in the
     * snapshot — a test that set it directly would be asserting against a value the production path
     * does not produce.
     */
    private static Fixture fixture() {
        RgaDocument document = new RgaDocument(1, new TreapBlockList());
        RgaDocument departed = new RgaDocument(2, new TreapBlockList());
        RgaDocument present = new RgaDocument(3, new TreapBlockList());

        Op.Insert byDeparted = departed.insert(0, "gone");
        document.apply(byDeparted);
        Op.Insert byPresent = present.insert(0, "here");
        document.apply(byPresent);

        Id collected = byDeparted.id();
        Id alive = byPresent.id();

        // Delete the departed replica's characters, then collect them: GC removes TOMBSTONES, so a
        // character has to be deleted before it can be collected at all.
        document.apply(document.delete(indexOf(document, collected), 4));
        int reclaimed = document.gc(VersionVector.of(Map.of(
            1, document.clock(), 2, byDeparted.id().lamport() + 8, 3, byPresent.id().lamport() + 8)));
        assertThat(reclaimed)
            .as("the fixture's premise: GC has to have actually collected something")
            .isGreaterThan(0);
        assertThat(document.contains(collected))
            .as("the fixture's premise: the collected character must be gone from the structure")
            .isFalse();

        return new Fixture(
            new DocumentReplica(UUID.randomUUID(), document, 0, System.currentTimeMillis()),
            collected, alive);
    }

    private static int indexOf(RgaDocument document, Id id) {
        for (int i = 0; i < document.visibleLength(); i++) {
            if (document.idAtVisible(i).equals(id)) {
                return i;
            }
        }
        throw new AssertionError("the fixture cannot find " + id);
    }

    // ------------------------------------------------------------------
    // Refused
    // ------------------------------------------------------------------

    @Test
    @DisplayName("an insertion after a collected character is refused — the case that already worked")
    void insertAfterACollectedOriginIsRefused() {
        Fixture fixture = fixture();
        Op op = new Op.Insert(new Id(9, 1), fixture.collected, "late");

        assertThat(fixture.replica.isPermanentlyUnanchored(op)).isTrue();
    }

    @Test
    @DisplayName("a delete of collected characters is refused")
    void deleteOfCollectedCharactersIsRefused() {
        Fixture fixture = fixture();
        Op op = new Op.Delete(new Id(9, 2), List.of(new Op.Range(fixture.collected, 4)));

        assertThat(fixture.replica.isPermanentlyUnanchored(op))
            .as("""
                A delete whose targets GC removed came back BUFFERED, which merge treats as news — \
                so it was written to the log and acknowledged to the client, releasing it from the \
                offline buffer, while it never applied to anything. The tombstone-collection design calls that silently \
                losing the user's work.""")
                    .isTrue();
    }

    @Test
    @DisplayName("a delete is refused when ANY of its ranges is collected, not only the first")
    void deleteWithOneCollectedRangeIsRefused() {
        Fixture fixture = fixture();
        // tryApplyDelete is all-or-nothing: one unreachable range holds the whole operation back,
        // so one collected range is enough to make it permanently unmergeable.
        Op op = new Op.Delete(new Id(9, 3), List.of(
            new Op.Range(fixture.alive, 1),
            new Op.Range(fixture.collected, 4)));

        assertThat(fixture.replica.isPermanentlyUnanchored(op)).isTrue();
    }

    @Test
    @DisplayName("a mark anchored to collected characters is refused, at either end")
    void marksOnCollectedCharactersAreRefused() {
        Fixture fixture = fixture();
        Anchor gone = new Anchor(fixture.collected, Anchor.Side.BEFORE);
        Anchor here = new Anchor(fixture.alive, Anchor.Side.AFTER);

        assertThat(fixture.replica.isPermanentlyUnanchored(
            new Op.AddMark(new Id(9, 4), MarkTypes.COMMENT, "thread", gone, here)))
                .as("a comment anchored to text that GC removed — start collected")
                .isTrue();
        assertThat(fixture.replica.isPermanentlyUnanchored(
            new Op.AddMark(new Id(9, 5), "bold", null, here, gone)))
                .as("EITHER anchor is enough: a mark with one collected end has no range to cover")
                .isTrue();
        assertThat(fixture.replica.isPermanentlyUnanchored(
            new Op.RemoveMark(new Id(9, 6), "bold", gone, here)))
                .as("removing a mark needs its range just as much as adding one does")
                .isTrue();
    }

    // ------------------------------------------------------------------
    // NOT refused — the narrowness the condition depends on
    // ------------------------------------------------------------------

    @Test
    @DisplayName("an operation on characters that are still there is accepted")
    void operationsOnLiveCharactersAreAccepted() {
        Fixture fixture = fixture();

        assertThat(fixture.replica.isPermanentlyUnanchored(
            new Op.Delete(new Id(9, 7), List.of(new Op.Range(fixture.alive, 1)))))
                .isFalse();
        assertThat(fixture.replica.isPermanentlyUnanchored(new Op.AddMark(
            new Id(9, 8), "bold", null,
            new Anchor(fixture.alive, Anchor.Side.BEFORE),
            new Anchor(fixture.alive, Anchor.Side.AFTER))))
                .isFalse();
    }

    @Test
    @DisplayName("an operation that merely outran its cause is accepted — absence alone is not enough")
    void anOperationAheadOfItsCauseIsAccepted() {
        Fixture fixture = fixture();
        // A replica the boundary knows nothing about, at a lamport far above anything collected.
        // This is the ordinary case the pending buffer exists for: the cause is in flight, not
        // gone. Refusing it would reject valid work — the exact failure the narrow condition is
        // written to avoid.
        Id future = new Id(77, 5_000);

        assertThat(fixture.replica.isPermanentlyUnanchored(
            new Op.Delete(new Id(9, 9), List.of(new Op.Range(future, 1)))))
                .as("""
                An operation whose cause has not arrived yet was declared permanently unmergeable, \
                so the client is told to resynchronise instead of the server waiting one \
                broadcast window.""")
                    .isFalse();
        assertThat(fixture.replica.isPermanentlyUnanchored(
            new Op.Insert(new Id(9, 10), future, "x")))
                .isFalse();
    }

    @Test
    @DisplayName("an insertion at the very start of the document is never unanchored")
    void insertAtTheStartIsAccepted() {
        Fixture fixture = fixture();
        // originLeft == null means "the beginning", which is not a character and cannot be
        // collected. Treating null as an absent identifier would refuse every first insertion into
        // a document that has ever been garbage collected.
        assertThat(fixture.replica.isPermanentlyUnanchored(
            new Op.Insert(new Id(9, 11), null, "first")))
                .isFalse();
    }
}
