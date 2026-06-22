package dev.collabedit.crdt;

import java.util.List;
import java.util.Map;

/**
 * A snapshot of a replica's full state — what goes into the {@code snapshots.state} column
 *.
 *
 * <p>A snapshot is a <b>cache, not a source of truth</b>. It may be deleted at any moment; the
 * system will rebuild the state from the operation log, more slowly but correctly. From that
 * follows the most important decision about the format: on a {@link #formatVersion} mismatch the
 * snapshot is <b>ignored</b> rather than migrated. Migrating a cache is a cost with no benefit.
 *
 * <p>It contains five things, because five are needed to rebuild a replica faithfully:
 * <ul>
 *   <li>{@code blocks} — the document structure including tombstones (which matter: they may be
 *       the origin of an operation still to arrive),</li>
 *   <li>{@code clock} — the Lamport clock, without which subsequent local operations would carry
 *       timestamps colliding with history,</li>
 *   <li>{@code maxSeen} — the version vector, needed for causal stability and for the
 *       "what do I already have" answer on reconnect,</li>
 *   <li>{@code marks} — the formatting layer (CP13). Without it an instance restart loses the whole
 *       appearance of the document: the text rebuilds from the blocks and the formatting disappears
 *       without trace. It cannot be derived from the blocks, because marks are a separate set of
 * operations,</li>
 * <li>{@code gcWatermark} — the boundary below which tombstones have already been physically
 * removed. See below.</li>
 * </ul>
 *
 * <h2>Why the GC watermark is here and not only in memory</h2>
 * The tombstone-collection design says plainly that "the snapshot must carry {@code stableVersion}, because the instance
 * loading it needs to know what is already gone". It did not, and the consequence was that the one
 * mechanism in this system which refuses a user's work <i>by design</i> stopped working after any
 * reload.
 *
 * <p>The boundary is what separates two situations that look identical from outside: an operation
 * that arrived before its cause (it waits in the buffer — normal) from one whose cause GC removed
 * (it will never arrive — {@code RESYNC_REQUIRED}). Held only in a field of the in-memory replica,
 * it reset to empty on every eviction — which happens after five minutes of quiet, while the GC
 * window it is compared against is seven days. So in practice the honest refusal the README
 * describes became an operation buffered forever, written to the log and acknowledged: exactly the
 * silent loss of the user's work the tombstone-collection design exists to prevent.
 *
 * @param gcWatermark the highest lamport per replica that GC has already collected past
 */
public record DocumentSnapshot(
    int formatVersion,
    long clock,
    Map<Integer, Long> maxSeen,
    List<Block> blocks,
    List<Op> marks,
    Map<Integer, Long> gcWatermark
) {

    /**
     * The current format version. Raise it on EVERY change to the {@link Block} structure or to the
     * encoding — snapshots written in the old format will then be skipped and the state rebuilt
     * from the log.
     *
     * <h2>Raising it is FREE, and that is not an accident</h2>
     * The operation-log decision settled that a snapshot with an incompatible version is ignored rather than
     * migrated — so adding the mark layer required no migration code, no maintenance window and no
     * decision about what to do with snapshots in the old format. They are skipped, the state
     * rebuilds from the operation log, and the next snapshot is written in the new format. The only
     * cost is a one-off slower first open of each document.
     *
     * <p>That decision was taken in CP5, and it has now paid off five times: CP13 (marks), CP18
     * (content-dropped blocks), version 4 (surrogate-safe strings, and the GC watermark above) and
     * version 5 (the identifier of the operation that deleted a tombstone — see
     * {@link Block#deletedBy} for what GC was doing without it).
     *
     * <p>The history used to live in a {@code FORMAT_VERSION_NOTE} constant beside this one, which
     * nothing ever read. It says the same thing here, where a reader of the version is already
     * looking.
     */
    public static final int CURRENT_FORMAT_VERSION = 5;

    public DocumentSnapshot {
        maxSeen = Map.copyOf(maxSeen);
        blocks = List.copyOf(blocks);
        marks = List.copyOf(marks);
        gcWatermark = Map.copyOf(gcWatermark);
    }

    public int textLength() {
        return blocks.stream().mapToInt(Block::visibleLength).sum();
    }

    public int tombstoneCount() {
        return blocks.stream().mapToInt(b -> b.length() - b.visibleLength()).sum();
    }
}
