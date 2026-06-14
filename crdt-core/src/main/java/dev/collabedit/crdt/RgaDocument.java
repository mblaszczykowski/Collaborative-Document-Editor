package dev.collabedit.crdt;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A replica of a text document as an RGA-style CRDT.
 *
 * <p>The key property this whole construction exists for: <b>applying the same set of operations in
 * any order and with any repetitions gives the same text.</b> It requires no central arbiter and no
 * ordered transport — and that is why the architecture in ADR-0004 (every backend instance a peer
 * replica, Redis as an unreliable broadcast) is safe rather than merely "usually working".
 *
 * <p>This class contains <b>the algorithm and nothing else</b>. It knows nothing about the network,
 * the database or Spring — see ADR-0001 and the configuration of the {@code crdt-core} module.
 *
 * <h2>Three things that are easy to get wrong</h2>
 * <ol>
 *   <li><b>The Lamport clock.</b> On receiving an operation the clock must be bumped past its
 *       timestamp ({@link #observeClock}). Without that RGA's insertion rule stops being correct —
 *       counterexample in {@code docs/crdt-design.md} section 1.</li>
 *   <li><b>Duplicate detection.</b> Not via the version vector (a Lamport clock is not
 *       contiguous), but via the presence of the character in the structure. Deletions are
 *       idempotent, so we do not check their duplicates at all.</li>
 *   <li><b>Operations that are not ready.</b> An operation whose origin has not yet arrived goes
 *       into a buffer and is retried after every successful application. The core has to survive
 *       any delivery order, because that is exactly what the {@code convergence} property test
 *       demands.</li>
 * </ol>
 */
public final class RgaDocument {

    private final int replicaId;
    private final BlockList blocks;

    /** The Lamport clock — the value the next local operation will receive. */
    private long clock;

    /** The highest applied lamport per replica. For GC and resynchronisation, not for dedup. */
    private final Map<Integer, Long> maxSeen = new HashMap<>();

    /** Operations that cannot be applied yet (a missing origin or deletion target). */
    private final List<Op> pending = new ArrayList<>();

    /**
     * Formatting as a separate layer above the character sequence (CP13).
     *
     * <p>Separate because it is not a character attribute and cannot be one — the justification is
     * in {@link MarkSet}. The character sequence knows nothing about it, which is visible in the
     * fact that {@link BlockList} did not change by a single field in CP13.
     */
    private final MarkSet marks = new MarkSet();

    public RgaDocument(int replicaId) {
        this(replicaId, new ArrayBlockList());
    }

    public RgaDocument(int replicaId, BlockList blocks) {
        this.replicaId = replicaId;
        this.blocks = blocks;
    }

    // ------------------------------------------------------------------
    // Local operations
    // ------------------------------------------------------------------

    /**
     * Inserts text at the given position in the <b>visible</b> text and returns the operation to
     * broadcast to the other replicas.
     */
    public Op.Insert insert(int visibleIndex, String text) {
        int length = visibleLength();
        if (visibleIndex < 0 || visibleIndex > length) {
            throw new IndexOutOfBoundsException(
                "position " + visibleIndex + " outside text of length " + length);
        }
        // The origin is the visible predecessor of the insertion point. When tombstones stand
        // before the position, new text lands before them — standard RGA behaviour.
        Id originLeft = visibleIndex == 0 ? null : idAtVisible(visibleIndex - 1);

        Op.Insert op = new Op.Insert(new Id(replicaId, clock), originLeft, text);
        clock += op.span();
        integrate(op);
        observeVersion(op);
        return op;
    }

    /**
     * Deletes {@code count} characters from the given position in the visible text and returns
     * the operation to broadcast.
     */
    public Op.Delete delete(int visibleIndex, int count) {
        if (count <= 0) {
            throw new IllegalArgumentException("the number of characters to delete must be positive");
        }
        int length = visibleLength();
        if (visibleIndex < 0 || visibleIndex + count > length) {
            throw new IndexOutOfBoundsException(
                "range [" + visibleIndex + ", " + (visibleIndex + count)
                    + ") outside text of length " + length);
        }

        List<Op.Range> targets = new ArrayList<>();
        int remaining = count;
        while (remaining > 0) {
            // Once a block is marked as a tombstone it stops being visible, so visibleIndex
            // already points at the next character to delete — which is why it is not advanced.
            BlockList.Locator loc = blocks.locateVisible(visibleIndex);
            int index = loc.blockIndex();
            if (loc.offset() > 0) {
                splitBlock(index, loc.offset());
                index++;
            }
            Block target = blocks.get(index);
            if (target.length() > remaining) {
                splitBlock(index, remaining);
                target = blocks.get(index);
            }
            blocks.markDeleted(index);
            targets.add(new Op.Range(target.start(), target.length()));
            remaining -= target.length();
        }

        Op.Delete op = new Op.Delete(new Id(replicaId, clock), targets);
        clock += op.span();
        observeVersion(op);
        return op;
    }

    // ------------------------------------------------------------------
    // Formatting (CP13)
    // ------------------------------------------------------------------

    /**
     * Applies formatting to the range of visible text {@code [from, to)}.
     *
     * <h2>Choosing the anchor sides — this is where typing behaviour is decided</h2>
     * <b>Start: {@code before(the character at position from)}.</b> Text typed directly BEFORE the
     * range should not belong to it, and with this anchor new text lands before the anchor, so it
     * stays outside the range. An anchor {@code after(the character at from-1)} would point at the
     * same position today and diverge tomorrow — the formatting would grow backwards.
     *
     * <b>End: depends on the mark type.</b> {@code before(the character at position to)} makes
     * typing at the end of the range JOIN it (which is how bold should behave), while
     * {@code after(the character at to-1)} makes it not (which is how a link should behave). The
     * entire difference between "bold grows as you type" and "a link does not" is which side one
     * anchor sits on.
     *
     * <h2>Known limitation</h2>
     * A range ending at the VERY END of the document cannot grow, because there is no character to
     * pin an expanding anchor to. Peritext solves this with an end-of-document sentinel; there is
     * none here, so a bold placed on the last word will not cover text appended after it. Listed in
     * the README.
     *
     * @return the operation to broadcast to the other replicas
     */
    public Op.AddMark addMark(int from, int to, String type, String value) {
        Op.AddMark op = new Op.AddMark(
            new Id(replicaId, clock), type, value, anchorStart(from), anchorEnd(to, type));
        clock += op.span();
        marks.apply(op);
        observeVersion(op);
        return op;
    }

    /** Removes formatting from the range of visible text {@code [from, to)}. */
    public Op.RemoveMark removeMark(int from, int to, String type) {
        Op.RemoveMark op = new Op.RemoveMark(
            new Id(replicaId, clock), type, anchorStart(from), anchorEnd(to, type));
        clock += op.span();
        marks.apply(op);
        observeVersion(op);
        return op;
    }

    private Anchor anchorStart(int from) {
        int length = visibleLength();
        if (from < 0 || from >= length) {
            throw new IndexOutOfBoundsException(
                "range start " + from + " outside text of length " + length);
        }
        return Anchor.before(idAtVisible(from));
    }

    private Anchor anchorEnd(int to, String type) {
        int length = visibleLength();
        if (to <= 0 || to > length) {
            throw new IndexOutOfBoundsException(
                "range end " + to + " outside text of length " + length);
        }
        // A range reaching the end of the document has no character to pin an expanding anchor
        // to — see "known limitation" in addMark.
        return MarkTypes.expandsAtEnd(type) && to < length
            ? Anchor.before(idAtVisible(to))
            : Anchor.after(idAtVisible(to - 1));
    }

    /** The formatting of the visible text as disjoint segments, in order. */
    public List<MarkSet.Span> formatting() {
        return marks.spans(blocks);
    }

    /** The number of mark operations — a measure of the formatting layer's overhead. */
    public int markCount() {
        return marks.size();
    }

    // ------------------------------------------------------------------
    // Remote operations
    // ------------------------------------------------------------------

    /** What happened to an applied operation. */
    public enum ApplyResult {
        /** The operation contributed a new change to the document. */
        APPLIED,
        /** We had seen this operation before — nothing changed. */
        DUPLICATE,
        /** Its cause is missing (the origin or the deletion target); it waits in the buffer. */
        BUFFERED;

        /** Whether the operation is news to this replica — i.e. worth persisting and broadcasting. */
        public boolean isNews() {
            return this != DUPLICATE;
        }
    }

    /**
     * Applies an operation originating from another replica.
     *
     * <p>Safe under any order and any repetition. An operation that cannot be applied yet goes into
     * the buffer and will be retried automatically.
     *
     * <p>Distinguishing {@link ApplyResult#APPLIED} from {@link ApplyResult#DUPLICATE} is not
     * needed by the algorithm itself — from its point of view they are the same, because merging is
     * idempotent. It is needed by <b>the layer above</b>: there is no point writing a duplicate to
     * the log or broadcasting it across the cluster. Without that distinction the only alternative
     * would be comparing the text before and after, that is, copying the whole document on every
     * keystroke.
     */
    public ApplyResult apply(Op op) {
        // The clock is bumped even for a buffered operation: the Lamport rule concerns receipt,
        // not application. The version vector is updated only after actual application, because it
        // means "I have this integrated", not "I saw it on the network".
        observeClock(op);
        if (isAlreadyKnown(op)) {
            return ApplyResult.DUPLICATE;
        }
        if (tryApply(op)) {
            observeVersion(op);
            drainPending();
            return ApplyResult.APPLIED;
        }
        pending.add(op);
        return ApplyResult.BUFFERED;
    }

    /**
     * Whether we have already seen this particular operation.
     *
     * <p>For an insertion: whether a character with that identifier is in the structure. For a
     * deletion: whether every character it covers is already a tombstone. That second answer is
     * sometimes a false positive — if somebody else deleted exactly the same range, we will treat
     * the operation as known even though it is new. The only effect is that we will not write it to
     * the log; the document's state stays correct, because deletion is idempotent. A deliberate
     * trade-off: distinguishing exactly would require remembering the identifiers of every
     * deletion.
     */
    private boolean isAlreadyKnown(Op op) {
        return switch (op) {
            case Op.Insert insert -> blocks.locateId(insert.id()) != null;
            case Op.Delete delete -> isEntirelyDeleted(delete);
            // Mark operations are keyed by their own identifier, so recognising a duplicate is
            // exact and free — unlike a deletion, where we have to infer it from the contents of
            // the structure.
            case Op.AddMark mark -> marks.contains(mark.id());
            case Op.RemoveMark mark -> marks.contains(mark.id());
        };
    }

    private boolean isEntirelyDeleted(Op.Delete op) {
        for (Op.Range range : op.targets()) {
            long cursor = range.start().lamport();
            long end = cursor + range.length();
            while (cursor < end) {
                BlockList.Locator loc = blocks.locateId(new Id(range.start().replicaId(), cursor));
                if (loc == null) {
                    return false;
                }
                Block block = blocks.get(loc.blockIndex());
                if (!block.deleted()) {
                    return false;
                }
                cursor += block.length() - loc.offset();
            }
        }
        return true;
    }

    public void applyAll(Iterable<Op> ops) {
        for (Op op : ops) {
            apply(op);
        }
    }

    private boolean tryApply(Op op) {
        return switch (op) {
            case Op.Insert insert -> tryApplyInsert(insert);
            case Op.Delete delete -> tryApplyDelete(delete);
            case Op.AddMark mark -> tryApplyMark(mark);
            case Op.RemoveMark mark -> tryApplyMark(mark);
        };
    }

    private boolean tryApplyInsert(Op.Insert op) {
        // Duplicate: a character with this identifier is already in the structure. Exact and
        // free — the structure indexes by identifier anyway (docs/crdt-design.md section 2).
        if (blocks.locateId(op.id()) != null) {
            return true;
        }
        if (op.originLeft() != null && blocks.locateId(op.originLeft()) == null) {
            return false;
        }
        integrate(op);
        return true;
    }

    private boolean tryApplyDelete(Op.Delete op) {
        // All or nothing: applying a deletion partially would leave the operation in the buffer
        // and, on retry, mark some ranges a second time. That would be harmless (idempotence) but
        // would make reasoning about the state harder.
        for (Op.Range range : op.targets()) {
            if (!isRangeFullyPresent(range)) {
                return false;
            }
        }
        for (Op.Range range : op.targets()) {
            markRangeDeleted(range);
        }
        return true;
    }

    /**
     * A mark can only be applied once BOTH of its anchors are in the structure.
     *
     * <p>Before that it goes into the buffer — exactly like an insertion that outran its origin.
     * Applying a mark with an unresolvable anchor would produce formatting in a random place, that
     * is, silent corruption of the document's appearance.
     */
    private boolean tryApplyMark(Op op) {
        if (!MarkSet.anchorsResolvable(op, blocks)) {
            return false;
        }
        marks.apply(op);
        return true;
    }

    private boolean isRangeFullyPresent(Op.Range range) {
        long cursor = range.start().lamport();
        long end = cursor + range.length();
        while (cursor < end) {
            BlockList.Locator loc = blocks.locateId(new Id(range.start().replicaId(), cursor));
            if (loc == null) {
                return false;
            }
            Block block = blocks.get(loc.blockIndex());
            // Skip to the end of the block — a range may cover several blocks, including ones
            // produced by a split or by separate operations with contiguous lamports.
            cursor += block.length() - loc.offset();
        }
        return true;
    }

    private void markRangeDeleted(Op.Range range) {
        long cursor = range.start().lamport();
        long end = cursor + range.length();
        while (cursor < end) {
            BlockList.Locator loc = blocks.locateId(new Id(range.start().replicaId(), cursor));
            int index = loc.blockIndex();
            if (loc.offset() > 0) {
                splitBlock(index, loc.offset());
                index++;
            }
            Block block = blocks.get(index);
            long coverable = Math.min(block.length(), end - cursor);
            if (coverable < block.length()) {
                splitBlock(index, (int) coverable);
                block = blocks.get(index);
            }
            blocks.markDeleted(index);
            cursor += block.length();
        }
    }

    // ------------------------------------------------------------------
    // The heart of the algorithm
    // ------------------------------------------------------------------

    /**
     * Inserts a new block at the place RGA's rule determines.
     *
     * <p>The rule: find the origin, walk right for as long as the blocks encountered have a higher
     * timestamp than the one being inserted, and insert at the first place where the block
     * encountered has a lower one.
     *
     * <p>Why this is correct — two arguments, both resting on {@code lamport} being a Lamport
     * clock:
     * <ul>
     *   <li>An element in the subtree of a sibling S was created causally after S, so it has a
     *       higher timestamp than S. So if we skip S, we skip its entire subtree — siblings are
     *       never torn apart.</li>
     *   <li>The first element past the origin's subtree is a sibling of the origin (or a sibling of
     *       its ancestor), and such an element has a <i>lower</i> timestamp than the origin — while
     *       the element being inserted has a <i>higher</i> timestamp than the origin, because its
     *       author had seen the origin. The scan therefore stops there of its own accord and never
     *       leaves the origin's subtree.</li>
     * </ul>
     */
    private void integrate(Op.Insert op) {
        int position;
        if (op.originLeft() == null) {
            position = 0;
        } else {
            BlockList.Locator loc = blocks.locateId(op.originLeft());
            int index = loc.blockIndex();
            Block originBlock = blocks.get(index);
            // The origin has to be the last character of its block so that "immediately after the
            // origin" is a block boundary. The split is a purely technical operation — it does not
            // change the logical structure, because the right part becomes the child of the last
            // character of the left one.
            if (loc.offset() < originBlock.length() - 1) {
                splitBlock(index, loc.offset() + 1);
            }
            position = index + 1;
        }

        Id newId = op.id();
        while (position < blocks.size() && blocks.get(position).start().compareTo(newId) > 0) {
            position++;
        }

        Block block = new Block(newId, op.text(), op.originLeft(), false);

        // The fast path for ordinary typing: append to the previous block instead of creating a
        // new one. Without it run-length encoding would achieve nothing, because every keystroke
        // is a separate operation.
        if (position > 0) {
            Block previous = blocks.get(position - 1);
            if (previous.canAppend(block)) {
                blocks.set(position - 1, previous.append(block.text()));
                return;
            }
        }
        blocks.insert(position, block);
    }

    private void splitBlock(int index, int offset) {
        Block[] parts = blocks.get(index).splitAt(offset);
        blocks.set(index, parts[0]);
        blocks.insert(index + 1, parts[1]);
    }

    private void drainPending() {
        boolean progress = true;
        while (progress && !pending.isEmpty()) {
            progress = false;
            Iterator<Op> iterator = pending.iterator();
            while (iterator.hasNext()) {
                Op op = iterator.next();
                if (tryApply(op)) {
                    iterator.remove();
                    observeVersion(op);
                    progress = true;
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Tombstone garbage collection (ADR-0005)
    // ------------------------------------------------------------------

    /**
     * Physically removes tombstones that are causally stable with respect to {@code stable} and
     * are not the origin of any existing block.
     *
     * <p>Both conditions are necessary. Causal stability guarantees that no <i>future</i> operation
     * can anchor to that character; the absence of references guarantees that no <i>already
     * applied</i> operation does. Dropping the second condition would corrupt the structure
     * immediately.
     *
     * @return the number of characters whose memory was reclaimed
     */
    public int gc(VersionVector stable) {
        Set<Id> referenced = new HashSet<>();
        for (Block block : blocks) {
            if (block.originLeft() != null) {
                referenced.add(block.originLeft());
            }
        }
        // The THIRD condition, added in CP13: a tombstone that is a MARK ANCHOR also has to stay.
        // Removing it makes the anchor unresolvable and the mark silently disappears — the
        // formatting is lost without a trace and without an error, as a side effect of tidying up
        // memory.
        //
        // Since CP18 this no longer means the block keeps its characters: a block that must not be
        // removed drops its content instead. The condition below decides whether the block stays,
        // not whether it holds text.
        for (Op mark : marks.all()) {
            switch (mark) {
                case Op.AddMark add -> {
                    referenced.add(add.start().charId());
                    referenced.add(add.end().charId());
                }
                case Op.RemoveMark remove -> {
                    referenced.add(remove.start().charId());
                    referenced.add(remove.end().charId());
                }
                default -> { }
            }
        }

        int reclaimed = 0;
        for (int i = blocks.size() - 1; i >= 0; i--) {
            Block block = blocks.get(i);
            if (!block.deleted()) {
                continue;
            }
            if (block.lastId().lamport() > stable.get(block.start().replicaId())) {
                continue;
            }
            if (!isAnyCharReferenced(block, referenced)) {
                blocks.remove(i);
                reclaimed += block.length();
                continue;
            }
            // CP18: the block has to stay, but its CHARACTERS do not.
            //
            // All three reasons above for keeping a tombstone — being another block's origin,
            // being a mark anchor, and causal stability — need the IDENTITY of a character,
            // never its content. Before this, "must keep the identity" meant "must keep
            // everything", and CP15 measured what that cost: a 1000-character document written
            // in one run, fully deleted, with 100 marks, reclaimed exactly 0%. One anchor kept
            // one run-length block, and one run-length block was the whole document.
            //
            // Splitting the block around its anchors instead would not work: the right-hand
            // part is anchored to the last character of the left, so dropping a prefix
            // invalidates the origin of whatever remains. Dropping content sidesteps that
            // entirely, because nothing about the structure changes.
            if (block.hasContent()) {
                block.dropContent();
                reclaimed += block.length();
            }
        }
        return reclaimed;
    }

    private static boolean isAnyCharReferenced(Block block, Set<Id> referenced) {
        for (int offset = 0; offset < block.length(); offset++) {
            if (referenced.contains(block.idAt(offset))) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Snapshoty (ADR-0003)
    // ------------------------------------------------------------------

    /** A snapshot of the full state, to be stored as a cache in the database. */
    public DocumentSnapshot snapshot() {
        List<Block> copies = new ArrayList<>(blocks.size());
        for (Block block : blocks) {
            copies.add(copyOf(block));
        }
        return new DocumentSnapshot(
            DocumentSnapshot.CURRENT_FORMAT_VERSION, clock, maxSeen, copies, marks.all());
    }

    /**
     * Rebuilds a replica from a snapshot.
     *
     * <p>The blocks are inserted in sequence without running the integration algorithm — the
     * snapshot stores them in document order already. That is the entire saving snapshots exist
     * for: rebuilding is linear rather than costing a full replay of the history.
     */
    public static RgaDocument restore(int replicaId, BlockList storage, DocumentSnapshot snapshot) {
        RgaDocument document = new RgaDocument(replicaId, storage);
        for (Block block : snapshot.blocks()) {
            storage.insert(storage.size(),
                copyOf(block));
        }
        document.clock = snapshot.clock();
        document.maxSeen.putAll(snapshot.maxSeen());
        // Marks come back as operations, so a rebuilt replica can resolve a conflict with any
        // mark still to arrive — see SnapshotCodec.
        for (Op mark : snapshot.marks()) {
            document.marks.apply(mark);
        }
        return document;
    }

    // ------------------------------------------------------------------
    // Odczyt stanu
    // ------------------------------------------------------------------

    /**
     * A defensive copy that preserves whether the content is still there.
     *
     * <p>Reconstructing with {@code new Block(..., block.text(), ...)} would throw on a
     * content-dropped block, and a snapshot is exactly where they accumulate.
     */
    private static Block copyOf(Block block) {
        return block.hasContent()
            ? new Block(block.start(), block.text(), block.originLeft(), block.deleted())
            : Block.withoutContent(block.start(), block.length(), block.originLeft());
    }

    public String text() {
        StringBuilder builder = new StringBuilder(blocks.visibleLength());
        for (Block block : blocks) {
            if (!block.deleted()) {
                builder.append(block.text());
            }
        }
        return builder.toString();
    }

    public int visibleLength() {
        return blocks.visibleLength();
    }

    /** The number of characters that are tombstones — the numerator of {@code doc_tombstone_ratio}. */
    public int tombstoneCount() {
        return blocks.totalLength() - blocks.visibleLength();
    }

    /** The number of blocks — a measure of how effective run-length compression is. */
    public int blockCount() {
        return blocks.size();
    }

    public int pendingCount() {
        return pending.size();
    }

    public int replicaId() {
        return replicaId;
    }

    public long clock() {
        return clock;
    }

    public VersionVector version() {
        return VersionVector.of(maxSeen);
    }

    public BlockList blocks() {
        return blocks;
    }

    /**
     * Whether a character with the given identifier is in the structure — including as a
     * tombstone.
     *
     * <p>Needed to distinguish two very different situations that look the same from outside: "the
     * operation arrived before its cause" (transient, it will wait in the buffer) from "the cause
     * was removed by GC" (permanent, it can no longer be merged — ADR-0005).
     */
    public boolean contains(Id id) {
        return blocks.locateId(id) != null;
    }

    /** The identifier of the character at a given index in the visible text — a cursor position (ADR-0006). */
    public Id idAtVisible(int visibleIndex) {
        BlockList.Locator loc = blocks.locateVisible(visibleIndex);
        if (loc == null) {
            throw new IndexOutOfBoundsException("no visible character at position " + visibleIndex);
        }
        return blocks.get(loc.blockIndex()).idAt(loc.offset());
    }

    // ------------------------------------------------------------------

    private void observeClock(Op op) {
        clock = Math.max(clock, op.maxLamport() + 1);
    }

    private void observeVersion(Op op) {
        maxSeen.merge(op.id().replicaId(), op.maxLamport(), Math::max);
    }

    @Override
    public String toString() {
        return "RgaDocument(replica=" + replicaId + ", text=\"" + text() + "\", blocks="
            + blocks.size() + ", tombstones=" + tombstoneCount() + ", pending=" + pending.size() + ")";
    }
}
