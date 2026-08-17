package dev.collabedit.crdt;

public final class Block {
    private final Id start;

    private String text;

    private final int length;

    private final Id originLeft;
    private boolean deleted;

    private Id deletedBy;

    public Block(Id start, String text, Id originLeft, boolean deleted) {
        this(start, text, originLeft, deleted, null);
    }

    public Block(Id start, String text, Id originLeft, boolean deleted, Id deletedBy) {
        if (text.isEmpty()) {
            throw new IllegalArgumentException("a block cannot be empty");
        }
        if (!deleted && deletedBy != null) {
            throw new IllegalArgumentException(
                "a visible block cannot carry a deleter: " + start + " deletedBy=" + deletedBy);
        }
        this.start = start;
        this.text = text;
        this.length = text.length();
        this.originLeft = originLeft;
        this.deleted = deleted;
        this.deletedBy = deletedBy;
    }

    static Block withoutContent(Id start, int length, Id originLeft, Id deletedBy) {
        if (length <= 0) {
            throw new IllegalArgumentException("a block cannot be empty");
        }
        return new Block(start, length, originLeft, deletedBy);
    }

    private Block(Id start, int length, Id originLeft, Id deletedBy) {
        this.start = start;
        this.text = null;
        this.length = length;
        this.originLeft = originLeft;
        this.deleted = true;
        this.deletedBy = deletedBy;
    }

    public Id start() {
        return start;
    }

    public String text() {
        if (text == null) {
            throw new IllegalStateException(
                "content of this block was dropped by GC; check hasContent() first: " + this);
        }
        return text;
    }

    public boolean hasContent() {
        return text != null;
    }

    void dropContent() {
        if (!deleted) {
            throw new IllegalStateException("cannot drop content of a visible block: " + this);
        }
        text = null;
    }

    public Id originLeft() {
        return originLeft;
    }

    public boolean deleted() {
        return deleted;
    }

    public Id deletedBy() {
        return deletedBy;
    }

    void markDeleted(Id deleteId) {
        this.deleted = true;
        if (deleteId != null && (this.deletedBy == null || deleteId.compareTo(this.deletedBy) < 0)) {
            this.deletedBy = deleteId;
        }
    }

    public int length() {
        return length;
    }

    public int visibleLength() {
        return deleted ? 0 : length;
    }

    public Id idAt(int offset) {
        return start.plus(offset);
    }

    public Id lastId() {
        return start.plus(length - 1);
    }

    public boolean containsId(Id id) {
        return id.replicaId() == start.replicaId()
            && id.lamport() >= start.lamport()
            && id.lamport() < start.lamport() + length;
    }

    public int offsetOf(Id id) {
        return (int) (id.lamport() - start.lamport());
    }

    public Block[] splitAt(int offset) {
        if (offset <= 0 || offset >= length) {
            throw new IllegalArgumentException(
                "split outside the block's interior: offset=" + offset + ", length=" + length);
        }
        if (text == null) {
            return new Block[] {
                new Block(start, offset, originLeft, deletedBy),
                new Block(start.plus(offset), length - offset, start.plus(offset - 1), deletedBy)
            };
        }
        Block left = new Block(start, text.substring(0, offset), originLeft, deleted, deletedBy);
        Block right = new Block(
            start.plus(offset), text.substring(offset), start.plus(offset - 1), deleted, deletedBy);
        return new Block[] {left, right};
    }

    public boolean canAppend(Block other) {
        return !deleted
            && !other.deleted
            && other.start.isDirectSuccessorOf(lastId())
            && lastId().equals(other.originLeft);
    }

    public Block append(String more) {
        if (text == null) {
            throw new IllegalStateException("cannot append to a block whose content was dropped: "
                + this);
        }
        return new Block(start, text + more, originLeft, deleted);
    }

    @Override
    public String toString() {
        String body = text == null ? "<" + length + " dropped>" : "\"" + text + "\"";
        return (deleted ? "~" : "") + start + ":" + body + "←" + originLeft;
    }
}
