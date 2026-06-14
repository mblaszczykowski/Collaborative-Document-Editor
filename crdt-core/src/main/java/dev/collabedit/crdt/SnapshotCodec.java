package dev.collabedit.crdt;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Encoding a {@link DocumentSnapshot} into binary form.
 *
 * <p>Binary rather than JSON, because the snapshot of a large document with a long history is the
 * heaviest single object in the system, and its size translates directly into how long a document
 * takes to open. JSON would cost several times more here for zero benefit — nobody reads a snapshot
 * with their eyes, unlike the operations in the log, where {@link OpCodec} deliberately uses JSON.
 *
 * <p>A GZIP layer on top: document text compresses very well, and the CPU cost is negligible
 * against the disk and network traffic.
 *
 * <h2>Format (version 3)</h2>
 * <pre>
 *   int    formatVersion
 *   long   clock
 *   int    number of version vector entries
 *     int  replicaId, long maxLamport
 *   int    number of blocks
 *     int  replicaId, long startLamport
 *     bool has origin, [int replicaId, long lamport]
 *     bool deleted
 *     bool has content
 *       if yes: int length in UTF-8 bytes, the bytes
 *       if no:  int length in characters          &lt;- CP18
 * </pre>
 *
 * <p>Version 3 adds one bit per block. A block whose content GC dropped (CP18) carries only its
 * length — and the length is what every index in the document needs. A block with content is
 * written exactly as in version 2, so a typical snapshot grows by one byte per block and nothing
 * else.
 *
 * <p><b>Migration is free, and that is not an accident.</b> A snapshot in an incompatible version
 * is IGNORED rather than migrated ({@link #decode} returns {@code null}), because a snapshot is a
 * disposable cache — the state rebuilds from the operation log, more slowly and correctly. The
 * ADR-0003 decision pays for itself a second time here: raising the format version requires not a
 * single line of migration code.
 *
 * <p>The blocks are written <b>in document order</b>, so rebuilding does not require running the
 * integration algorithm again — they can simply be read in sequence. That is the entire time saving
 * snapshots exist for.
 */
public final class SnapshotCodec {

    private SnapshotCodec() {
    }

    /**
     * Writing a block's text as a length + UTF-8 bytes.
     *
     * <p>NOT {@code writeUTF}: that method encodes the length in two bytes and fails on text longer
     * than 64 KB with "encoded string too long". A run-length block formed by merging continuous
     * typing comfortably exceeds that size — found on the first run of the load harness against a
     * 100 000-character document, where snapshots stopped being written and the only symptom was a
     * log entry.
     *
     * <p>The bug was invisible to every earlier test, because none of them created a single block
     * larger than a few dozen characters.
     */
    private static void writeLongString(DataOutputStream out, String text) throws IOException {
        byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String readLongString(DataInputStream in) throws IOException {
        byte[] bytes = new byte[in.readInt()];
        in.readFully(bytes);
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    public static byte[] encode(DocumentSnapshot snapshot) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(bytes))) {
            out.writeInt(snapshot.formatVersion());
            out.writeLong(snapshot.clock());

            out.writeInt(snapshot.maxSeen().size());
            for (Map.Entry<Integer, Long> entry : snapshot.maxSeen().entrySet()) {
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
                out.writeBoolean(block.hasContent());
                if (block.hasContent()) {
                    writeLongString(out, block.text());
                } else {
                    out.writeInt(block.length());
                }
            }

            // Marks (CP13) are stored AS OPERATIONS, in the OpCodec wire format, rather than as
            // resolved formatting segments. Segments are a result, not state: storing them would
            // mean a replica restored from a snapshot could no longer merge a mark arriving later
            // correctly — because it would have nothing to resolve the conflict against.
            out.writeInt(snapshot.marks().size());
            for (Op mark : snapshot.marks()) {
                writeLongString(out, OpCodec.encode(mark));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot encode the snapshot", e);
        }
        return bytes.toByteArray();
    }

    /**
     * @return the decoded snapshot, or {@code null} when the data cannot be read. Returning
     *         {@code null} rather than throwing is deliberate: a snapshot is a cache, and an
     *         unreadable cache should lead to a rebuild from the log, not to a failure.
     */
    public static DocumentSnapshot decode(byte[] data) {
        try (DataInputStream in = new DataInputStream(
            new GZIPInputStream(new ByteArrayInputStream(data)))) {

            int formatVersion = in.readInt();
            if (formatVersion != DocumentSnapshot.CURRENT_FORMAT_VERSION) {
                return null;
            }
            long clock = in.readLong();

            int versionEntries = in.readInt();
            Map<Integer, Long> maxSeen = new HashMap<>(versionEntries);
            for (int i = 0; i < versionEntries; i++) {
                maxSeen.put(in.readInt(), in.readLong());
            }

            int blockCount = in.readInt();
            List<Block> blocks = new ArrayList<>(blockCount);
            for (int i = 0; i < blockCount; i++) {
                Id start = new Id(in.readInt(), in.readLong());
                Id originLeft = in.readBoolean() ? new Id(in.readInt(), in.readLong()) : null;
                boolean deleted = in.readBoolean();
                boolean hasContent = in.readBoolean();
                blocks.add(hasContent
                    ? new Block(start, readLongString(in), originLeft, deleted)
                    : Block.withoutContent(start, in.readInt(), originLeft));
            }
            int markCount = in.readInt();
            List<Op> marks = new ArrayList<>(markCount);
            for (int i = 0; i < markCount; i++) {
                marks.add(OpCodec.decode(readLongString(in)));
            }
            return new DocumentSnapshot(formatVersion, clock, maxSeen, blocks, marks);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
