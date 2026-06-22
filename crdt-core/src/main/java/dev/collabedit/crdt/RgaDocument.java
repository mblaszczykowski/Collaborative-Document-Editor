package dev.collabedit.crdt;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A replica of a text document as an RGA-style CRDT.
 *
 * <p>The key property this whole construction exists for: <b>applying the same set of operations in
 * any order and with any repetitions gives the same text.</b> It requires no central arbiter and no
 * ordered transport — and that is why the architecture in the cluster design (every backend instance a peer
 * replica, Redis as an unreliable broadcast) is safe rather than merely "usually working".
 *
 * <p>This class contains <b>the algorithm and nothing else</b>. It knows nothing about the network,
 * the database or Spring — see the CRDT decision and the configuration of the {@code crdt-core} module.
 *
 * <h2>Three things that are easy to get wrong</h2>
 * <ol>
 *   <li><b>The Lamport clock.</b> On receiving an operation the clock must be bumped past its
 *       timestamp ({@link #observeClock}). Without that RGA's insertion rule stops being correct —
 * counterexample in the README, "What the Lamport clock is actually for".</li>
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

    /**
     * Operations that cannot be applied yet (a missing origin or deletion target).
     *
     * <p>Keyed by the operation's own identifier, not a plain list. {@link #isAlreadyKnown} detects
     * a duplicate insert only once the character is IN THE STRUCTURE, so an operation waiting here
     * was never recognised as one — and every redelivery path in the system feeds this buffer: the
     * cluster re-broadcast that the cluster design calls an unreliable broadcast, the client's retransmission
     * after reconnect, and log replay on load.
     *
     * <p>Each redelivered copy was a separate entry, and {@code DocumentReplica.merge} treats
     * BUFFERED as news — so every copy was queued for another write to the operation log and
     * another cluster broadcast, and {@link #drainPending} then retried all of them on every
     * successful apply. Operation identifiers are unique per replica across all four operation
     * types, so keying on them is exact rather than a heuristic.
     *
     * <p>The map alone fixes the retry cost but not the write and broadcast cost: those follow
     * from what {@link #apply} REPORTS, so a redelivery of something already waiting here has to
     * come back as {@link ApplyResult#DUPLICATE} rather than {@code BUFFERED}. It does.
     */
    private final Map<Id, Op> pending = new LinkedHashMap<>();

    /**
     * The boundary below which tombstones have already been physically removed.
     *
     * <p>Kept here rather than by the caller because it is a property of THIS document's history:
     * it records what {@link #gc} has already thrown away, and only this class knows that. It
     * travels in the snapshot, so a replica rebuilt from one still knows which operations can never
     * be anchored again — see {@link DocumentSnapshot#gcWatermark}.
     */
    private VersionVector gcWatermark = VersionVector.empty();

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

        // The operation's identifier is minted BEFORE the blocks are marked, because each tombstone
        // records which operation deleted it (Block.deletedBy) and GC's causal-stability test is
        // about that operation rather than about the insertion. Building the Op afterwards, as this
        // did, meant the identifier did not exist yet at the moment it was needed.
        Id deleteId = new Id(replicaId, clock);

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
            blocks.markDeleted(index, deleteId);
            targets.add(new Op.Range(target.start(), target.length()));
            remaining -= target.length();
        }

        Op.Delete op = new Op.Delete(deleteId, targets);
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
        requireNonEmptyRange(from, to);
        Op.AddMark op = new Op.AddMark(
            new Id(replicaId, clock), type, value, anchorStart(from), anchorEnd(to, type));
        clock += op.span();
        marks.apply(op);
        observeVersion(op);
        return op;
    }

    /**
     * Removes formatting from the range of visible text {@code [from, to)}.
     *
     * <p>The operation carries a type and no value, so for the identity-carrying types it means
     * "remove comment marks here", not "remove THIS comment". Two threads anchored to exactly the
     * same characters cannot be detached one at a time — see {@link MarkSet#placementsOf} for why
     * fixing that is a wire-format change rather than a local one.
     */
    public Op.RemoveMark removeMark(int from, int to, String type) {
        requireNonEmptyRange(from, to);
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

    /**
     * Refuses an inverted or empty formatting range.
     *
     * <p>The two anchors validate independently — {@code anchorStart} checks {@code from} against
     * the length and {@code anchorEnd} checks {@code to} — so nothing checked that one came before
     * the other. {@code addMark(4, 2)} was accepted: it consumed a Lamport tick, was stored in the
     * mark set for good, was broadcast and written to the log, pinned two blocks from garbage
     * collection through the anchor-protection rule — and resolved to nothing at all.
     *
     * <p>An {@link IllegalArgumentException} rather than a silent no-op, for the same reason
     * {@code insert} and {@code delete} throw on a bad range: a caller asking for an impossible
     * range has a bug, and the operation would be permanent.
     *
     * <p><b>This guards the local path only.</b> An inverted range arriving from another replica
     * through {@link #apply} is still accepted, and deliberately so: refusing a remote operation
     * would make two replicas hold different sets of operations, which is divergence — the one
     * outcome the whole algorithm exists to prevent. The mark simply resolves to nothing, exactly
     * as it did before this check existed. The check stops such an operation being CREATED; it
     * cannot stop one that already exists somewhere else.
     */
    private static void requireNonEmptyRange(int from, int to) {
        if (from >= to) {
            throw new IllegalArgumentException(
                "an empty formatting range [" + from + ", " + to + ")");
        }
    }

    /** The formatting of the visible text as disjoint segments, in order. */
    public List<MarkSet.Span> formatting() {
        return marks.spans(blocks);
    }

    /**
     * Where each distinct value of one mark type currently sits — see
     * {@link MarkSet#placementsOf}.
     *
     * <p>This is the query to use for a mark type whose value is an <b>identity</b> (a comment
     * thread, a suggestion) rather than decoration. {@link #formatting} resolves one winner per
     * type per segment, which is right for rendering and wrong for asking "where is thread X".
     */
    public Map<String, MarkSet.Placement> markPlacements(String type) {
        return marks.placementsOf(blocks, type);
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
        /**
         * We had seen this operation before — nothing changed.
         *
         * <p>Covers both a change already integrated and one already waiting in the buffer. The
         * layer above needs the same answer for the two: it persisted and broadcast the operation
         * when it first arrived, and neither is worth doing twice.
         */
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
        if (pending.putIfAbsent(op.id(), op) != null) {
            // Already waiting: the FIRST arrival was reported as news, so the layer above has
            // already written it to the log and broadcast it. Reporting news a second time is
            // exactly the amplification keying this buffer was meant to end — deduplicating the
            // entries while still saying "news" would have halved the problem and hidden it.
            return ApplyResult.DUPLICATE;
        }
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
        // free — the structure indexes by identifier anyway (the README, "Why deduplication is not the version vector's job").
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
            markRangeDeleted(range, op.id());
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

    private void markRangeDeleted(Op.Range range, Id deleteId) {
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
            blocks.markDeleted(index, deleteId);
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
            Iterator<Op> iterator = pending.values().iterator();
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
    // Tombstone garbage collection
    // ------------------------------------------------------------------

    /**
     * Physically removes tombstones nothing can still refer to.
     *
     * <p>Four conditions, and each one guards a different way of being referred to:
     * <ol>
     * <li><b>The DELETION is causally stable</b> — every replica that still counts has seen the
     * operation that tombstoned this character, so none of them can still be displaying it
     * and anchoring to it. See {@link #isDeletionStable}; this used to test the insertion
     * instead, which is a different question and the wrong one.</li>
     * <li><b>Not the origin of any existing block</b> — an operation already applied points
     * at it.</li>
     * <li><b>Not an anchor of any applied mark</b> (CP13) — removing it makes the anchor
     * unresolvable and the formatting silently disappears.</li>
     * <li><b>Not referenced by anything in the buffer</b> — an operation that outran its cause is
     * exactly the case where the cause must not be collected out from under it.</li>
     * </ol>
     *
     * <p>Condition 1 is about the <i>future</i>: no operation not yet seen can name this character.
     * Conditions 2–4 are about the <i>present</i>: nothing already here names it. Dropping any of
     * them corrupts something, and the four failures are entirely different, which is why they are
     * listed rather than summarised.
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
            addReferences(referenced, mark);
        }

        // The FOURTH condition. Operations WAITING IN THE BUFFER reference characters too, and
        // this loop did not exist — so `referenced` described only what had already been applied.
        //
        // The case is narrow and the failure is total. A mark waits here because ONE of its two
        // anchors has not arrived; the OTHER may be a present, causally stable, otherwise
        // unreferenced tombstone. GC then destroyed it, and when the missing anchor finally
        // arrived the mark could never resolve: it sat in `pending` for the lifetime of the
        // process while the comment or suggestion it anchored simply was not there. Reproduced:
        // with the gc call the formatting comes back empty and pendingCount stays at 1; without
        // it, the mark applies. A buffered insert is harmless by comparison — its origin is absent
        // by definition — but it costs nothing to be exact here, and an operation that outran its
        // cause is precisely the case where the cause must not be collected underneath it.
        List<Op.Range> awaitedRanges = new ArrayList<>();
        for (Op waiting : pending.values()) {
            addReferences(referenced, waiting);
            if (waiting instanceof Op.Delete delete) {
                awaitedRanges.addAll(delete.targets());
            }
        }

        int reclaimed = 0;
        for (int i = blocks.size() - 1; i >= 0; i--) {
            Block block = blocks.get(i);
            if (!block.deleted()) {
                continue;
            }
            if (!isDeletionStable(block, stable)) {
                continue;
            }
            if (!isAnyCharReferenced(block, referenced) && !isInAwaitedRange(block, awaitedRanges)) {
                blocks.remove(i);
                // Only what is actually still held. A block whose content an earlier sweep already
                // dropped gives back nothing when it is finally removed, and counting its length a
                // second time made the counter report more characters reclaimed than the document
                // ever contained — nine for a six-character document, in the case that produced
                // this comment. crdt_gc_reclaimed_characters_total is the the tombstone-collection design diagnostic for
                // "is GC working at all", so a number that can exceed the document's size answers
                // that question wrongly in the reassuring direction.
                reclaimed += block.hasContent() ? block.length() : 0;
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
        // RAISED, never assigned — the boundary describes what has ALREADY been removed, and
        // removal does not reverse. `stable` is a meet over the replicas that currently count
        // (DocumentReplica.stableVersion), so it SHRINKS whenever a client joins reporting a lower
        // version vector, or reaches zero when nobody is counted at all.
        //
        // Assigning it, which is what this did, therefore moved the boundary backwards on an
        // ordinary event: one new client mid-sync, and an operation anchored to a character this
        // very method destroyed stopped being recognised as unanchorable. It was then buffered for
        // ever, written to the log and acknowledged — the silent loss of the user's work that
        // the tombstone-collection design exists to prevent, arriving through the mechanism meant to prevent it.
        //
        // The old in-memory field had the same flaw and partly hid it: a reload reset the boundary
        // to empty anyway. Now that it is durable, a regressed value would persist.
        gcWatermark = gcWatermark.join(stable);
        return reclaimed;
    }

    /**
     * Whether the operation that tombstoned this block is causally stable.
     *
     * <h2>The question this asks, and the one it used to ask</h2>
     * The tombstone-collection design: a tombstone may be removed once <b>its deletion operation</b> is
     * entirely below {@code stableVersion}. This used to read
     * {@code block.lastId.lamport > stable.get(block.start.replicaId)}, which asks whether
     * every replica has seen the <b>insertion</b> — a different question with a different answer,
     * and one the insertion always passes in a document older than a few seconds.
     *
     * <p>The consequence was not a leak but the opposite, and it was the expensive direction. A
     * replica that holds the character and not its deletion still displays it and will anchor to
     * it. So deleting a paragraph written a minute ago made its tombstones instantly collectable,
     * and a peer that had not yet received the delete came back with an operation pointing at a
     * character the server had destroyed — {@code RESYNC_REQUIRED}, the refusal the tombstone-collection design reserves
     * for a client absent longer than the seven-day GC window, fired by a race measured in
     * milliseconds.
     *
     * <p>A {@code null} deleter means the block was tombstoned by a path that did not record which
     * operation did it. No such path exists in this class, but the answer here is "keep" rather
     * than "assume it is fine", because being wrong this way costs memory and being wrong the other
     * way costs the user's text.
     */
    private static boolean isDeletionStable(Block block, VersionVector stable) {
        Id deletedBy = block.deletedBy();
        if (deletedBy == null) {
            return false;
        }
        return deletedBy.lamport() <= stable.get(deletedBy.replicaId());
    }

    /** Adds the characters an operation depends on to the set GC must not remove. */
    private static void addReferences(Set<Id> referenced, Op op) {
        switch (op) {
            case Op.AddMark add -> {
                referenced.add(add.start().charId());
                referenced.add(add.end().charId());
            }
            case Op.RemoveMark remove -> {
                referenced.add(remove.start().charId());
                referenced.add(remove.end().charId());
            }
            case Op.Insert insert -> {
                if (insert.originLeft() != null) {
                    referenced.add(insert.originLeft());
                }
            }
            case Op.Delete delete -> {
                // A buffered deletion is handled by RANGE rather than by identifier — see
                // isInAwaitedRange. Its start is added here as well only because it costs nothing
                // and keeps the two mechanisms agreeing about the one character they share.
                for (Op.Range range : delete.targets()) {
                    referenced.add(range.start());
                }
            }
        }
    }

    /**
     * Whether a block holds any character a buffered deletion is still waiting for.
     *
     * <h2>Why the START of the range is not enough, which is what this replaces</h2>
     * The first version of this protected only {@code range.start}, reasoning that "the interior
     * is unreachable without the start being present". That is false, and the failure it produces
     * is permanent divergence rather than a delayed operation.
     *
     * <p>{@link #isRangeFullyPresent} walks a range <b>block by block</b>, stepping by the length
     * of whatever block it lands in. A range that was one run-length block on the sender can be
     * several on the receiver — a concurrent insert anchored inside it splits it — so removing any
     * block after the first breaks the walk at that point and the deletion can never apply. It
     * stays in {@code pending} for the life of the process while every other replica shows the
     * text deleted, which is exactly the "every replica agrees except this one" outcome the
     * algorithm exists to prevent.
     *
     * <p>Tested by overlap rather than by adding every identifier to {@code referenced}: a delete
     * range covers as many characters as the user selected, and a large deleted paste would put
     * hundreds of thousands of entries into a set that is otherwise a handful. There are at most
     * {@code max-pending-operations} ranges, and almost always none.
     */
    private static boolean isInAwaitedRange(Block block, List<Op.Range> awaited) {
        for (Op.Range range : awaited) {
            if (range.start().replicaId() != block.start().replicaId()) {
                continue;
            }
            long rangeFrom = range.start().lamport();
            long rangeTo = rangeFrom + range.length();
            long blockFrom = block.start().lamport();
            long blockTo = blockFrom + block.length();
            if (blockFrom < rangeTo && rangeFrom < blockTo) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAnyCharReferenced(Block block, Set<Id> referenced) {
        for (int offset = 0; offset < block.length(); offset++) {
            if (referenced.contains(block.idAt(offset))) {
                return true;
            }
        }
        return false;
    }

    /**
     * The boundary below which tombstones have already been physically removed.
     *
     * <p>An operation whose origin is BOTH absent from the structure and below this boundary can
     * never be anchored: the character it pointed at is gone for good. Absence alone means nothing
     * (the operation may have outrun its cause), and being below the boundary alone means nothing
     * either (GC removes only tombstones).
     */
    public VersionVector gcWatermark() {
        return gcWatermark;
    }

    // ------------------------------------------------------------------
    // Snapshots
    // ------------------------------------------------------------------

    /** A snapshot of the full state, to be stored as a cache in the database. */
    public DocumentSnapshot snapshot() {
        List<Block> copies = new ArrayList<>(blocks.size());
        for (Block block : blocks) {
            copies.add(copyOf(block));
        }
        return new DocumentSnapshot(
            DocumentSnapshot.CURRENT_FORMAT_VERSION, clock, maxSeen, copies, marks.all(),
            gcWatermark.asMap());
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
        document.gcWatermark = VersionVector.of(snapshot.gcWatermark());
        return document;
    }

    // ------------------------------------------------------------------
    // Reading the state
    // ------------------------------------------------------------------

    /**
     * A defensive copy that preserves whether the content is still there.
     *
     * <p>Reconstructing with {@code new Block(..., block.text(), ...)} would throw on a
     * content-dropped block, and a snapshot is exactly where they accumulate.
     */
    private static Block copyOf(Block block) {
        return block.hasContent()
            ? new Block(block.start(), block.text(), block.originLeft(), block.deleted(),
                block.deletedBy())
: Block.withoutContent(block.start(), block.length(), block.originLeft(),
                block.deletedBy());
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

    /**
     * The blocks in document order, read-only.
     *
     * <p>This used to return the live {@link BlockList}, which is the same door
     * {@link Block#markDeleted} was made package-private to close, one level up: any caller could
     * invoke {@code remove}, {@code insert}, {@code set} or {@code markDeleted} on the structure
     * and desynchronise {@code clock}, {@code maxSeen} and {@code gcWatermark} from it, with no
     * compiler complaint and no symptom until the next insertion in that spot.
     *
     * <p>Both production callers outside this package — {@code HistoryService} and
     * {@code SuggestionService} — only iterate, so narrowing the type costs them nothing. The
     * structural accessor stays as {@link #blockList} for the tests and the benchmark, which are
     * in this package and do need it.
     */
    public Iterable<Block> blocks() {
        return blocks;
    }

    /** The structure itself — package-private, for the invariant checks and the benchmark. */
    BlockList blockList() {
        return blocks;
    }

    /**
     * Whether a character with the given identifier is in the structure — including as a
     * tombstone.
     *
     * <p>Needed to distinguish two very different situations that look the same from outside: "the
     * operation arrived before its cause" (transient, it will wait in the buffer) from "the cause
     * was removed by GC" (permanent, it can no longer be merged — the tombstone-collection design).
     */
    public boolean contains(Id id) {
        return blocks.locateId(id) != null;
    }

    /** The identifier of the character at a given index in the visible text — a cursor position. */
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
