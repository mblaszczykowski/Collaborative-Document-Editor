package dev.collabedit.crdt;

import java.util.List;
import java.util.Map;

public record DocumentSnapshot(
    int formatVersion,
    long clock,
    Map<Integer, Long> maxSeen,
    List<Block> blocks,
    List<Op> marks,
    Map<Integer, Long> gcWatermark
) {
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
