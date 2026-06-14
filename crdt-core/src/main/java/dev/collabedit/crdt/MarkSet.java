package dev.collabedit.crdt;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Text formatting as a separate layer above the character sequence (CP13, the Peritext approach).
 *
 * <h2>The model</h2>
 * A mark is not a character attribute but a <b>range between two anchors</b> ({@link Anchor}). The
 * set of mark operations only grows and is never modified in place — removing formatting is another
 * operation, not the deletion of an earlier one. The result for any given character is computed
 * only at render time.
 *
 * <h2>Why a per-character attribute does not work</h2>
 * Ania bolds a range. Bartek, not yet seeing her change, types text into the middle of that range.
 * With per-character attributes the inserted text has nowhere to get the bold from: when it was
 * created Ania's operation had not reached Bartek, and when it does arrive it lists characters that
 * are not in it. The result — a hole in the bolding that nobody made.
 *
 * <p>A range between anchors does not have that problem, because the anchors move with the text:
 * text inserted into the middle of a range <b>is</b> in the middle of the range, regardless of the
 * order operations are delivered in.
 *
 * <h2>Conflict resolution</h2>
 * For a given character and a given mark type, the winner is the operation with the highest
 * {@code (lamport, replicaId)} among those covering that character. This is <b>the same total
 * order</b> RGA's insertion rule uses — with no new arbitration mechanism and no new assumption
 * about clocks. Convergence follows from the order being total and identical on every replica,
 * with the set of operations being the same.
 *
 * <h2>Complexity — a sweep, {@code O(m log m)} (CP20)</h2>
 * Range boundaries divide the text into {@code O(m)} segments. The previous version walked ALL the
 * operations for EVERY segment, making the whole thing {@code O(m²)} — and that was not a
 * theoretical problem, because {@link #spans} is called on every keystroke. CP15 measured it:
 * <b>0.6 ms at 100 marks, 5.3 ms at 1600</b> on a 50 000-character document, that is, visible
 * typing lag at a few hundred marks.
 *
 * <p>Now the segments are walked left to right with the set of active ranges maintained
 * incrementally: each range enters and leaves exactly once. The winner for a type is read off the
 * front of a sorted set, and there is a fixed handful of types ({@link MarkTypes}), so the
 * per-segment cost does not grow with the number of marks.
 */
public final class MarkSet {

    /**
     * Operacje znacznikowe, kluczowane identyfikatorem.
     *
     * <p>{@link LinkedHashMap} rather than a plain map, for repeatable iteration — the result does
     * not depend on it (the resolution order is explicit), but a test that fails one run in ten is
     * worse than one that fails every time.
     */
    private final Map<Id, Op> operations = new LinkedHashMap<>();

    /** A resolved mark over a segment of visible text {@code [from, to)}. */
    public record Span(int from, int to, Map<String, String> marks) {
    }

    /**
     * Adds a mark operation.
     *
     * @return {@code true} when the operation was new to this replica
     */
    public boolean apply(Op op) {
        if (!(op instanceof Op.AddMark) && !(op instanceof Op.RemoveMark)) {
            throw new IllegalArgumentException("this is not a mark operation: " + op);
        }
        return operations.putIfAbsent(op.id(), op) == null;
    }

    public boolean contains(Id id) {
        return operations.containsKey(id);
    }

    public int size() {
        return operations.size();
    }

    public List<Op> all() {
        return List.copyOf(operations.values());
    }

    /**
     * Whether both of the operation's anchors can be located in the structure.
     *
     * <p>A mark whose anchor has not yet arrived has to wait in the buffer — exactly like an
     * insertion that outran its origin. Applying it immediately would give a range pinned to
     * characters that do not exist, that is, formatting in a random place.
     */
    public static boolean anchorsResolvable(Op op, BlockList blocks) {
        return switch (op) {
            case Op.AddMark mark ->
                blocks.locateId(mark.start().charId()) != null
                    && blocks.locateId(mark.end().charId()) != null;
            case Op.RemoveMark mark ->
                blocks.locateId(mark.start().charId()) != null
                    && blocks.locateId(mark.end().charId()) != null;
            default -> true;
        };
    }

    // ------------------------------------------------------------------
    // Wyliczanie formatowania
    // ------------------------------------------------------------------

    /** An operation with its range resolved into visible coordinates. */
    private record Resolved(Op op, String type, String value, boolean adding, int from, int to) {
    }

    /**
     * The formatting of the visible text as a sequence of segments.
     *
     * <p>The segments are disjoint, cover the text in order, and <b>contain no unformatted
     * segments</b> — the view layer receives only what it has to draw.
     */
    public List<Span> spans(BlockList blocks) {
        List<Resolved> resolved = resolveAll(blocks);
        if (resolved.isEmpty()) {
            return List.of();
        }

        // Segment boundaries: the start and end of every range. Between two consecutive
        // boundaries the formatting is constant by definition, because no range begins or ends
        // there.
        TreeSet<Integer> boundaries = new TreeSet<>();
        for (Resolved entry : resolved) {
            boundaries.add(entry.from());
            boundaries.add(entry.to());
        }

        // The ranges starting and ending at a given boundary — so the sweep does not have to
        // walk every operation at every boundary.
        Map<Integer, List<Resolved>> startingAt = new HashMap<>();
        Map<Integer, List<Resolved>> endingAt = new HashMap<>();
        for (Resolved entry : resolved) {
            startingAt.computeIfAbsent(entry.from(), key -> new ArrayList<>()).add(entry);
            endingAt.computeIfAbsent(entry.to(), key -> new ArrayList<>()).add(entry);
        }

        // Active ranges per type, ordered DESCENDING by (lamport, replicaId). The first element
        // is the winner for that type on the current segment — the same total order as RGA
        // insertion, so no new arbiter appears here.
        //
        // TreeMap rather than HashMap: the order of types in the result should be repeatable. The
        // result does not depend on it, but a test that fails one run in ten is worse than one
        // that fails every time.
        Map<String, TreeSet<Resolved>> activeByType = new TreeMap<>();

        List<Span> spans = new ArrayList<>();
        List<Integer> points = new ArrayList<>(boundaries);
        for (int i = 0; i + 1 < points.size(); i++) {
            int from = points.get(i);
            int to = points.get(i + 1);

            // Remove the ranges ending at this boundary first, then add the ones starting here.
            // The reverse order would briefly have ranges [a,b) and [b,c) of the same type active
            // at the same time.
            for (Resolved ending : endingAt.getOrDefault(from, List.of())) {
                TreeSet<Resolved> active = activeByType.get(ending.type());
                if (active != null) {
                    active.remove(ending);
                    if (active.isEmpty()) {
                        activeByType.remove(ending.type());
                    }
                }
            }
            for (Resolved starting : startingAt.getOrDefault(from, List.of())) {
                activeByType
                    .computeIfAbsent(starting.type(), key -> new TreeSet<>(BY_PRIORITY_DESCENDING))
                    .add(starting);
            }

            if (from >= to) {
                continue;
            }
            Map<String, String> marks = winnersOf(activeByType);
            if (!marks.isEmpty()) {
                spans.add(new Span(from, to, marks));
            }
        }
        return mergeAdjacent(spans);
    }

    /**
     * The resolution order: descending by {@code (lamport, replicaId)}.
     *
     * <p>The same total order RGA's insertion rule uses. It is strict — two different operations
     * cannot share an identifier — so {@link TreeSet} will never treat two different ranges as the
     * same element.
     */
    private static final Comparator<Resolved> BY_PRIORITY_DESCENDING = Comparator
        .comparingLong((Resolved entry) -> entry.op().id().lamport())
        .thenComparingInt(entry -> entry.op().id().replicaId())
        .reversed();

    /**
     * The winners per type among the ranges active on the current segment.
     *
     * <p>The previous version walked every operation at EVERY segment, which made it
     * {@code O(m²)} — measured in CP15: 0.6 ms at 100 marks and 5.3 ms at 1600 on a
     * 50 000-character document, with {@code spans()} being called on every keystroke. The sweep
     * adds and removes each range exactly once, so the whole thing costs {@code O(m log m)}, and
     * this method looks only at the front of each type.
     *
     * <p>There is a fixed handful of types ({@link MarkTypes}), so this loop does not grow with
     * the number of marks — and that is the entire difference from the previous version.
     */
    private static Map<String, String> winnersOf(Map<String, TreeSet<Resolved>> activeByType) {
        Map<String, String> marks = new LinkedHashMap<>();
        for (Map.Entry<String, TreeSet<Resolved>> entry : activeByType.entrySet()) {
            Resolved winner = entry.getValue().first();
            // A removal wins exactly as an application does — it simply contributes nothing to
            // the result. Skipping this distinction would let an older bold "show through" a
            // newer unbold.
            if (winner.adding()) {
                marks.put(winner.type(), winner.value());
            }
        }
        return marks;
    }

    /**
     * Merges adjacent segments with identical formatting.
     *
     * <p>The boundaries come from ALL ranges, so two bolds meeting end to end would produce two
     * segments with the same content. The view layer would cope, but two adjacent identical
     * decoration ranges are two DOM elements where one would do — and when rendering on every
     * keystroke that is a difference.
     */
    private static List<Span> mergeAdjacent(List<Span> spans) {
        if (spans.size() < 2) {
            return spans;
        }
        List<Span> merged = new ArrayList<>();
        Span current = spans.getFirst();
        for (int i = 1; i < spans.size(); i++) {
            Span next = spans.get(i);
            if (next.from() == current.to() && next.marks().equals(current.marks())) {
                current = new Span(current.from(), next.to(), current.marks());
            } else {
                merged.add(current);
                current = next;
            }
        }
        merged.add(current);
        return merged;
    }

    private List<Resolved> resolveAll(BlockList blocks) {
        List<Resolved> resolved = new ArrayList<>(operations.size());
        for (Op op : operations.values()) {
            Anchor start;
            Anchor end;
            String type;
            String value;
            boolean adding;
            switch (op) {
                case Op.AddMark mark -> {
                    start = mark.start();
                    end = mark.end();
                    type = mark.type();
                    value = mark.value();
                    adding = true;
                }
                case Op.RemoveMark mark -> {
                    start = mark.start();
                    end = mark.end();
                    type = mark.type();
                    value = null;
                    adding = false;
                }
                default -> throw new IllegalStateException("nieznacznikowa operacja w zbiorze");
            }

            Integer from = position(start, blocks);
            Integer to = position(end, blocks);
            if (from == null || to == null || from >= to) {
                // The anchor is outside the structure (removed by GC, or the operation outran
                // its cause), or the range is empty after everything in it was deleted. The mark
                // simply covers nothing — this is not an error.
                continue;
            }
            resolved.add(new Resolved(op, type, value, adding, from, to));
        }
        return resolved;
    }

    /**
     * The anchor's position in visible coordinates.
     *
     * <p>An anchor attached to a tombstone does not disappear — it points at the gap between the
     * visible characters surrounding it. That is what makes deleting formatted text and typing new
     * text in the same place preserve the formatting rather than lose it.
     *
     * @return the position, or {@code null} when the character is not in the structure
     */
    private static Integer position(Anchor anchor, BlockList blocks) {
        BlockList.Locator locator = blocks.locateId(anchor.charId());
        if (locator == null) {
            return null;
        }
        Block block = blocks.get(locator.blockIndex());
        int base = blocks.visibleOffsetOf(locator.blockIndex());
        if (block.deleted()) {
            // A tombstone takes no space in the visible text — both sides of the anchor point at
            // the same gap.
            return base;
        }
        return anchor.side() == Anchor.Side.BEFORE
            ? base + locator.offset()
            : base + locator.offset() + 1;
    }
}
