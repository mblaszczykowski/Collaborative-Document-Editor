package dev.collabedit.crdt;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Snapshot serialisation (ADR-0003).
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
    @DisplayName("a snapshot in an unsupported format is ignored rather than blowing up the read")
    void unknownFormatIsIgnored() {
        // ADR-0003: on a format version mismatch the snapshot is to be SKIPPED and the state
        // rebuilt from the log. Throwing would make a document impossible to open after every
        // change to the serialisation format.
        byte[] garbage = {0, 1, 2, 3, 4, 5};
        assertThat(SnapshotCodec.decode(garbage)).isNull();
    }
}
