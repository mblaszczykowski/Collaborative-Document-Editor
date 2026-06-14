package dev.collabedit.crdt;

/**
 * The ordered list of a document's blocks, with two kinds of lookup: by <b>visible offset</b> (what
 * the editor needs) and by <b>identifier</b> (what the merge algorithm needs).
 *
 * <p>This interface exists deliberately: {@link RgaDocument} holds the algorithm and does not know
 * how the list is stored. That lets the same, once-tested logic run on two implementations of
 * different complexity:
 *
 * <table>
 *   <caption>Operation complexity</caption>
 *   <tr><th></th><th>{@link ArrayBlockList}</th><th>{@link TreapBlockList}</th></tr>
 *   <tr><td>{@code insert}</td><td>O(n)</td><td>O(log n)</td></tr>
 *   <tr><td>{@code locateVisible}</td><td>O(n)</td><td>O(log n)</td></tr>
 *   <tr><td>{@code locateId}</td><td>O(n)</td><td>O(log n)</td></tr>
 * </table>
 *
 * <p>The property test {@code naiveEqualsIndexed} feeds both implementations the same random
 * sequence of operations and compares the results — that is proof the optimisation did not change
 * the behaviour, rather than an assumption that it did not.
 */
public interface BlockList extends Iterable<Block> {

    /** A character's position: the block index and the offset inside it. */
    record Locator(int blockIndex, int offset) { }

    int size();

    Block get(int index);

    void insert(int index, Block block);

    void set(int index, Block block);

    void remove(int index);

    /**
     * Marks a block as deleted. A separate method rather than {@code get(i).markDeleted()}, because
     * the indexed implementation has to update the tree's aggregates at the same time — mutating a
     * block "off to the side" would silently corrupt the structure.
     */
    void markDeleted(int index);

    /** The number of characters visible in the text (excluding tombstones). */
    int visibleLength();

    /**
     * The number of all characters, tombstones included — a measure of the overhead (the metric
     * from ADR-0005).
     */
    int totalLength();

    /**
     * The block containing the character at the given visible index.
     *
     * @return the position, or {@code null} when the index is out of range
     */
    Locator locateVisible(int visibleIndex);

    /**
     * The block containing the character with the given identifier — including when that character
     * is a tombstone.
     *
     * @return the position, or {@code null} when the character is not in the structure
     */
    Locator locateId(Id id);

    /** The number of visible characters before the block at the given index. */
    int visibleOffsetOf(int blockIndex);

    /** An empty list of the same type — used when cloning a document in tests. */
    BlockList newEmpty();
}
