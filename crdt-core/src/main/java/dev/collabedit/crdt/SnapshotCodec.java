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
 * <h2>Format (version 5)</h2>
 * <pre>
 *   int    formatVersion
 *   long   clock
 * int number of version vector entries, ASCENDING BY replicaId
 *     int  replicaId, long maxLamport
 *   int    number of blocks
 *     int  replicaId, long startLamport
 *     bool has origin, [int replicaId, long lamport]
 *     bool deleted
 * if deleted: bool has deleter, [int replicaId, long lamport] &lt;- version 5
 *     bool has content
 * if yes: TEXT &lt;- see below
 *       if no:  int length in characters          &lt;- CP18
 * int number of marks
 * TEXT (the OpCodec JSON of the mark)
 * int number of GC-watermark entries, ASCENDING BY replicaId &lt;- the tombstone-collection design
 * int replicaId, long lamport
 *
 * TEXT = bool wellFormed
 * if true: int length in UTF-8 bytes, the bytes
 * if false: int length in UTF-16 code units, two bytes each (big-endian)
 * </pre>
 *
 * <p>Version 3 added one bit per block. A block whose content GC dropped (CP18) carries only its
 * length — and the length is what every index in the document needs. A block with content is
 * written exactly as in version 2, so a typical snapshot grows by one byte per block and nothing
 * else.
 *
 * <h2>Version 4: why a string needs an escape hatch</h2>
 * <p>Identity in this CRDT is per <b>UTF-16 code unit</b>, so an emoji is two characters with two
 * identifiers — and a block boundary can fall between them as soon as anybody inserts at that
 * position. The block then holds a lone surrogate, which is not a valid character, and
 * {@code String.getBytes(UTF_8)} silently replaces it with {@code '?'}.
 *
 * <p>The length survived that substitution — one code unit in, one byte out, one code unit back —
 * so no index broke and nothing threw. {@code "x😀y"} with something wedged into the middle of the
 * emoji came back from a snapshot as {@code "x?Z?y"}, while the operation log rebuilt it correctly.
 * <b>A snapshot disagreeing with the log is the one thing the operation-log decision says cannot happen</b>: the
 * snapshot is a cache, and a cache that answers differently from its source is worse than no cache
 * at all. Worse still, the corrupted text was then re-snapshotted, so the damage was permanent.
 *
 * <p>The fix is the flag above rather than switching everything to UTF-16. Well-formed text — every
 * block in every document nobody has split mid-emoji, which is very nearly all of them — is written
 * as exactly the same UTF-8 bytes as before, so the size argument this class opens with still
 * holds. The two-bytes-per-unit form costs one byte per string to be available, and is exact.
 *
 * <p>Version 4 also fixes the version-vector entries in ASCENDING replica order. They used to be
 * written in {@code Map.copyOf} iteration order, which {@code java.util.ImmutableCollections}
 * randomises per JVM run through a salt — so encoding one snapshot twice in two processes produced
 * different bytes. Nothing depended on that, which is exactly why it was worth fixing before
 * something did: {@code SnapshotFixtureTest} now pins the encoder's output byte for byte.
 *
 * <h2>Version 5: the tombstone has to say who deleted it</h2>
 * <p>The tombstone-collection design lets a tombstone be removed once <b>its deletion operation</b> is causally stable.
 * {@code RgaDocument.gc} tested the insertion instead — see {@link Block#deletedBy} for what that
 * cost — and fixing it means the deleting operation's identifier is now part of a block's state,
 * so it has to survive a snapshot. A visible block writes nothing extra; a tombstone writes one
 * flag and, when the deleter is known, thirteen bytes. Every tombstone in a document written by
 * this version has a known deleter, so in practice it is thirteen bytes per tombstone and gzip
 * compresses the repetition well, since a run of blocks deleted together shares one identifier.
 *
 * <p>The flag is not decorative: a block restored WITHOUT a deleter is one GC must never collect,
 * and the format has to be able to say so rather than inventing an identifier that would make it
 * collectable.
 *
 * <p><b>Migration is free, and that is not an accident.</b> A snapshot in an incompatible version
 * is IGNORED rather than migrated ({@link #decode} returns {@code null}), because a snapshot is a
 * disposable cache — the state rebuilds from the operation log, more slowly and correctly. The
 * operation-log decision pays for itself a second time here: raising the format version requires not a
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
     * Writing a string as a length + bytes, losslessly for any sequence of UTF-16 code units.
     *
     * <p>NOT {@code writeUTF}: that method encodes the length in two bytes and fails on text longer
     * than 64 KB with "encoded string too long". A run-length block formed by merging continuous
     * typing comfortably exceeds that size — found on the first run of the load harness against a
     * 100 000-character document, where snapshots stopped being written and the only symptom was a
     * log entry.
     *
     * <p>The bug was invisible to every earlier test, because none of them created a single block
     * larger than a few dozen characters.
     *
     * <p>The {@code wellFormed} flag is the version 4 fix; the class javadoc explains why a block
     * can hold half an emoji and what UTF-8 did with it. The common branch is byte-for-byte what
     * version 3 wrote, after the flag.
     */
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

    /**
     * DEFLATE's best possible ratio, and therefore the most bytes a compressed snapshot can
     * legitimately expand into. Used only to bound allocations while decoding — see
     * {@link #readLength}.
     */
    private static final int MAX_GZIP_EXPANSION = 1032;

    /**
     * Reads a length prefix, refusing one the compressed input cannot possibly justify.
     *
     * <h2>Why a bound rather than trusting the number</h2>
     * Every length in this format drove an allocation directly — {@code new byte[in.readInt]},
     * {@code new char[in.readInt]}, {@code new ArrayList&lt;&gt;(blockCount)}. A negative value
     * produced a {@code NegativeArraySizeException}, which {@link #decode}'s catch turns into the
     * intended {@code null}; a huge one produced an {@link OutOfMemoryError}, which is an
     * {@link Error} and escapes — breaking this class's stated promise that "an unreadable cache
     * should lead to a rebuild from the log, not to a failure", and taking the process with it.
     *
     * <p>The input here is a row this application wrote, not a client payload, so this is
     * corruption-hardening rather than a security boundary. It costs one comparison per length and
     * it converts the one failure mode that cannot be caught into the one that is already handled.
     *
     * <h2>Why the ceiling is divided, and why it did not work without that</h2>
     * The first version compared every length against {@code compressedBytes * MAX_GZIP_EXPANSION}
     * flat — that is, against the number of BYTES the input could decompress to — while the number
     * being read counts ELEMENTS, each of which costs several bytes on the wire and allocates
     * several more in memory. A corrupted one-megabyte row therefore passed a block count of about
     * 1.03e9, which {@code new ArrayList<>(blockCount)} turns into a four-gigabyte array reference:
     * an {@link OutOfMemoryError}, an {@link Error}, not caught by {@link #decode}'s
     * {@code IOException | RuntimeException}, taking the process down. The bound was in place and
     * the failure it names was still reachable, which is worse than no bound — the class javadoc
     * promised it could not happen.
     *
     * <p>Each caller therefore passes the MINIMUM number of wire bytes one of its elements can
     * occupy, read off the encoder above: 12 for a version-vector or watermark entry
     * ({@code int} + {@code long}), 15 for the shortest possible block (identifier, three flags and
     * a length), 5 for the shortest mark string (the well-formed flag and its length), 1 for a UTF-8
     * byte and 2 for a UTF-16 code unit. Under-estimating is the safe direction: it only makes the
     * bound looser, and the encoder's own output must never be refused — see
     * {@link #readCharacterCount} for what happened the one time it was.
     *
     * @param compressedBytes the size of the snapshot as stored; nothing inside it can describe
     * more elements than DEFLATE could have produced from that many bytes
     * @param wireBytesPerElement the smallest number of bytes one element of this length occupies
     * in the stream
     */
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

    /**
     * Reads a length that describes CHARACTERS rather than bytes in the stream.
     *
     * <h2>Why this is not {@link #readLength}</h2>
     * A content-dropped block (CP18) writes only its length: the characters are gone and the
     * identity is what was kept. That number is therefore <b>not backed by bytes on the wire</b> —
     * four bytes can legitimately say a hundred and twenty thousand characters.
     *
     * <p>Putting it through the gzip-expansion ceiling made the decoder reject snapshots its own
     * encoder had just produced. A large deleted paste in a document with little live text
     * compresses to almost nothing, so the ceiling fell below the count: measured at 99 bytes
     * encoding a 120 000-character dropped block, which decoded to {@code null}. The consequence
     * is not a visible failure but a permanently dead cache — {@code SnapshotStore} treats
     * {@code null} as a miss, replays the whole log, and writes the same unreadable snapshot
     * again. The TypeScript client decoded those exact bytes correctly, so the server could not
     * read a snapshot its own client could.
     *
     * <p>No bound is needed here beyond sanity, and that is the point: unlike every other length
     * in this format, this one drives <b>no allocation</b> — {@link Block#withoutContent} stores
     * an {@code int}. The ceiling was protecting against an out-of-memory error that cannot happen
     * on this field.
     */
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

    /**
     * Whether every surrogate in the string is part of a complete pair.
     *
     * <p>The only strings that fail this are the ones a block split produced by cutting between the
     * two halves of an astral character. One pass, no allocation, and it runs on the snapshot path
     * rather than the keystroke path.
     */
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

            // Ascending by replicaId, not in map order. DocumentSnapshot's compact constructor does
            // Map.copyOf, and ImmutableCollections salts its iteration order per JVM — so the same
            // snapshot encoded in two processes produced different bytes. Sorting makes the encoder
            // reproducible, which is what lets SnapshotFixtureTest compare against committed bytes.
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
                    // Only for tombstones: a visible block has no deleter by construction, and
                    // writing a flag for it would cost a byte per block on every document that has
                    // never been edited.
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

            // Marks (CP13) are stored AS OPERATIONS, in the OpCodec wire format, rather than as
            // resolved formatting segments. Segments are a result, not state: storing them would
            // mean a replica restored from a snapshot could no longer merge a mark arriving later
            // correctly — because it would have nothing to resolve the conflict against.
            out.writeInt(snapshot.marks().size());
            for (Op mark : snapshot.marks()) {
                writeText(out, OpCodec.encode(mark));
            }

            // The GC boundary. Last, so the fields a client actually needs come first,
            // and sorted for the same reproducibility reason as the version vector above.
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
