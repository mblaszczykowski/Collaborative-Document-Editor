package dev.collabedit.crdt;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;

class SnapshotBudgetTest {
    @FunctionalInterface interface Payload { void write(DataOutputStream out) throws IOException; }
    private static byte[] compressed(Payload payload) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(new GZIPOutputStream(bytes))) { payload.write(out); }
        return bytes.toByteArray();
    }
    private static void header(DataOutputStream out, long clock) throws IOException {
        out.writeInt(5); out.writeLong(clock); out.writeInt(0);
    }
    private static void dropped(DataOutputStream out, int replica, int length, boolean deleted)
        throws IOException {
        out.writeInt(replica); out.writeLong(0); out.writeBoolean(false); out.writeBoolean(deleted);
        if (deleted) { out.writeBoolean(false); }
        out.writeBoolean(false); out.writeInt(length);
    }
    private static void block(DataOutputStream out, byte[] text) throws IOException {
        out.writeInt(1); out.writeLong(0); out.writeBoolean(false); out.writeBoolean(false);
        out.writeBoolean(true); out.writeBoolean(true); out.writeInt(text.length); out.write(text);
    }
    @Test void rejectsNegativeOversizedCountsAndUnsafeClocks() throws IOException {
        for (int count : new int[] {-1, SnapshotCodec.MAX_BLOCKS + 1}) {
            assertThat(SnapshotCodec.decode(compressed(out -> {
                header(out, 0); out.writeInt(count); out.writeInt(0); out.writeInt(0);
            }))).isNull();
        }
        for (long clock : new long[] {-1, (1L << 53) + 1}) {
            assertThat(SnapshotCodec.decode(compressed(out -> {
                header(out, clock); out.writeInt(0); out.writeInt(0); out.writeInt(0);
            }))).isNull();
        }
    }
    @Test void droppedIdentityAtTheCharacterBudgetIsValidButLargerOrVisibleOnesAreNot()
        throws IOException {
        var valid = SnapshotCodec.decode(compressed(out -> {
            header(out, 0); out.writeInt(1);
            dropped(out, 1, SnapshotCodec.MAX_CHARACTER_UNITS, true); out.writeInt(0); out.writeInt(0);
        }));
        assertThat(valid).isNotNull();
        assertThat(valid.blocks().getFirst().length()).isEqualTo(SnapshotCodec.MAX_CHARACTER_UNITS);
        assertThat(valid.blocks().getFirst().hasContent()).isFalse();
        for (int length : new int[] {0, -1, SnapshotCodec.MAX_CHARACTER_UNITS + 1}) {
            assertThat(SnapshotCodec.decode(compressed(out -> {
                header(out, 0); out.writeInt(1); dropped(out, 1, length, true);
                out.writeInt(0); out.writeInt(0);
            }))).isNull();
        }
        assertThat(SnapshotCodec.decode(compressed(out -> {
            header(out, 0); out.writeInt(1); dropped(out, 1, 2, false); out.writeInt(0); out.writeInt(0);
        }))).isNull();
    }
    @Test void limitsAggregateContentlessUnits() throws IOException {
        assertThat(SnapshotCodec.decode(compressed(out -> {
            header(out, 0); out.writeInt(2);
            dropped(out, 1, SnapshotCodec.MAX_CHARACTER_UNITS / 2 + 1, true);
            dropped(out, 2, SnapshotCodec.MAX_CHARACTER_UNITS / 2 + 1, true);
            out.writeInt(0); out.writeInt(0);
        }))).isNull();
    }
    @Test void rejectsDuplicateVectorsOverlappingIdentitiesAndTrailingBytes() throws IOException {
        assertThat(SnapshotCodec.decode(compressed(out -> {
            out.writeInt(5); out.writeLong(0); out.writeInt(2);
            out.writeInt(1); out.writeLong(0); out.writeInt(1); out.writeLong(0);
            out.writeInt(0); out.writeInt(0); out.writeInt(0);
        }))).isNull();
        assertThat(SnapshotCodec.decode(compressed(out -> {
            header(out, 1); out.writeInt(2); block(out, new byte[] {'x'}); block(out, new byte[] {'y'});
            out.writeInt(0); out.writeInt(0);
        }))).isNull();
        assertThat(SnapshotCodec.decode(compressed(out -> {
            header(out, 0); out.writeInt(0); out.writeInt(0); out.writeInt(0); out.writeByte(0);
        }))).isNull();
    }
    @Test void rejectsTruncatedTextInvalidUtf8AndNonBooleanBytes() throws IOException {
        assertThat(SnapshotCodec.decode(compressed(out -> {
            header(out, 1); out.writeInt(1); block(out, new byte[] {(byte) 0xff});
            out.writeInt(0); out.writeInt(0);
        }))).isNull();
        assertThat(SnapshotCodec.decode(compressed(out -> {
            header(out, 1); out.writeInt(1); out.writeInt(1); out.writeLong(0); out.writeByte(2);
        }))).isNull();
        assertThat(SnapshotCodec.decode(compressed(out -> {
            header(out, 1); out.writeInt(1); out.writeInt(1); out.writeLong(0);
            out.writeBoolean(false); out.writeBoolean(false); out.writeBoolean(true);
            out.writeBoolean(true); out.writeInt(20);
        }))).isNull();
    }
    @Test void preservesUtf8BomAndRejectsNonMarkOperations() throws IOException {
        var withBom = SnapshotCodec.decode(compressed(out -> {
            header(out, 2); out.writeInt(1); block(out, "\uFEFFx".getBytes(StandardCharsets.UTF_8));
            out.writeInt(0); out.writeInt(0);
        }));
        assertThat(withBom).isNotNull();
        assertThat(withBom.blocks().getFirst().text()).isEqualTo("\uFEFFx");
        assertThat(SnapshotCodec.decode(compressed(out -> {
            header(out, 1); out.writeInt(0); out.writeInt(1);
            byte[] operation = OpCodec.encode(new Op.Insert(new Id(1, 0), null, "x"))
                .getBytes(StandardCharsets.UTF_8);
            out.writeBoolean(true); out.writeInt(operation.length); out.write(operation); out.writeInt(0);
        }))).isNull();
    }
    @Test void rejectsCompressedInputAboveBudgetBeforeOpeningGzip() {
        assertThat(SnapshotCodec.decode(new byte[SnapshotCodec.MAX_COMPRESSED_BYTES + 1])).isNull();
    }
}
