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

@DisplayName("Missing legacy identities below a persisted GC boundary are refused")
class UnanchoredOperationTest {
    private record Fixture(DocumentReplica replica, Id collected, Id alive) {
    }

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

        document.apply(document.delete(indexOf(document, collected), 4));
        int reclaimed = document.gc(VersionVector.of(Map.of(
            1, document.clock(), 2, byDeparted.id().lamport() + 8, 3, byPresent.id().lamport() + 8)));
        assertThat(reclaimed)
            .as("the fixture's premise: GC has to have actually collected something")
            .isGreaterThan(0);
        assertThat(document.contains(collected))
            .as("current GC releases payload while preserving identity")
            .isTrue();
        assertThat(document.tombstoneContentCount()).isZero();

        // Simulate a legacy snapshot that removed identity altogether. Current GC no longer
        // creates it, but stale-anchor validation must still distinguish it from a future cause.
        var saved = document.snapshot();
        document = RgaDocument.restore(1, new TreapBlockList(),
            new dev.collabedit.crdt.DocumentSnapshot(saved.formatVersion(), saved.clock(),
                saved.maxSeen(), saved.blocks().stream().filter(block -> !block.deleted()).toList(),
                saved.marks(), saved.gcWatermark()));
        assertThat(document.contains(collected)).isFalse();

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

    @Test
    @DisplayName("an insertion after a collected character is refused - the case that already worked")
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
                A delete whose targets GC removed came back BUFFERED, which merge treats as news - \
                so it was written to the log and acknowledged to the client, releasing it from the \
                offline buffer, while it never applied to anything. The tombstone-collection design calls that silently \
                losing the user's work.""")
                    .isTrue();
    }

    @Test
    @DisplayName("a delete is refused when ANY of its ranges is collected, not only the first")
    void deleteWithOneCollectedRangeIsRefused() {
        Fixture fixture = fixture();
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
                .as("a comment anchored to text that GC removed - start collected")
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
    @DisplayName("an operation that merely outran its cause is accepted - absence alone is not enough")
    void anOperationAheadOfItsCauseIsAccepted() {
        Fixture fixture = fixture();
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
        assertThat(fixture.replica.isPermanentlyUnanchored(
            new Op.Insert(new Id(9, 11), null, "first")))
                .isFalse();
    }
}
