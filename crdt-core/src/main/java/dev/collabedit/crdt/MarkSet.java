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
 *
 * <p><b>That paragraph is true of {@link #spans} and NOT of {@link #placementsOf}</b>, which is
 * still {@code O(additions × removals)} for one type — and the heading above implies the quadratic
 * path is gone from the file. It is not; it is gone from the path called on every keystroke. The
 * other one answers "where is thread X" for the comments and suggestions panels, so it runs per
 * request, over the marks of a single type, and the counts there are tens rather than thousands.
 * Left alone deliberately: making it asymptotically better needs an interval structure, which is
 * more machinery than the measurement justifies. Said out loud rather than left for a reader to
 * discover, because a heading claiming a complexity the file only mostly has is the same kind of
 * false comfort CP20 removed.
 */
public final class MarkSet {

    /**
     * The mark operations, keyed by their own identifier.
     *
     * <p>{@link LinkedHashMap} rather than a plain map, for repeatable iteration — the result does
     * not depend on it (the resolution order is explicit), but a test that fails one run in ten is
     * worse than one that fails every time.
     */
    private final Map<Id, Op> operations = new LinkedHashMap<>();

    /**
     * A resolved mark over a segment of visible text {@code [from, to)}.
     *
     * <p>The map is copied on construction. It used to be handed out as the very
     * {@link LinkedHashMap} the sweep built, so a caller could mutate a result the document had
     * just computed — and {@code spans} returned {@code List.of()} when there were no marks and a
     * live {@code ArrayList} otherwise, which is an API whose mutability depends on the answer.
     * Both are now immutable in both cases; the copy is per segment rather than per character and
     * does not show up next to the sweep itself.
     */
    public record Span(int from, int to, Map<String, String> marks) {
        public Span {
            // NOT Map.copyOf: a mark without a value — bold, italic — resolves to a null value,
            // and Map.copyOf rejects nulls with an NPE from inside ImmutableCollections. The
            // nullable value is deliberate (it is what distinguishes decoration from a link or a
            // comment thread), so the copy has to be one that tolerates it.
            marks = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(marks));
        }
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
    // Resolving the formatting
    // ------------------------------------------------------------------

    /** An operation with its range resolved into visible coordinates. */
    private record Resolved(Op op, String type, String value, boolean adding, int from, int to) {
    }

    /** Where one mark ends up, in visible coordinates. Half-open, like every range here. */
    public record Placement(int from, int to) {
    }

    /**
     * Where each <b>distinct value</b> of one mark type currently sits — ignoring which of them
     * wins the render.
     *
     * <h2>Why this exists next to {@link #spans}</h2>
     * {@link #spans} answers "what should this segment of text look like", and for that exactly one
     * value per type can win: text is either bold or it is not. For {@code bold} the value is
     * decoration and the question is the right one.
     *
     * <p>For {@code comment}, {@code suggest-insert} and {@code suggest-delete} the value is an
     * <b>identity</b>, and two of them can legitimately cover the same characters — two people
     * commenting on one sentence is the ordinary case, not the exotic one. Reading those through
     * {@code spans} loses every value but the newest, and the callers that did it answered two
     * questions wrongly:
     *
     * <ul>
     *   <li>a comment thread whose text was entirely present reported as <b>detached</b>, because
     *       a later overlapping thread had won its segments — and detached is the presentation
     *       reserved for a thread whose text was deleted;</li>
     *   <li>a suggested deletion resolved to the sub-range it happened to win, so accepting it
     *       deleted less than was proposed and still answered 204.</li>
     * </ul>
     *
     * <p>One sweep for the whole type rather than one per value, because the callers ask about
     * every open thread or proposal at once and {@link #resolveAll} is the expensive half.
     *
     * <p>A removal of the same type that outranks a mark and covers it <b>entirely</b> takes it
     * away — that is an editor deleting somebody's comment anchor, which should read as detached.
     * A partial removal leaves the mark in place: the conversation is still about that text.
     *
     * <h2>Why the answer is a union rather than one operation's range</h2>
     * One value can come from more than one surviving operation: a suggestion re-applied over a
     * range that grew, a comment anchor extended in a second gesture. Reporting only the
     * highest-priority of them narrows the answer, and the narrowing is not cosmetic in either
     * caller — accepting a suggested deletion would delete part of what was proposed and still
     * report success, which is the same failure this method was written to fix, one level down.
     *
     * <p>The union is min-from to max-to rather than a list of ranges, which matches what the
     * callers do with it (delete it, jump to it) and what the code this replaced produced. The
     * simplification it buys is worth stating precisely rather than as "in practice": a gap
     * between two operations of one identity is reported as covered, so accepting a suggestion
     * would delete across it.
     *
     * <p>That is unreachable today and only because of an invariant held ELSEWHERE:
     * {@code SuggestionService} emits exactly one {@code AddMark} per suggestion and the client
     * emits none, so one identity never has two operations to span. The invariant is not enforced
     * here and not enforced on the wire — {@code DocumentAccessInterceptor} restricts mark types
     * only for the {@code commenter} role, so a client with editor rights could send a second
     * {@code suggest-delete} carrying an existing identity. It buys no privilege it does not
     * already have (accepting a suggestion needs editor rights, and an editor can delete anything
     * directly), which is why this stays a documented limit rather than a per-value list of
     * ranges threaded through both callers. If that invariant ever moves, this is where it lands.
     *
     * <h2>What {@code RemoveMark} cannot express</h2>
     * A removal carries a type but no value, so "remove this comment's anchor" is really "remove
     * comment marks over this range". Two threads on exactly the same characters cannot be
     * detached one at a time — removing either detaches both. Fixing that means a value on
     * {@code RemoveMark}, which is a wire-format change; it is recorded here rather than pretended
     * away, because the alternative is a reader assuming an identity-aware removal exists.
     *
     * @param type marks with no value are skipped; there is no identity to key them on
     */
    public Map<String, Placement> placementsOf(BlockList blocks, String type) {
        List<Resolved> resolved = resolveAll(blocks);
        List<Resolved> additions = new ArrayList<>();
        List<Resolved> removals = new ArrayList<>();
        for (Resolved entry : resolved) {
            if (!entry.type().equals(type)) {
                continue;
            }
            if (!entry.adding()) {
                removals.add(entry);
            } else if (entry.value() != null) {
                additions.add(entry);
            }
        }

        Map<String, Placement> placements = new LinkedHashMap<>();
        for (Resolved mark : additions) {
            boolean removed = false;
            for (Resolved removal : removals) {
                if (removal.from() <= mark.from() && removal.to() >= mark.to()
                    && BY_PRIORITY_DESCENDING.compare(removal, mark) < 0) {
                    removed = true;
                    break;
                }
            }
            if (removed) {
                continue;
            }
            placements.merge(mark.value(), new Placement(mark.from(), mark.to()),
                (existing, added) -> new Placement(Math.min(existing.from(), added.from()),
                    Math.max(existing.to(), added.to())));
        }
        return placements;
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
            // Strictly increasing: `points` is drained from a TreeSet, so `from < to` always
            // holds and the guard that used to stand below the two updates was unreachable.
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

            Map<String, String> marks = winnersOf(activeByType);
            if (!marks.isEmpty()) {
                spans.add(new Span(from, to, marks));
            }
        }
        return List.copyOf(mergeAdjacent(spans));
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
                default -> throw new IllegalStateException("this is not a mark operation: " + op);
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
