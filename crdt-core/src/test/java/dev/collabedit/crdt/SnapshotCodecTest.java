package dev.collabedit.crdt;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Snapshot serialisation.
 *
 * <p>A snapshot is a cache, so corrupting one should not lose data — but <b>being unable to write
 * one</b> is a real problem: without snapshots every document open requires replaying the entire
 * operation log.
 */
class SnapshotCodecTest {

    @Test
    @DisplayName("a round trip preserves the structure, the clock and the version vector")
    void roundTrip() {
        RgaDocument document = new RgaDocument(7);
        document.insert(0, "Inkline");
        document.insert(7, " works");
        document.delete(0, 3);

        DocumentSnapshot decoded = SnapshotCodec.decode(SnapshotCodec.encode(document.snapshot()));

        assertThat(decoded).isNotNull();
        RgaDocument restored = RgaDocument.restore(7, new TreapBlockList(), decoded);
        assertThat(restored.text()).isEqualTo(document.text());
        assertThat(restored.clock()).isEqualTo(document.clock());
        assertThat(restored.version()).isEqualTo(document.version());
        // Tombstones MUST survive: they may be the anchor point of an operation that has not
        // arrived yet. A snapshot without them would corrupt the replica once restored.
        assertThat(restored.tombstoneCount()).isEqualTo(document.tombstoneCount());
    }

    @Test
    @DisplayName("REGRESSION: a block larger than 64 KB can be written")
    void handlesBlocksLargerThan64Kilobytes() {
        // DataOutputStream.writeUTF encodes the length in two bytes and throws "encoded string
        // too long" above 64 KB. A run-length block formed by merging continuous typing comfortably
        // exceeds that size.
        //
        // The bug was invisible to every correctness test, because none of them created such a
        // large block. It surfaced only in the load harness on a 100 000-character document — and
        // not as a crash but as a log entry, while snapshot writing silently stopped.
        RgaDocument document = new RgaDocument(1);
        document.insert(0, "x".repeat(100_000));

        assertThat(document.blockCount())
            .as("the test does not create one large block, so it is not checking what it was meant to")
            .isEqualTo(1);

        DocumentSnapshot decoded = SnapshotCodec.decode(SnapshotCodec.encode(document.snapshot()));

        assertThat(decoded).isNotNull();
        assertThat(RgaDocument.restore(1, new TreapBlockList(), decoded).text())
            .hasSize(100_000);
    }

    @Test
    @DisplayName("non-ASCII characters survive encoding")
    void handlesNonAsciiText() {
        RgaDocument document = new RgaDocument(1);
        // Deliberately not translated: this string is test DATA, not prose. Polish diacritics,
        // Japanese and an emoji cover two-, three- and four-byte UTF-8 sequences respectively,
        // which is the point — an ASCII-only payload would not exercise the encoder at all.
        document.insert(0, "zażółć gęślą jaźń — 日本語 — 🙂");

        DocumentSnapshot decoded = SnapshotCodec.decode(SnapshotCodec.encode(document.snapshot()));

        assertThat(RgaDocument.restore(1, new TreapBlockList(), decoded).text())
            .isEqualTo(document.text());
    }

    @Test
    @DisplayName("a block split between the halves of an emoji survives the round trip")
    void handlesABlockSplitInsideASurrogatePair() {
        RgaDocument document = new RgaDocument(1);
        document.insert(0, "x😀y");
        // Identity is per UTF-16 code unit, so the emoji is TWO characters and a position between
        // them is a legal insertion point. The split leaves one block ending in a high surrogate
        // and the next beginning with a low one.
        document.insert(2, "Z");
        assertThat(document.text()).isEqualTo("x\uD83DZ\uDE00y");

        DocumentSnapshot decoded = SnapshotCodec.decode(SnapshotCodec.encode(document.snapshot()));
        String restored = RgaDocument.restore(1, new TreapBlockList(), decoded).text();

        // Before version 4 this produced "x?Z?y" : String.getBytes(UTF_8) replaces a lone surrogate
        // with '?', and the length survives the substitution, so nothing threw and no index broke.
        // The snapshot simply stopped agreeing with the operation log — and then got re-snapshotted.
        assertThat(restored)
            .as("the snapshot lost half of an astral character; a client opening from the snapshot "
                + "and one replaying the log now hold different text")
            .isEqualTo(document.text());
    }

    @Test
    @DisplayName("encoding the same snapshot twice produces the same bytes")
    void theEncoderIsReproducible() {
        RgaDocument document = new RgaDocument(1);
        Op base = document.insert(0, "shared");
        for (int replica = 2; replica <= 9; replica++) {
            RgaDocument other = new RgaDocument(replica);
            other.apply(base);
            // An insertion at position 0 has no origin, so it always applies — enough to put an
            // entry for this replica into the version vector, which is all this test needs.
            document.apply(other.insert(0, String.valueOf(replica)));
        }

        // The version vector used to be written in Map.copyOf iteration order, which
        // ImmutableCollections salts per JVM — so this held within one process and failed between
        // two. Encoding twice here cannot catch that; SnapshotFixtureTest comparing against
        // COMMITTED bytes is what does, and this states the property the fixture relies on.
        assertThat(SnapshotCodec.encode(document.snapshot()))
            .isEqualTo(SnapshotCodec.encode(document.snapshot()));
        assertThat(new java.util.TreeSet<>(document.snapshot().maxSeen().keySet()))
            .hasSizeGreaterThan(1);
    }

    @Test
    @DisplayName("the GC boundary survives the snapshot round trip")
    void gcWatermarkSurvivesTheRoundTrip() {
        RgaDocument document = new RgaDocument(1, new TreapBlockList());
        document.insert(0, "abcdef");
        document.delete(5, 1);
        document.gc(document.version());

        assertThat(document.gcWatermark().isEmpty())
            .as("GC did not run, so this test is not checking what it was written for")
            .isFalse();

        DocumentSnapshot decoded = SnapshotCodec.decode(SnapshotCodec.encode(document.snapshot()));
        RgaDocument restored = RgaDocument.restore(9, new TreapBlockList(), decoded);

        // the tombstone-collection design: "the snapshot must carry stableVersion, because the instance loading it needs
        // to know what is already gone". Without it, a replica rebuilt from a snapshot cannot tell
        // an operation that outran its cause from one whose cause it collected — and answers
        // "wait" to both, which for the second means for ever.
        assertThat(restored.gcWatermark())
            .as("the boundary below which tombstones are already gone did not survive the "
                + "snapshot; RESYNC_REQUIRED cannot fire on a reloaded replica")
            .isEqualTo(document.gcWatermark());
    }

    @Test
    @DisplayName("a snapshot in an unsupported format is ignored rather than blowing up the read")
    void unknownFormatIsIgnored() {
        // the operation-log decision: on a format version mismatch the snapshot is to be SKIPPED and the state
        // rebuilt from the log. Throwing would make a document impossible to open after every
        // change to the serialisation format.
        byte[] garbage = {0, 1, 2, 3, 4, 5};
        assertThat(SnapshotCodec.decode(garbage)).isNull();
    }

    /**
     * A large deleted paste in a document with little live text.
     *
     * <p>The dropped-content length is a CHARACTER COUNT that no bytes on the wire back — the whole
     * point of CP18 is that the characters are gone. Putting it through the gzip-expansion ceiling
     * added to bound allocations made the decoder reject snapshots its own encoder had just
     * produced: at 120 000 dropped characters the snapshot compresses to about a hundred bytes,
     * and the ceiling lands far below the count.
     *
     * <p>The failure is invisible rather than loud. {@code SnapshotStore} reads {@code null} as a
     * cache miss, replays the whole operation log — correct, and the exact cost snapshots exist to
     * remove — and then writes the same unreadable snapshot again, for ever. The TypeScript client
     * decoded those same bytes correctly, so the server could not read a snapshot its own client
     * could.
     */
    @Test
    @DisplayName("a snapshot whose dropped content dwarfs its compressed size still decodes")
    void aLargeDroppedRunDoesNotTripTheAllocationBound() {
        RgaDocument document = new RgaDocument(1, new TreapBlockList());
        document.insert(0, "x".repeat(120_000));
        document.insert(120_000, "still here");
        document.delete(0, 120_000);
        // The tombstone has to survive as an origin so that GC drops its CONTENT rather than
        // removing the block — that is the branch whose length is written as a bare count.
        int reclaimed = document.gc(VersionVector.of(Map.of(1, 1_000_000L)));
        assertThat(reclaimed)
            .as("the scenario needs GC to have dropped the content of a large block")
            .isGreaterThan(100_000);

        byte[] encoded = SnapshotCodec.encode(document.snapshot());
        assertThat(encoded.length)
            .as("the scenario needs the compressed snapshot to be far smaller than the character "
                + "count it describes, or the ceiling is never approached")
            .isLessThan(120_000 / 1032);

        DocumentSnapshot decoded = SnapshotCodec.decode(encoded);
        assertThat(decoded)
            .as("the decoder refused a snapshot this encoder produced — every open of this "
                + "document now replays the whole log and rewrites the same unreadable cache")
            .isNotNull();
        RgaDocument restored =
            RgaDocument.restore(1, new TreapBlockList(), java.util.Objects.requireNonNull(decoded));
        assertThat(restored.text()).isEqualTo("still here");
        assertThat(restored.visibleLength()).isEqualTo("still here".length());
    }
}
