package dev.collabedit.crdt;

/**
 * A contiguous run of characters from one replica — the unit of storage in a document.
 *
 * <p>A document is not a list of characters but a list of blocks. Typing 20 characters in a row is
 * one {@code Block}, not 20 objects. The justification and the invariants are in
 * the README, "The document is a list of runs".
 *
 * <p><b>Invariant:</b> a block is entirely alive or entirely deleted. Deleting part of one splits
 * the block first. The alternative (an array of per-character flags) would complicate every
 * indexing operation while saving little.
 *
 * <p>The character at index {@code i} has the identifier {@code start.plus(i)}, and its left origin
 * is the character {@code i-1} (for {@code i == 0} it is {@link #originLeft}).
 *
 * <h2>A block without content (CP18)</h2>
 * A deleted block may <b>drop its text while staying in the structure</b>. It keeps its
 * identity ({@link #start}), its {@link #length}, its {@link #originLeft} and its position;
 * it loses only the characters, which nobody can see anyway.
 *
 * <p>This exists because {@link RgaDocument#gc} could barely reclaim anything. The tombstone-collection design lists
 * three reasons a tombstone must be kept, and CP15 measured the cost: a 1000-character document
 * written in one run, fully deleted, with 100 formatting marks, reclaimed <b>0%</b>. All three
 * reasons — being another block's origin, being a mark anchor, causal stability — need the
 * <b>identity</b> of a character, never its content. So the answer was never to keep or remove
 * the whole block; it was to separate the two things being kept.
 *
 * <p>The invariant therefore changes wording, and this is the only semantic change in CP18:
 * <b>a block always has positive length, but a deleted block may have no text.</b> Everything
 * that used to read {@code text.length()} now reads {@link #length}, which is why the field
 * exists at all rather than being derived.
 */
public final class Block {

    private final Id start;

    /**
     * The characters, or {@code null} once dropped.
     *
     * <p>Not final, for the same reason {@link #deleted} is not: dropping content is a change
     * that may happen to an existing block, and copying the structure to express it would cost
     * more than the memory being reclaimed.
     */
    private String text;

    /**
     * Number of characters this block covers — <b>with or without content</b>.
     *
     * <p>Stored rather than derived from {@code text.length()}, because after the content is
     * dropped there is nothing to derive it from, and every index in the document depends on
     * it: the treap aggregates, {@link #containsId}, {@link #lastId}, position lookup. A block
     * that forgets its length forgets where every character after it lives.
     */
    private final int length;

    private final Id originLeft;
    private boolean deleted;

    /**
     * The identifier of the operation that tombstoned this block; {@code null} while it is visible.
     *
     * <h2>Why the deleter's identity has to be stored</h2>
     * The tombstone-collection design says a tombstone may be physically removed once <b>its deletion
     * operation</b> is entirely below {@code stableVersion}. {@link RgaDocument#gc} used to test
     * something else entirely — {@code block.lastId.lamport} against
     * {@code stable.get(block.start.replicaId)}, which asks whether every replica has seen the
     * <b>insertion</b>. Those are different questions with different answers, and the gap between
     * them loses a user's work:
     *
     * <p>A replica that holds the character but not its deletion still <b>displays</b> it, and will
     * anchor an insertion to it. In a document older than one progress-report cycle the insert
     * lamports sit far below {@code stable} for unrelated reasons, so a character deleted a second
     * ago was immediately collectable — and the peer that had not yet received the delete came back
     * with an operation anchored to a character that no longer existed. That is
     * {@code RESYNC_REQUIRED} on a sub-second race, on the one path in this system that refuses a
     * user's edit by design, when the tombstone-collection design confines it to a seven-day absence.
     *
     * <p>Nothing else in the structure can answer the question. The insertion's identity is
     * {@link #start}; the deletion's is an entirely separate operation from a possibly different
     * replica, and the only place to keep it is here, beside the flag it justifies.
     *
     * <p><b>A {@code null} on a deleted block means "the deleter is unknown", and GC must then
     * keep it.</b> That case is not reachable through any path in this class — every caller of
     * {@link BlockList#markDeleted(int, Id)} has the operation in hand — but the conservative
     * answer is written into {@link RgaDocument#gc} regardless, because the cost of being wrong in
     * that direction is memory and the cost of being wrong in the other is the user's text.
     */
    private Id deletedBy;

    public Block(Id start, String text, Id originLeft, boolean deleted) {
        this(start, text, originLeft, deleted, null);
    }

    /**
     * A block with a known deleter — the form snapshots and splits use.
     *
     * @param deletedBy the identifier of the deleting operation, or {@code null} when the block is
     * visible or its deleter is not known. See the field's javadoc.
     */
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

    /**
     * A block that has already had its content dropped — used when restoring from a snapshot.
     *
     * <p>Only ever deleted: dropping the content of a visible block would delete text nobody
     * asked to delete, so the constructor refuses rather than trusting the caller.
     */
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

    /**
     * @throws IllegalStateException when the content has been dropped. Deliberately loud: the
     *         only correct readers of this are paths that already know the block is visible,
     *         and a silent empty string here would delete text from the document instead.
     */
    public String text() {
        if (text == null) {
            throw new IllegalStateException(
                "content of this block was dropped by GC; check hasContent() first: " + this);
        }
        return text;
    }

    /** Whether the characters are still held, as opposed to only the identity. */
    public boolean hasContent() {
        return text != null;
    }

    /**
     * Releases the characters, keeping everything the structure depends on.
     *
     * @throws IllegalStateException when the block is still visible
     */
    void dropContent() {
        if (!deleted) {
            throw new IllegalStateException("cannot drop content of a visible block: " + this);
        }
        text = null;
    }

    /** The left origin of the block's <b>first</b> character; {@code null} means the start of the document. */
    public Id originLeft() {
        return originLeft;
    }

    public boolean deleted() {
        return deleted;
    }

    /** The operation that tombstoned this block, or {@code null}. See the field's javadoc. */
    public Id deletedBy() {
        return deletedBy;
    }

    // Package-private, not public. BlockList.markDeleted exists precisely because the indexed
    // implementation must update the treap's aggregates at the same moment, and mutating a
    // block off to the side silently corrupts the structure — a failure the README
    // records as one of the three that mutation testing had to find. Leaving this public left
    // the door the neighbouring javadoc says must not be used; the two callers are both
    // in-package, so closing it costs nothing and makes the invariant a compiler's problem.
    //
    // Takes the deleting operation's identifier, because GC needs to know whether THAT operation
    // is causally stable rather than whether the insertion is — see the deletedBy field.
    //
    // The SMALLEST deleting identifier wins among those that REACH this method, which is not the
    // same as the smallest that exists, and the difference is worth stating because an earlier
    // version of this comment claimed otherwise.
    //
    // It claimed the minimum makes the recorded deleter "a function of the operation SET". It does
    // not. RgaDocument.apply asks isAlreadyKnown BEFORE tryApplyDelete, and isEntirelyDeleted
    // answers true for any deletion whose target characters are already tombstones — so when two
    // replicas delete the same range concurrently, whichever arrives second is dismissed as a
    // duplicate and its identifier never gets here. Deliver them in the other order and the block
    // records the other deleter. Measured: the same operation set in two orders produces different
    // snapshot bytes, and a GC that reclaims three characters in one order and none in the other.
    //
    // That is a real asymmetry between instances and it is NOT a safety violation, which is why it
    // is recorded rather than fixed: the deleter stored is always one this replica actually
    // applied, so "every counted replica has seen it" still implies every replica considers the
    // character deleted. Two instances already GC at different moments by design (each sweeps on
    // its own schedule), so which tombstones are physically gone was never identical between them.
    // What is lost is snapshot byte-reproducibility across instances — worth knowing, and cheaper
    // to know than to remove, since removing it means changing how deletions are deduplicated.
    //
    // The minimum is still the better of the two available choices: it is the earliest deletion,
    // so it is the one most likely to be causally stable already.
    void markDeleted(Id deleteId) {
        this.deleted = true;
        if (deleteId != null && (this.deletedBy == null || deleteId.compareTo(this.deletedBy) < 0)) {
            this.deletedBy = deleteId;
        }
    }

    public int length() {
        return length;
    }

    /** The number of characters visible in the text — 0 for a tombstone. */
    public int visibleLength() {
        return deleted ? 0 : length;
    }

    public Id idAt(int offset) {
        return start.plus(offset);
    }

    /** The identifier of the block's last character. */
    public Id lastId() {
        return start.plus(length - 1);
    }

    public boolean containsId(Id id) {
        return id.replicaId() == start.replicaId()
            && id.lamport() >= start.lamport()
            && id.lamport() < start.lamport() + length;
    }

    /** The character's offset within the block; meaningful only when {@link #containsId} is true. */
    public int offsetOf(Id id) {
        return (int) (id.lamport() - start.lamport());
    }

    /**
     * Splits the block in two: {@code [0, offset)} and {@code [offset, length)}.
     *
     * <p>The right part's origin is the last character of the left one — which is what makes a
     * split change the document's representation rather than its logical structure. That is the key
     * invariant: a split must be a purely technical operation, invisible to the merge algorithm.
     *
     * <p>Works on a content-dropped block too, splitting the length alone. That case is reached
     * when an operation arrives whose origin sits inside a tombstone GC has already emptied —
     * normal, since the identity is exactly what was kept.
     */
    public Block[] splitAt(int offset) {
        if (offset <= 0 || offset >= length) {
            throw new IllegalArgumentException(
                "split outside the block's interior: offset=" + offset + ", length=" + length);
        }
        // Both halves inherit the deleter, because a split changes the representation and not the
        // logical structure — the characters on either side were tombstoned by the same operation.
        // Losing it here would leave GC unable to prove either half collectable, which is safe but
        // means a document that has been split often can never reclaim anything.
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

    /**
     * Whether {@code other} can be appended to the end of this block without changing its meaning —
     * that is, whether it comes from the same replica, has the next lamport, the same tombstone
     * state, and is anchored to exactly this block's last character.
     *
     * <p>This is the fast path for ordinary typing: without it every keystroke would create a
     * separate block and run-length encoding would achieve nothing.
     *
     * <p>A content-dropped block can never be appended to, and needs no extra check for it:
     * dropping requires {@code deleted}, and appending requires the opposite on both sides.
     */
    public boolean canAppend(Block other) {
        return !deleted
            && !other.deleted
            && other.start.isDirectSuccessorOf(lastId())
            && lastId().equals(other.originLeft);
    }

    public Block append(String more) {
        // Reachable only through canAppend(), which requires both blocks visible, so a
        // content-dropped block can never get here. Checked anyway because the failure without
        // it is silent and permanent: string concatenation would splice the literal "null" into
        // the document and give the block a length four characters too long, which every index
        // downstream would then trust.
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
