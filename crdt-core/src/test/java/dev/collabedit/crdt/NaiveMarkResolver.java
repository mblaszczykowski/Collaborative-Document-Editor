package dev.collabedit.crdt;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A deliberately stupid reference implementation of the mark layer's resolution.
 *
 * <h2>Why this file exists</h2>
 * {@code MarkConvergenceProperties} compared the replicas against <b>each other</b> and nothing
 * else. That is the "every replica wrong in the same way" hole the text side has defended against
 * since CP2 — with a {@code StringBuilder} reference model and a structural invariant — and the
 * mark layer had neither. Measured, not assumed: replacing the body of
 * {@link MarkSet#spans(BlockList)} with {@code return List.of;} left all four mark properties
 * green, because {@code allFormattingConverged} is vacuously true when every replica returns
 * nothing and {@code spansAreDisjointAndOrdered} loops over spans and therefore asserts nothing
 * when there are none. Un-reversing the priority comparator — so the OLDEST mark wins instead of
 * the newest — produced zero failures for the same reason.
 *
 * <p>So the whole rich-text layer (CP13, the mark layer) and everything built on it — comments,
 * suggestions, block structure — was covered at the property level by an assertion that could not
 * fail.
 *
 * <h2>The relationship to MarkSet is the one ArrayBlockList has to TreapBlockList</h2>
 * {@link MarkSet#spans(BlockList)} is a sweep with per-type ordered sets, rewritten in CP20 for
 * complexity reasons. This is the version that walks every visible position and asks every mark
 * whether it covers it: O(positions x marks), obviously correct, and impossible to get subtly
 * wrong in the same way an optimised sweep can be. The project already made exactly this argument
 * once — "first the obviously correct version, then numbers showing it is too slow, and only then
 * the optimisation, with the same property suite on both" — and then optimised the mark layer
 * without ever writing the naive half.
 *
 * <p>It resolves anchors independently too, by building an identifier-to-position table from the
 * blocks rather than by calling anything in {@code MarkSet}. A shared anchor resolver would make
 * the two agree about a wrong answer, which is the failure being guarded against.
 */
final class NaiveMarkResolver {

    private NaiveMarkResolver() {
    }

    /**
     * The formatting of the document's visible text, computed the slow way.
     *
     * <p>Reads the mark operations out of a snapshot rather than from {@code MarkSet}, so the two
     * implementations share no state beyond the document itself.
     */
    static List<MarkSet.Span> spans(RgaDocument document) {
        Positions positions = Positions.of(document.blockList());
        List<Resolved> resolved = new ArrayList<>();
        for (Op mark : document.snapshot().marks()) {
            Resolved entry = resolve(mark, positions);
            if (entry != null) {
                resolved.add(entry);
            }
        }
        // Newest first, under Id's total order — the same rule RGA uses to order siblings, which
        // is the point: no second arbiter appears in the mark layer.
        resolved.sort((a, b) -> b.id.compareTo(a.id));

        int length = document.visibleLength();
        List<Map<String, String>> perPosition = new ArrayList<>(length);
        for (int position = 0; position < length; position++) {
            Map<String, String> winners = new TreeMap<>();
            Map<String, Boolean> decided = new HashMap<>();
            for (Resolved entry : resolved) {
                if (position < entry.from || position >= entry.to) {
                    continue;
                }
                if (decided.putIfAbsent(entry.type, Boolean.TRUE) != null) {
                    continue;
                }
                if (entry.adding) {
                    winners.put(entry.type, entry.value);
                }
            }
            perPosition.add(winners);
        }
        return group(perPosition);
    }

    /** Consecutive positions carrying identical formatting become one span; empty ones are dropped. */
    private static List<MarkSet.Span> group(List<Map<String, String>> perPosition) {
        List<MarkSet.Span> spans = new ArrayList<>();
        int start = 0;
        while (start < perPosition.size()) {
            Map<String, String> here = perPosition.get(start);
            int end = start + 1;
            while (end < perPosition.size() && perPosition.get(end).equals(here)) {
                end++;
            }
            if (!here.isEmpty()) {
                spans.add(new MarkSet.Span(start, end, new LinkedHashMap<>(here)));
            }
            start = end;
        }
        return spans;
    }

    private record Resolved(Id id, String type, String value, boolean adding, int from, int to) {
    }

    private static Resolved resolve(Op mark, Positions positions) {
        Anchor start;
        Anchor end;
        String type;
        String value;
        boolean adding;
        switch (mark) {
            case Op.AddMark add -> {
                start = add.start();
                end = add.end();
                type = add.type();
                value = add.value();
                adding = true;
            }
            case Op.RemoveMark remove -> {
                start = remove.start();
                end = remove.end();
                type = remove.type();
                value = null;
                adding = false;
            }
            default -> {
                return null;
            }
        }
        Integer from = positions.of(start);
        Integer to = positions.of(end);
        if (from == null || to == null || from >= to) {
            // Unresolvable or empty after its content was deleted. Covering nothing is not an
            // error — RgaDocument.requireNonEmptyRange refuses to CREATE such a range, but one
            // arriving from another replica is accepted deliberately, because refusing it would
            // make two replicas hold different sets of operations.
            return null;
        }
        return new Resolved(mark.id(), type, value, adding, from, to);
    }

    /**
     * Where every character sits in the visible text.
     *
     * <p>A tombstone maps to the position of the gap it leaves, which is what makes formatting
     * survive the deletion of the text it was applied to — the same rule
     * {@code MarkSet.anchorPosition} implements, arrived at from the block list rather than
     * through it.
     */
    private record Positions(Map<Id, Integer> before, Map<Id, Integer> after) {

        static Positions of(BlockList blocks) {
            Map<Id, Integer> before = new HashMap<>();
            Map<Id, Integer> after = new HashMap<>();
            int seen = 0;
            for (Block block : blocks) {
                for (int offset = 0; offset < block.length(); offset++) {
                    Id id = block.idAt(offset);
                    if (block.deleted()) {
                        before.put(id, seen);
                        after.put(id, seen);
                    } else {
                        before.put(id, seen + offset);
                        after.put(id, seen + offset + 1);
                    }
                }
                seen += block.visibleLength();
            }
            return new Positions(before, after);
        }

        Integer of(Anchor anchor) {
            return anchor.side() == Anchor.Side.BEFORE
                ? before.get(anchor.charId())
: after.get(anchor.charId());
        }
    }
}
