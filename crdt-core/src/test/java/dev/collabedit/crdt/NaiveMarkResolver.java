package dev.collabedit.crdt;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

final class NaiveMarkResolver {
    private NaiveMarkResolver() {
    }

    static List<MarkSet.Span> spans(RgaDocument document) {
        Positions positions = Positions.of(document.blockList());
        List<Resolved> resolved = new ArrayList<>();
        for (Op mark : document.snapshot().marks()) {
            Resolved entry = resolve(mark, positions);
            if (entry != null) {
                resolved.add(entry);
            }
        }
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
            return null;
        }
        return new Resolved(mark.id(), type, value, adding, from, to);
    }

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
