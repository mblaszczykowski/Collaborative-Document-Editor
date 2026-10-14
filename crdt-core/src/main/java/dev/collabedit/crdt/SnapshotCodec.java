package dev.collabedit.crdt;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.nio.ByteBuffer;
import java.io.FilterInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public final class SnapshotCodec {
    private SnapshotCodec() {
    }

    private static void writeText(DataOutputStream out, String text) throws IOException {
        if (isWellFormed(text)) {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            out.writeBoolean(true);
            out.writeInt(bytes.length);
            out.write(bytes);
            return;
        }
        out.writeBoolean(false);
        out.writeInt(text.length());
        for (int i = 0; i < text.length(); i++) {
            out.writeChar(text.charAt(i));
        }
    }

    public static final int MAX_COMPRESSED_BYTES = 16 * 1024 * 1024;
    public static final int MAX_DECOMPRESSED_BYTES = 64 * 1024 * 1024;
    public static final int MAX_TEXT_BYTES = 16 * 1024 * 1024;
    public static final int MAX_CHARACTER_UNITS = 16 * 1024 * 1024;
    public static final int MAX_BLOCKS = 200_000;
    public static final int MAX_MARKS = 100_000;
    public static final int MAX_VECTOR_ENTRIES = 50_000;
    private static final int MAX_GZIP_EXPANSION = 1032;

    private static final class BoundedInput extends FilterInputStream {
        private long read;
        BoundedInput(InputStream source) { super(source); }
        private void consumed(int count) throws IOException {
            if (count > 0 && (read += count) > MAX_DECOMPRESSED_BYTES) {
                throw new IOException("decompressed snapshot exceeds budget");
            }
        }
        @Override public int read() throws IOException {
            int value = in.read();
            consumed(value < 0 ? 0 : 1);
            return value;
        }
        @Override public int read(byte[] bytes, int from, int count) throws IOException {
            int actual = in.read(bytes, from,
                (int) Math.min(count, MAX_DECOMPRESSED_BYTES - read + 1));
            consumed(actual);
            return actual;
        }
    }

    private static final class CharacterBudget {
        private int remaining = MAX_CHARACTER_UNITS;
        void consume(int count) throws IOException {
            if (count < 0 || count > remaining) {
                throw new IOException("snapshot character budget exceeded");
            }
            remaining -= count;
        }
    }

    private static boolean readBoolean(DataInputStream in) throws IOException {
        int value = in.readUnsignedByte();
        if (value > 1) { throw new IOException("invalid snapshot boolean"); }
        return value == 1;
    }

    private static long readCounter(DataInputStream in, long maximum) throws IOException {
        long value = in.readLong();
        if (value < 0 || value > maximum) { throw new IOException("invalid snapshot counter"); }
        return value;
    }

    private static int readLength(DataInputStream in, int compressedBytes, int wireBytesPerElement,
                                  int maximum) throws IOException {
        int length = in.readInt();
        long ceiling = Math.min(maximum,
            (long) compressedBytes * MAX_GZIP_EXPANSION / wireBytesPerElement);
        if (length < 0 || length > ceiling) {
            throw new IOException("snapshot count exceeds budget: " + length);
        }
        return length;
    }

    private static String readText(DataInputStream in, int compressedBytes,
                                   CharacterBudget budget) throws IOException {
        if (readBoolean(in)) {
            byte[] bytes = new byte[readLength(in, compressedBytes, 1, MAX_TEXT_BYTES)];
            in.readFully(bytes);
            String text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
            budget.consume(text.length());
            return text;
        }
        int length = readLength(in, compressedBytes, 2, MAX_TEXT_BYTES / 2);
        budget.consume(length);
        char[] units = new char[length];
        for (int i = 0; i < units.length; i++) { units[i] = in.readChar(); }
        return new String(units);
    }

    private static Map<Integer, Long> readVector(DataInputStream in, int compressedBytes)
        throws IOException {
        int entries = readLength(in, compressedBytes, 12, MAX_VECTOR_ENTRIES);
        Map<Integer, Long> result = new HashMap<>(entries);
        for (int i = 0; i < entries; i++) {
            int replica = in.readInt();
            long value = readCounter(in, OpCodec.MAX_LAMPORT);
            if (result.putIfAbsent(replica, value) != null) {
                throw new IOException("duplicate snapshot vector identity");
            }
        }
        return result;
    }

    private static boolean isWellFormed(String text) {
        for (int i = 0; i < text.length(); i++) {
            char unit = text.charAt(i);
            if (Character.isHighSurrogate(unit)) {
                if (i + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(i + 1))) {
                    return false;
                }
                i++;
            } else if (Character.isLowSurrogate(unit)) {
                return false;
            }
        }
        return true;
    }

    public static byte[] encode(DocumentSnapshot snapshot) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(bytes))) {
            out.writeInt(snapshot.formatVersion());
            out.writeLong(snapshot.clock());

            out.writeInt(snapshot.maxSeen().size());
            for (Map.Entry<Integer, Long> entry : new TreeMap<>(snapshot.maxSeen()).entrySet()) {
                out.writeInt(entry.getKey());
                out.writeLong(entry.getValue());
            }

            out.writeInt(snapshot.blocks().size());
            for (Block block : snapshot.blocks()) {
                out.writeInt(block.start().replicaId());
                out.writeLong(block.start().lamport());
                boolean hasOrigin = block.originLeft() != null;
                out.writeBoolean(hasOrigin);
                if (hasOrigin) {
                    out.writeInt(block.originLeft().replicaId());
                    out.writeLong(block.originLeft().lamport());
                }
                out.writeBoolean(block.deleted());
                if (block.deleted()) {
                    Id deletedBy = block.deletedBy();
                    out.writeBoolean(deletedBy != null);
                    if (deletedBy != null) {
                        out.writeInt(deletedBy.replicaId());
                        out.writeLong(deletedBy.lamport());
                    }
                }
                out.writeBoolean(block.hasContent());
                if (block.hasContent()) {
                    writeText(out, block.text());
                } else {
                    out.writeInt(block.length());
                }
            }

            out.writeInt(snapshot.marks().size());
            for (Op mark : snapshot.marks()) {
                writeText(out, OpCodec.encode(mark));
            }

            out.writeInt(snapshot.gcWatermark().size());
            for (Map.Entry<Integer, Long> entry
: new TreeMap<>(snapshot.gcWatermark()).entrySet()) {
                out.writeInt(entry.getKey());
                out.writeLong(entry.getValue());
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot encode the snapshot", e);
        }
        return bytes.toByteArray();
    }

    public static DocumentSnapshot decode(byte[] data) {
        if (data == null || data.length > MAX_COMPRESSED_BYTES) { return null; }
        try (DataInputStream in = new DataInputStream(new BoundedInput(
            new GZIPInputStream(new ByteArrayInputStream(data))))) {
            int formatVersion = in.readInt();
            if (formatVersion != DocumentSnapshot.CURRENT_FORMAT_VERSION) { return null; }
            long clock = readCounter(in, OpCodec.MAX_LAMPORT + 1);
            CharacterBudget budget = new CharacterBudget();
            Map<Integer, Long> maxSeen = readVector(in, data.length);
            int blockCount = readLength(in, data.length, 20, MAX_BLOCKS);
            List<Block> blocks = new ArrayList<>(blockCount);
            Map<Integer, java.util.NavigableMap<Long, Long>> intervals = new HashMap<>();
            for (int i = 0; i < blockCount; i++) {
                Id start = new Id(in.readInt(), readCounter(in, OpCodec.MAX_LAMPORT));
                Id originLeft = readBoolean(in)
                    ? new Id(in.readInt(), readCounter(in, OpCodec.MAX_LAMPORT)) : null;
                boolean deleted = readBoolean(in);
                Id deletedBy = deleted && readBoolean(in)
                    ? new Id(in.readInt(), readCounter(in, OpCodec.MAX_LAMPORT)) : null;
                boolean hasContent = readBoolean(in);
                if (!hasContent && !deleted) { throw new IOException("visible block has no content"); }
                Block block;
                if (hasContent) {
                    block = new Block(start, readText(in, data.length, budget), originLeft, deleted, deletedBy);
                } else {
                    int length = in.readInt();
                    if (length <= 0) { throw new IOException("invalid snapshot block length"); }
                    budget.consume(length);
                    block = Block.withoutContent(start, length, originLeft, deletedBy);
                }
                long end = start.lamport() + block.length();
                if (end - 1 > OpCodec.MAX_LAMPORT) { throw new IOException("invalid snapshot block range"); }
                var ranges = intervals.computeIfAbsent(start.replicaId(), ignored -> new TreeMap<>());
                var before = ranges.floorEntry(start.lamport());
                var after = ranges.ceilingEntry(start.lamport());
                if ((before != null && before.getValue() > start.lamport())
                    || (after != null && after.getKey() < end)) {
                    throw new IOException("overlapping snapshot identities");
                }
                ranges.put(start.lamport(), end);
                blocks.add(block);
            }
            int markCount = readLength(in, data.length, 6, MAX_MARKS);
            List<Op> marks = new ArrayList<>(markCount);
            java.util.Set<Id> markIds = new java.util.HashSet<>();
            for (int i = 0; i < markCount; i++) {
                Op mark = OpCodec.decode(readText(in, data.length, budget));
                if (!(mark instanceof Op.AddMark || mark instanceof Op.RemoveMark)
                    || !markIds.add(mark.id())) { throw new IOException("invalid snapshot mark"); }
                marks.add(mark);
            }
            Map<Integer, Long> gcWatermark = readVector(in, data.length);
            if (in.read() != -1) { throw new IOException("trailing snapshot bytes"); }
            return new DocumentSnapshot(formatVersion, clock, maxSeen, blocks, marks, gcWatermark);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
