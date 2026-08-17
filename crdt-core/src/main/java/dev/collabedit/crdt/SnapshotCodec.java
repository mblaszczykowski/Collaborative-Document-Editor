package dev.collabedit.crdt;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
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

    private static final int MAX_GZIP_EXPANSION = 1032;

    private static int readLength(DataInputStream in, int compressedBytes, int wireBytesPerElement)
        throws IOException {
        int length = in.readInt();
        long ceiling = (long) compressedBytes * MAX_GZIP_EXPANSION / wireBytesPerElement;
        if (length < 0 || length > ceiling) {
            throw new IOException("implausible length in the snapshot: " + length
                + " from " + compressedBytes + " compressed bytes at "
                + wireBytesPerElement + " bytes per element");
        }
        return length;
    }

    private static int readCharacterCount(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length <= 0) {
            throw new IOException("a block cannot have a non-positive length: " + length);
        }
        return length;
    }

    private static String readText(DataInputStream in, int compressedBytes) throws IOException {
        if (in.readBoolean()) {
            byte[] bytes = new byte[readLength(in, compressedBytes, 1)];
            in.readFully(bytes);
            return new String(bytes, StandardCharsets.UTF_8);
        }
        char[] units = new char[readLength(in, compressedBytes, 2)];
        for (int i = 0; i < units.length; i++) {
            units[i] = in.readChar();
        }
        return new String(units);
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
        try (DataInputStream in = new DataInputStream(
            new GZIPInputStream(new ByteArrayInputStream(data)))) {
            int formatVersion = in.readInt();
            if (formatVersion != DocumentSnapshot.CURRENT_FORMAT_VERSION) {
                return null;
            }
            long clock = in.readLong();

            int versionEntries = readLength(in, data.length, 12);
            Map<Integer, Long> maxSeen = new HashMap<>(versionEntries);
            for (int i = 0; i < versionEntries; i++) {
                maxSeen.put(in.readInt(), in.readLong());
            }

            int blockCount = readLength(in, data.length, 15);
            List<Block> blocks = new ArrayList<>(blockCount);
            for (int i = 0; i < blockCount; i++) {
                Id start = new Id(in.readInt(), in.readLong());
                Id originLeft = in.readBoolean() ? new Id(in.readInt(), in.readLong()) : null;
                boolean deleted = in.readBoolean();
                Id deletedBy = deleted && in.readBoolean()
                    ? new Id(in.readInt(), in.readLong())
: null;
                boolean hasContent = in.readBoolean();
                blocks.add(hasContent
                    ? new Block(start, readText(in, data.length), originLeft, deleted, deletedBy)
: Block.withoutContent(start, readCharacterCount(in), originLeft, deletedBy));
            }
            int markCount = readLength(in, data.length, 5);
            List<Op> marks = new ArrayList<>(markCount);
            for (int i = 0; i < markCount; i++) {
                marks.add(OpCodec.decode(readText(in, data.length)));
            }

            int watermarkEntries = readLength(in, data.length, 12);
            Map<Integer, Long> gcWatermark = new HashMap<>(watermarkEntries);
            for (int i = 0; i < watermarkEntries; i++) {
                gcWatermark.put(in.readInt(), in.readLong());
            }
            return new DocumentSnapshot(
                formatVersion, clock, maxSeen, blocks, marks, gcWatermark);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
