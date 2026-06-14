package dev.collabedit.crdt;

import java.util.List;
import java.util.Map;

/**
 * A snapshot of a replica's full state — what goes into the {@code snapshots.state} column
 * (ADR-0003).
 *
 * <p>A snapshot is a <b>cache, not a source of truth</b>. It may be deleted at any moment; the
 * system will rebuild the state from the operation log, more slowly but correctly. From that
 * follows the most important decision about the format: on a {@link #formatVersion} mismatch the
 * snapshot is <b>ignored</b> rather than migrated. Migrating a cache is a cost with no benefit.
 *
 * <p>It contains four things, because four are needed to rebuild a replica faithfully:
 * <ul>
 *   <li>{@code blocks} — the document structure including tombstones (which matter: they may be
 *       the origin of an operation still to arrive),</li>
 *   <li>{@code clock} — the Lamport clock, without which subsequent local operations would carry
 *       timestamps colliding with history,</li>
 *   <li>{@code maxSeen} — the version vector, needed for causal stability (ADR-0005) and for the
 *       "what do I already have" answer on reconnect,</li>
 *   <li>{@code marks} — the formatting layer (CP13). Without it an instance restart loses the whole
 *       appearance of the document: the text rebuilds from the blocks and the formatting disappears
 *       without trace. It cannot be derived from the blocks, because marks are a separate set of
 *       operations.</li>
 * </ul>
 */
public record DocumentSnapshot(
    int formatVersion,
    long clock,
    Map<Integer, Long> maxSeen,
    List<Block> blocks,
    List<Op> marks
) {

    /**
     * The current format version. Raise it on EVERY change to the {@link Block} structure or to the
     * encoding — snapshots written in the old format will then be skipped and the state rebuilt
     * from the log.
     */
    public static final int CURRENT_FORMAT_VERSION = 3;

    public DocumentSnapshot {
        maxSeen = Map.copyOf(maxSeen);
        blocks = List.copyOf(blocks);
        marks = List.copyOf(marks);
    }

    /**
     * Raising the format version is FREE, and that is not an accident.
     *
     * <p>ADR-0003 settled that a snapshot with an incompatible version is ignored rather than
     * migrated — so adding the mark layer required no migration code, no maintenance window, and no
     * decision about what to do with snapshots in the old format. They are skipped, the state
     * rebuilds from the operation log, and the next snapshot is written in the new format. The only
     * cost is a one-off slower first open of each document.
     *
     * <p>That decision was taken in CP5, and it paid off in CP13 — and again in CP18.
     */
    public static final String FORMAT_VERSION_NOTE =
        "version 3: a block may have no content — we store a flag and the length (CP18); "
            + "version 2: the mark layer added (CP13)";

    public boolean isReadable() {
        return formatVersion == CURRENT_FORMAT_VERSION;
    }

    public int textLength() {
        return blocks.stream().mapToInt(Block::visibleLength).sum();
    }

    public int tombstoneCount() {
        return blocks.stream().mapToInt(b -> b.length() - b.visibleLength()).sum();
    }
}
