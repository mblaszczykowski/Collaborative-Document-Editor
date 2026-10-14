package dev.collabedit.crdt;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.HashSet;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RgaDocument {
    private final int replicaId;
    private final BlockList blocks;

    private long clock;

    private final Map<Integer, Long> maxSeen = new HashMap<>();

    private final Map<Id, Op> pending = new LinkedHashMap<>();

    private final Set<Id> seenDeletes = new HashSet<>();

    private VersionVector gcWatermark = VersionVector.empty();

    private final MarkSet marks = new MarkSet();

    public RgaDocument(int replicaId) {
        this(replicaId, new ArrayBlockList());
    }

    public RgaDocument(int replicaId, BlockList blocks) {
        this.replicaId = replicaId;
        this.blocks = blocks;
    }

    public Op.Insert insert(int visibleIndex, String text) {
        int length = visibleLength();
        if (visibleIndex < 0 || visibleIndex > length) {
            throw new IndexOutOfBoundsException(
                "position " + visibleIndex + " outside text of length " + length);
        }
        Id originLeft = visibleIndex == 0 ? null : idAtVisible(visibleIndex - 1);

        Op.Insert op = new Op.Insert(new Id(replicaId, clock), originLeft, text);
        clock += op.span();
        integrate(op);
        observeVersion(op);
        return op;
    }

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

        Id deleteId = new Id(replicaId, clock);

        List<Op.Range> targets = new ArrayList<>();
        int remaining = count;
        while (remaining > 0) {
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
        seenDeletes.add(deleteId);
        clock += op.span();
        observeVersion(op);
        return op;
    }

    public Op.AddMark addMark(int from, int to, String type, String value) {
        requireNonEmptyRange(from, to);
        Op.AddMark op = new Op.AddMark(
            new Id(replicaId, clock), type, value, anchorStart(from), anchorEnd(to, type));
        clock += op.span();
        marks.apply(op);
        observeVersion(op);
        return op;
    }

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
        return MarkTypes.expandsAtEnd(type) && to < length
            ? Anchor.before(idAtVisible(to))
            : Anchor.after(idAtVisible(to - 1));
    }

    private static void requireNonEmptyRange(int from, int to) {
        if (from >= to) {
            throw new IllegalArgumentException(
                "an empty formatting range [" + from + ", " + to + ")");
        }
    }

    public List<MarkSet.Span> formatting() {
        return marks.spans(blocks);
    }

    public Map<String, MarkSet.Placement> markPlacements(String type) {
        return marks.placementsOf(blocks, type);
    }

    public int markCount() {
        return marks.size();
    }

    public enum ApplyResult {
        APPLIED,
        DUPLICATE,
        BUFFERED;

        public boolean isNews() {
            return this != DUPLICATE;
        }
    }

    public ApplyResult apply(Op op) {
        return apply(op, Integer.MAX_VALUE);
    }

    public static class PendingOverflowException extends RuntimeException {
        public PendingOverflowException() {
            super("too many operations waiting for a cause (missing dependencies)");
        }
    }

    public ApplyResult apply(Op op, int pendingLimit) {
        observeClock(op);
        if (isAlreadyKnown(op)) {
            return ApplyResult.DUPLICATE;
        }
        if (tryApply(op)) {
            observeVersion(op);
            drainPending();
            return ApplyResult.APPLIED;
        }
        if (pending.containsKey(op.id())) {
            return ApplyResult.DUPLICATE;
        }
        if (pending.size() >= pendingLimit) {
            throw new PendingOverflowException();
        }
        if (pending.putIfAbsent(op.id(), op) != null) {
            return ApplyResult.DUPLICATE;
        }
        return ApplyResult.BUFFERED;
    }

    public boolean hasSeen(Op op) {
        return isAlreadyKnown(op) || pending.containsKey(op.id());
    }

    public boolean canApplyWithoutBuffering(Op op) {
        if (hasSeen(op)) {
            return true;
        }
        return switch (op) {
            case Op.Insert insert -> insert.originLeft() == null
                || blocks.locateId(insert.originLeft()) != null;
            case Op.Delete delete -> delete.targets().stream().allMatch(this::isRangeFullyPresent);
            case Op.AddMark mark -> MarkSet.anchorsResolvable(mark, blocks);
            case Op.RemoveMark mark -> MarkSet.anchorsResolvable(mark, blocks);
        };
    }

    private boolean isAlreadyKnown(Op op) {
        return switch (op) {
            case Op.Insert insert -> blocks.locateId(insert.id()) != null;
            case Op.Delete delete -> seenDeletes.contains(delete.id());
            case Op.AddMark mark -> marks.contains(mark.id());
            case Op.RemoveMark mark -> marks.contains(mark.id());
        };
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
        for (Op.Range range : op.targets()) {
            if (!isRangeFullyPresent(range)) {
                return false;
            }
        }
        for (Op.Range range : op.targets()) {
            markRangeDeleted(range, op.id());
        }
        seenDeletes.add(op.id());
        return true;
    }

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

    private void integrate(Op.Insert op) {
        int position;
        if (op.originLeft() == null) {
            position = 0;
        } else {
            BlockList.Locator loc = blocks.locateId(op.originLeft());
            int index = loc.blockIndex();
            Block originBlock = blocks.get(index);
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

    public int gc(VersionVector stable) {
        // Keep identity skeletons after reclaiming content. Removing an unreferenced block
        // loses insert deduplication across retries and snapshot restoration.
        int reclaimed = 0;
        for (Block block : blocks) {
            if (block.deleted() && block.hasContent() && isDeletionStable(block, stable)) {
                block.dropContent();
                reclaimed += block.length();
            }
        }
        for (int i = blocks.size() - 1; i > 0; i--) {
            Block left = blocks.get(i - 1);
            Block right = blocks.get(i);
            if (!left.hasContent() && !right.hasContent()
                && right.start().isDirectSuccessorOf(left.lastId())
                && left.lastId().equals(right.originLeft())
                && java.util.Objects.equals(left.deletedBy(), right.deletedBy())) {
                blocks.set(i - 1, Block.withoutContent(left.start(),
                    left.length() + right.length(), left.originLeft(), left.deletedBy()));
                blocks.remove(i);
            }
        }
        gcWatermark = gcWatermark.join(stable);
        return reclaimed;
    }

    private static boolean isDeletionStable(Block block, VersionVector stable) {
        Id deletedBy = block.deletedBy();
        if (deletedBy == null) {
            return false;
        }
        return deletedBy.lamport() <= stable.get(deletedBy.replicaId());
    }

    public VersionVector gcWatermark() {
        return gcWatermark;
    }

    public DocumentSnapshot snapshot() {
        List<Block> copies = new ArrayList<>(blocks.size());
        for (Block block : blocks) {
            copies.add(copyOf(block));
        }
        return new DocumentSnapshot(
            DocumentSnapshot.CURRENT_FORMAT_VERSION, clock, maxSeen, copies, marks.all(),
            gcWatermark.asMap());
    }

    public static RgaDocument restore(int replicaId, BlockList storage, DocumentSnapshot snapshot) {
        RgaDocument document = new RgaDocument(replicaId, storage);
        for (Block block : snapshot.blocks()) {
            storage.insert(storage.size(),
                copyOf(block));
            if (block.deletedBy() != null) {
                document.seenDeletes.add(block.deletedBy());
            }
        }
        document.clock = snapshot.clock();
        document.maxSeen.putAll(snapshot.maxSeen());
        for (Op mark : snapshot.marks()) {
            document.marks.apply(mark);
        }
        document.gcWatermark = VersionVector.of(snapshot.gcWatermark());
        return document;
    }

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

    public int tombstoneCount() {
        return blocks.totalLength() - blocks.visibleLength();
    }

    public int tombstoneContentCount() {
        int characters = 0;
        for (Block block : blocks) {
            if (block.deleted() && block.hasContent()) {
                characters += block.length();
            }
        }
        return characters;
    }

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

    public Iterable<Block> blocks() {
        return blocks;
    }

    BlockList blockList() {
        return blocks;
    }

    public boolean contains(Id id) {
        return blocks.locateId(id) != null;
    }

    public Id idAtVisible(int visibleIndex) {
        BlockList.Locator loc = blocks.locateVisible(visibleIndex);
        if (loc == null) {
            throw new IndexOutOfBoundsException("no visible character at position " + visibleIndex);
        }
        return blocks.get(loc.blockIndex()).idAt(loc.offset());
    }

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
