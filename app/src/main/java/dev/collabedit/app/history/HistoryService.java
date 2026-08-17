package dev.collabedit.app.history;

import dev.collabedit.app.domain.DocumentService;
import dev.collabedit.app.persist.OperationStore;
import dev.collabedit.app.persist.SnapshotStore;
import dev.collabedit.app.persist.StoredOperation;
import dev.collabedit.crdt.Block;
import dev.collabedit.crdt.Id;
import dev.collabedit.crdt.MarkSet;
import dev.collabedit.crdt.MarkTypes;
import dev.collabedit.crdt.Op;
import dev.collabedit.crdt.RgaDocument;
import dev.collabedit.crdt.TreapBlockList;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class HistoryService {
    private static final Duration GROUPING_WINDOW = Duration.ofMinutes(2);

    private static final int TIMELINE_LIMIT = 200;

    private final OperationStore operations;
    private final SnapshotStore snapshots;

    public HistoryService(OperationStore operations, SnapshotStore snapshots) {
        this.operations = operations;
        this.snapshots = snapshots;
    }

    public record Version(
        long throughServerSeq,
        String userId,
        String displayName,
        String color,
        Instant at,
        int operationCount,
        int charactersInserted,
        int charactersDeleted
    ) {
    }

    public List<Version> timeline(UUID documentId) {
        return operations
            .timelineOf(documentId, GROUPING_WINDOW.toSeconds(), TIMELINE_LIMIT)
            .stream()
            .map(entry -> new Version(
                entry.throughServerSeq(), entry.userId(), entry.displayName(), entry.color(),
                entry.startedAt(), entry.operationCount(), entry.charactersInserted(),
                entry.charactersDeleted()))
                    .toList();
    }

    public String textAt(UUID documentId, long throughServerSeq) {
        return documentAt(documentId, throughServerSeq).text();
    }

    public record State(String text, List<MarkSet.Span> marks) {
    }

    public State stateAt(UUID documentId, long throughServerSeq) {
        RgaDocument document = documentAt(documentId, throughServerSeq);
        return new State(document.text(), document.formatting());
    }

    private RgaDocument documentAt(UUID documentId, long throughServerSeq) {
        var snapshot = snapshots.findLatestUpTo(documentId, throughServerSeq);

        RgaDocument document;
        long from;
        if (snapshot.isPresent()) {
            document = RgaDocument.restore(0, new TreapBlockList(), snapshot.get().snapshot());
            from = snapshot.get().upToServerSeq();
        } else {
            document = new RgaDocument(0, new TreapBlockList());
            from = 0;
        }

        for (StoredOperation stored : operations.loadRange(documentId, from, throughServerSeq)) {
            document.apply(stored.op());
        }
        return document;
    }

    public record Segment(String kind, String text) {
    }

    public List<Segment> diff(UUID documentId, long fromServerSeq, long toServerSeq) {
        List<Character> beforeText = new ArrayList<>();
        List<Id> beforeIds = new ArrayList<>();
        collectVisible(documentAt(documentId, fromServerSeq), beforeIds, beforeText);

        List<Character> afterText = new ArrayList<>();
        List<Id> afterIds = new ArrayList<>();
        collectVisible(documentAt(documentId, toServerSeq), afterIds, afterText);

        Map<Id, Integer> positionBefore = new HashMap<>();
        for (int i = 0; i < beforeIds.size(); i++) {
            positionBefore.put(beforeIds.get(i), i);
        }

        List<Segment> segments = new ArrayList<>();
        StringBuilder run = new StringBuilder();
        String runKind = null;
        int consumedBefore = 0;

        for (int i = 0; i < afterIds.size(); i++) {
            Integer match = positionBefore.get(afterIds.get(i));
            if (match != null && match >= consumedBefore) {
                if (match > consumedBefore) {
                    runKind = flush(segments, run, runKind, "removed");
                    for (int j = consumedBefore; j < match; j++) {
                        run.append(beforeText.get(j));
                    }
                    runKind = "removed";
                }
                runKind = flush(segments, run, runKind, "same");
                run.append(afterText.get(i));
                runKind = "same";
                consumedBefore = match + 1;
            } else {
                runKind = flush(segments, run, runKind, "added");
                run.append(afterText.get(i));
                runKind = "added";
            }
        }
        if (consumedBefore < beforeIds.size()) {
            runKind = flush(segments, run, runKind, "removed");
            for (int j = consumedBefore; j < beforeIds.size(); j++) {
                run.append(beforeText.get(j));
            }
            runKind = "removed";
        }
        flush(segments, run, runKind, null);
        return segments;
    }

    private static String flush(List<Segment> segments, StringBuilder run, String current,
                                String next) {
        if (current != null && !current.equals(next) && !run.isEmpty()) {
            segments.add(new Segment(current, run.toString()));
            run.setLength(0);
        }
        return next;
    }

    private static void collectVisible(RgaDocument document, List<Id> ids, List<Character> text) {
        for (Block block : document.blocks()) {
            if (block.deleted() || !block.hasContent()) {
                continue;
            }
            String content = block.text();
            for (int offset = 0; offset < content.length(); offset++) {
                ids.add(block.idAt(offset));
                text.add(content.charAt(offset));
            }
        }
    }

    public List<Op> restoreOperations(RgaDocument replica, State target) {
        List<Op> generated = new ArrayList<>(restoreText(replica, target.text()));
        generated.addAll(restoreMarks(replica, target.marks()));
        return generated;
    }

    private List<Op> restoreText(RgaDocument replica, String targetText) {
        String current = replica.text();
        if (current.equals(targetText)) {
            return List.of();
        }

        int prefix = 0;
        int maxPrefix = Math.min(current.length(), targetText.length());
        while (prefix < maxPrefix && current.charAt(prefix) == targetText.charAt(prefix)) {
            prefix++;
        }
        if (prefix > 0 && Character.isHighSurrogate(current.charAt(prefix - 1))) {
            prefix--;
        }

        int suffix = 0;
        int maxSuffix = Math.min(current.length() - prefix, targetText.length() - prefix);
        while (suffix < maxSuffix
            && current.charAt(current.length() - 1 - suffix)
            == targetText.charAt(targetText.length() - 1 - suffix)) {
            suffix++;
        }
        if (suffix > 0 && Character.isLowSurrogate(current.charAt(current.length() - suffix))) {
            suffix--;
        }

        List<Op> generated = new ArrayList<>();
        int removeFrom = prefix;
        int removeTo = current.length() - suffix;
        if (removeTo > removeFrom) {
            generated.add(replica.delete(removeFrom, removeTo - removeFrom));
        }
        String insert = targetText.substring(prefix, targetText.length() - suffix);
        int at = prefix;
        for (String piece : DocumentService.splitForInsert(insert)) {
            generated.add(replica.insert(at, piece));
            at += piece.length();
        }
        return generated;
    }

    private List<Op> restoreMarks(RgaDocument replica, List<MarkSet.Span> target) {
        List<MarkSet.Span> current = replica.formatting();
        int length = replica.visibleLength();
        if (length == 0 || (target.isEmpty() && current.isEmpty())) {
            return List.of();
        }

        Set<Integer> boundarySet = new HashSet<>();
        boundarySet.add(0);
        boundarySet.add(length);
        for (MarkSet.Span span : target) {
            boundarySet.add(Math.min(span.from(), length));
            boundarySet.add(Math.min(span.to(), length));
        }
        for (MarkSet.Span span : current) {
            boundarySet.add(Math.min(span.from(), length));
            boundarySet.add(Math.min(span.to(), length));
        }
        List<Integer> boundaries = new ArrayList<>(boundarySet);
        boundaries.sort(Integer::compare);

        List<Op> generated = new ArrayList<>();
        int index = 0;
        while (index < boundaries.size() - 1) {
            int from = boundaries.get(index);
            Map<String, String> want = marksAt(target, from);
            Map<String, String> have = marksAt(current, from);

            int end = boundaries.get(index + 1);
            int next = index + 1;
            while (next < boundaries.size() - 1
                && want.equals(marksAt(target, boundaries.get(next)))
                && have.equals(marksAt(current, boundaries.get(next)))) {
                end = boundaries.get(next + 1);
                next++;
            }
            index = next;

            if (from >= end) {
                continue;
            }
            for (Map.Entry<String, String> entry : have.entrySet()) {
                if (isConversation(entry.getKey())) {
                    continue;
                }
                if (!wants(want, entry.getKey(), entry.getValue())) {
                    generated.add(replica.removeMark(from, end, entry.getKey()));
                }
            }
            for (Map.Entry<String, String> entry : want.entrySet()) {
                if (isConversation(entry.getKey())) {
                    continue;
                }
                if (!wants(have, entry.getKey(), entry.getValue())) {
                    generated.add(replica.addMark(from, end, entry.getKey(), entry.getValue()));
                }
            }
        }
        return generated;
    }

    private static boolean wants(Map<String, String> marks, String type, String value) {
        return marks.containsKey(type) && Objects.equals(marks.get(type), value);
    }

    private static boolean isConversation(String markType) {
        return MarkTypes.COMMENT.equals(markType);
    }

    private static Map<String, String> marksAt(List<MarkSet.Span> spans, int position) {
        for (MarkSet.Span span : spans) {
            if (span.from() <= position && position < span.to()) {
                return new LinkedHashMap<>(span.marks());
            }
        }
        return Map.of();
    }
}
