package dev.collabedit.crdt;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SnapshotCodecTest {
    @Test
    @DisplayName("a round trip preserves the structure, the clock and the version vector")
    void roundTrip() {
        RgaDocument document = new RgaDocument(7);
        document.insert(0, "Collabedit");
        document.insert(7, " works");
        document.delete(0, 3);

        DocumentSnapshot decoded = SnapshotCodec.decode(SnapshotCodec.encode(document.snapshot()));

        assertThat(decoded).isNotNull();
        RgaDocument restored = RgaDocument.restore(7, new TreapBlockList(), decoded);
        assertThat(restored.text()).isEqualTo(document.text());
        assertThat(restored.clock()).isEqualTo(document.clock());
        assertThat(restored.version()).isEqualTo(document.version());
        assertThat(restored.tombstoneCount()).isEqualTo(document.tombstoneCount());
    }

    @Test
    @DisplayName("REGRESSION: a block larger than 64 KB can be written")
    void handlesBlocksLargerThan64Kilobytes() {
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
        document.insert(0, "zażółć gęślą jaźń - 日本語 - 🙂");

        DocumentSnapshot decoded = SnapshotCodec.decode(SnapshotCodec.encode(document.snapshot()));

        assertThat(RgaDocument.restore(1, new TreapBlockList(), decoded).text())
            .isEqualTo(document.text());
    }

    @Test
    @DisplayName("a block split between the halves of an emoji survives the round trip")
    void handlesABlockSplitInsideASurrogatePair() {
        RgaDocument document = new RgaDocument(1);
        document.insert(0, "x😀y");
        document.insert(2, "Z");
        assertThat(document.text()).isEqualTo("x\uD83DZ\uDE00y");

        DocumentSnapshot decoded = SnapshotCodec.decode(SnapshotCodec.encode(document.snapshot()));
        String restored = RgaDocument.restore(1, new TreapBlockList(), decoded).text();

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
            document.apply(other.insert(0, String.valueOf(replica)));
        }

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

        assertThat(restored.gcWatermark())
            .as("the boundary below which tombstones are already gone did not survive the "
                + "snapshot; RESYNC_REQUIRED cannot fire on a reloaded replica")
            .isEqualTo(document.gcWatermark());
    }

    @Test
    @DisplayName("a snapshot in an unsupported format is ignored rather than blowing up the read")
    void unknownFormatIsIgnored() {
        byte[] garbage = {0, 1, 2, 3, 4, 5};
        assertThat(SnapshotCodec.decode(garbage)).isNull();
    }

    @Test
    @DisplayName("a snapshot whose dropped content dwarfs its compressed size still decodes")
    void aLargeDroppedRunDoesNotTripTheAllocationBound() {
        RgaDocument document = new RgaDocument(1, new TreapBlockList());
        document.insert(0, "x".repeat(120_000));
        document.insert(120_000, "still here");
        document.delete(0, 120_000);
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
            .as("the decoder refused a snapshot this encoder produced - every open of this "
                + "document now replays the whole log and rewrites the same unreadable cache")
            .isNotNull();
        RgaDocument restored =
            RgaDocument.restore(1, new TreapBlockList(), java.util.Objects.requireNonNull(decoded));
        assertThat(restored.text()).isEqualTo("still here");
        assertThat(restored.visibleLength()).isEqualTo("still here".length());
    }

    @Test
    @DisplayName("a corrupted mid-stream element count is refused rather than allocated")
    void aCorruptedElementCountIsRefused() throws IOException {
        RgaDocument document = new RgaDocument(1, new TreapBlockList());
        document.insert(0, "abcdef");
        byte[] encoded = SnapshotCodec.encode(document.snapshot());
        assertThat(SnapshotCodec.decode(encoded))
            .as("the unpatched snapshot must decode, or this test proves nothing about the patch")
            .isNotNull();

        byte[] plain = gunzip(encoded);
        int blockCountAt = 16 + readInt(plain, 12) * 12;
        assertThat(readInt(plain, blockCountAt))
            .as("the offset arithmetic is wrong, so the patch below is corrupting something else")
            .isEqualTo(document.blockCount());

        int implausible = (int) ((long) encoded.length * 1032 / 2);
        writeInt(plain, blockCountAt, implausible);
        byte[] corrupted = gzip(plain);

        assertThat((long) implausible)
            .as("the corrupted count is outside the ceiling this test replaces, so refusing it "
                + "would prove nothing")
            .isLessThanOrEqualTo((long) corrupted.length * 1032);
        assertThat(SnapshotCodec.decode(corrupted))
            .as("a block count no arrangement of these bytes could describe was accepted, and the "
                + "list it sizes is allocated before a single block is read")
            .isNull();
    }

    private static byte[] gunzip(byte[] data) throws IOException {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(data))) {
            return in.readAllBytes();
        }
    }

    private static byte[] gzip(byte[] data) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(bytes)) {
            out.write(data);
        }
        return bytes.toByteArray();
    }

    private static int readInt(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 24) | ((data[offset + 1] & 0xFF) << 16)
            | ((data[offset + 2] & 0xFF) << 8) | (data[offset + 3] & 0xFF);
    }

    private static void writeInt(byte[] data, int offset, int value) {
        data[offset] = (byte) (value >>> 24);
        data[offset + 1] = (byte) (value >>> 16);
        data[offset + 2] = (byte) (value >>> 8);
        data[offset + 3] = (byte) value;
    }
}
