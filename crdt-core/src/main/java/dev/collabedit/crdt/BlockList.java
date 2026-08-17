package dev.collabedit.crdt;

public interface BlockList extends Iterable<Block> {
    record Locator(int blockIndex, int offset) { }

    int size();

    Block get(int index);

    void insert(int index, Block block);

    void set(int index, Block block);

    void remove(int index);

    void markDeleted(int index, Id deleteId);

    int visibleLength();

    int totalLength();

    Locator locateVisible(int visibleIndex);

    Locator locateId(Id id);

    int visibleOffsetOf(int blockIndex);
}
