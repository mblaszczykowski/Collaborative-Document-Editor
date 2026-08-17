package dev.collabedit.crdt;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

public final class MarkSet {
    private final Map<Id, Op> operations = new LinkedHashMap<>();

    public record Span(int from, int to, Map<String, String> marks) {
        public Span {
            marks = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(marks));
        }
    }

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

    static boolean anchorsResolvable(Op op, BlockList blocks) {
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

    private record Resolved(Op op, String type, String value, boolean adding, int from, int to) {
    }

    public record Placement(int from, int to) {
    }

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

    public List<Span> spans(BlockList blocks) {
        List<Resolved> resolved = resolveAll(blocks);
        if (resolved.isEmpty()) {
            return List.of();
        }

        TreeSet<Integer> boundaries = new TreeSet<>();
        for (Resolved entry : resolved) {
            boundaries.add(entry.from());
            boundaries.add(entry.to());
        }

        Map<Integer, List<Resolved>> startingAt = new HashMap<>();
        Map<Integer, List<Resolved>> endingAt = new HashMap<>();
        for (Resolved entry : resolved) {
            startingAt.computeIfAbsent(entry.from(), key -> new ArrayList<>()).add(entry);
            endingAt.computeIfAbsent(entry.to(), key -> new ArrayList<>()).add(entry);
        }

        Map<String, TreeSet<Resolved>> activeByType = new TreeMap<>();

        List<Span> spans = new ArrayList<>();
        List<Integer> points = new ArrayList<>(boundaries);
        for (int i = 0; i + 1 < points.size(); i++) {
            int from = points.get(i);
            int to = points.get(i + 1);

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

    private static final Comparator<Resolved> BY_PRIORITY_DESCENDING = Comparator
        .comparingLong((Resolved entry) -> entry.op().id().lamport())
        .thenComparingInt(entry -> entry.op().id().replicaId())
        .reversed();

    private static Map<String, String> winnersOf(Map<String, TreeSet<Resolved>> activeByType) {
        Map<String, String> marks = new LinkedHashMap<>();
        for (Map.Entry<String, TreeSet<Resolved>> entry : activeByType.entrySet()) {
            Resolved winner = entry.getValue().first();
            if (winner.adding()) {
                marks.put(winner.type(), winner.value());
            }
        }
        return marks;
    }

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
                continue;
            }
            resolved.add(new Resolved(op, type, value, adding, from, to));
        }
        return resolved;
    }

    private static Integer position(Anchor anchor, BlockList blocks) {
        BlockList.Locator locator = blocks.locateId(anchor.charId());
        if (locator == null) {
            return null;
        }
        Block block = blocks.get(locator.blockIndex());
        int base = blocks.visibleOffsetOf(locator.blockIndex());
        if (block.deleted()) {
            return base;
        }
        return anchor.side() == Anchor.Side.BEFORE
            ? base + locator.offset()
            : base + locator.offset() + 1;
    }
}
