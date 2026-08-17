package dev.collabedit.crdt;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeSet;

public final class RgaDocument {
    private final int replicaId;
    private final BlockList blocks;

    private long clock;

    private final Map<Integer, Long> maxSeen = new HashMap<>();

    private final Map<Id, Op> pending = new LinkedHashMap<>();

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
            return ApplyResult.DUPLICATE;
        }
        return ApplyResult.BUFFERED;
    }

    private boolean isAlreadyKnown(Op op) {
        return switch (op) {
            case Op.Insert insert -> blocks.locateId(insert.id()) != null;
            case Op.Delete delete -> isEntirelyDeleted(delete);
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
        Map<Integer, NavigableSet<Long>> referenced = new HashMap<>();
        for (Block block : blocks) {
            if (block.originLeft() != null) {
                addReference(referenced, block.originLeft());
            }
        }
        for (Op mark : marks.all()) {
            addReferences(referenced, mark);
        }

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
                reclaimed += block.hasContent() ? block.length() : 0;
                continue;
            }
            if (block.hasContent()) {
                block.dropContent();
                reclaimed += block.length();
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

    private static void addReferences(Map<Integer, NavigableSet<Long>> referenced, Op op) {
        switch (op) {
            case Op.AddMark add -> {
                addReference(referenced, add.start().charId());
                addReference(referenced, add.end().charId());
            }
            case Op.RemoveMark remove -> {
                addReference(referenced, remove.start().charId());
                addReference(referenced, remove.end().charId());
            }
            case Op.Insert insert -> {
                if (insert.originLeft() != null) {
                    addReference(referenced, insert.originLeft());
                }
            }
            case Op.Delete delete -> {
                for (Op.Range range : delete.targets()) {
                    addReference(referenced, range.start());
                }
            }
        }
    }

    private static void addReference(Map<Integer, NavigableSet<Long>> referenced, Id id) {
        referenced.computeIfAbsent(id.replicaId(), replica -> new TreeSet<>()).add(id.lamport());
    }

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

    private static boolean isAnyCharReferenced(
        Block block, Map<Integer, NavigableSet<Long>> referenced) {
        NavigableSet<Long> lamports = referenced.get(block.start().replicaId());
        if (lamports == null) {
            return false;
        }
        long start = block.start().lamport();
        return !lamports.subSet(start, true, start + block.length(), false).isEmpty();
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
