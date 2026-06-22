package dev.collabedit.app.history;

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

/**
 * Change history and travelling back in time.
 *
 * <p>This whole feature is a free by-product of the decision in the operation-log decision: if the operation log is
 * the source of truth and is append-only, then <b>the history already exists</b> — it only has to
 * be read. There is no separate versioning mechanism here, no separate table and no extra write on
 * every change.
 *
 * <p>The price is on the read side: "the state at moment T" needs a snapshot plus a replay of the
 * operation tail. Stepping through the timeline one entry at a time multiplies that cost by the
 * number of steps — to be measured in CP8.
 */
@Service
public class HistoryService {

    /**
     * The window over which operations are grouped into one history entry.
     *
     * <p>The log has the granularity of a single operation, which in practice means a few
     * characters. A timeline with an entry per keystroke would be useless, so consecutive
     * operations by the same author are glued into one entry. Two minutes is the typical length of
     * one "thought" while writing — a value chosen from intuition rather than data, and described
     * that way in the README.
     */
    private static final Duration GROUPING_WINDOW = Duration.ofMinutes(2);

    /**
     * The upper bound on how many timeline entries one request returns.
     *
     * <p>There was none, and the CTE behind it scans every operation row of the document per
     * request — so on a long-lived document the panel asked for a list that grows for the life of
     * that document, forever. The same bound and the same reasoning as {@code LIST_LIMIT} in the
     * document list: the interface shows a timeline, not an archive. Pagination is the honest
     * answer at real scale and is listed as such; a bound is what stops the unbounded version
     * shipping in the meantime.
     */
    private static final int TIMELINE_LIMIT = 200;

    private final OperationStore operations;
    private final SnapshotStore snapshots;

    public HistoryService(OperationStore operations, SnapshotStore snapshots) {
        this.operations = operations;
        this.snapshots = snapshots;
    }

    /**
     * A timeline entry: a run of operations by one author within one time window.
     *
     * @param throughServerSeq the sequence number the log has to be replayed up to in order to see
     *                         this state
     */
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

    /**
     * The document's timeline, newest first.
     *
     * <h2>The grouping happens in the database, and it did not use to</h2>
     * This method used to read <b>every operation row of the document, payload included</b>, with
     * no limit and no pagination, and group them in Java. The payload is the largest column in
     * the table, so opening the history panel on a document with 100 000 operations sent the
     * entire operation log across the wire in order to produce a few dozen summary lines.
     *
     * <p>The previous version half-saw this: it read the character counts out of the JSON lazily
     * rather than deserialising an operation per row, with a comment explaining that building
     * thousands of objects "would be wasteful on a path that only needs lengths". It optimised
     * the parsing and paid for the transfer, which is the larger of the two costs.
     *
     * <p>Two changes together fix it. The counts are now columns, written when the operation is
     * (V6), so nothing needs the payload at all; and the sessionisation runs as a window function,
     * so what crosses the wire is <b>one row per timeline entry</b> rather than one per keystroke.
     *
     * <h2>The grouping rule is unchanged</h2>
     * A new entry starts when the author changes <b>or</b> when more than {@link #GROUPING_WINDOW}
     * passes since the <i>previous operation</i> — not since the start of the group. That is what
     * the Java version did, and {@code LAG} over {@code server_seq} expresses exactly the same
     * thing, which is why this could move without changing any test.
     *
     * <h2>The SQL now lives in persist/, and this method maps it</h2>
     * {@code OperationStore}'s javadoc calls itself the sole access path to the {@code operations}
     * table, and this service was holding its own {@code JdbcTemplate} and reading that table
     * directly — a boundary that only existed in a comment. The query moved; the grouping rule,
     * the window and the shape of the answer did not.
     */
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

    /**
     * The document's text at the moment the log reached the given sequence number.
     *
     * <p>The snapshot is purely a shortcut here: without it, replaying the whole log would give the
     * same result. That equivalence is tested explicitly, because without a test the cache would
     * quietly become the source of truth.
     */
    public String textAt(UUID documentId, long throughServerSeq) {
        return documentAt(documentId, throughServerSeq).text();
    }

    /**
     * The document at that moment — <b>text and formatting</b> (CP22).
     *
     * <p>Until CP22 this layer returned a {@code String}, which was right in CP5 and stopped being
     * right in CP13 without anybody noticing: a version whose only change was formatting looked
     * identical to the one before it, and restoring a version brought the text back while leaving
     * the appearance as it happened to be.
     *
     * <p>Comment anchors are marks too, and they are deliberately part of this — the preview shows
     * what the document looked like, and a highlighted fragment is part of that. What treats them
     * differently is {@link #restoreOperations}; the reason is written there.
     */
    public record State(String text, List<MarkSet.Span> marks) {
    }

    public State stateAt(UUID documentId, long throughServerSeq) {
        RgaDocument document = documentAt(documentId, throughServerSeq);
        return new State(document.text(), document.formatting());
    }

    /**
     * Rebuilds the document as of a sequence number.
     *
     * <p>The snapshot is purely a shortcut: without it, replaying the whole log would give the same
     * result. That equivalence is tested explicitly, because without a test the cache would quietly
     * become the source of truth.
     */
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

    // ------------------------------------------------------------------
    // Comparing two versions (CP22)
    // ------------------------------------------------------------------

    /**
     * One run of text in a comparison of two versions.
     *
     * @param kind {@code same}, {@code added} (present in the later version only) or
     *             {@code removed} (present in the earlier one only)
     */
    public record Segment(String kind, String text) {
    }

    /**
     * What changed between two moments — computed from <b>character identity</b>, not from a text
     * diff.
     *
     * <h2>Why this is the one thing a CRDT gives away for free</h2>
     * Every character here carries {@code (replicaId, lamport)} for the whole life of the document,
     * because that identity is what the merge algorithm is built on. Two states of one document are
     * the same append-only log stopped at two points, so any two characters present in both appear
     * in the <b>same relative order</b> in both. That is enough to compare them with a single
     * left-to-right walk and a hash lookup per character — no Myers, no heuristics, no
     * "similarity".
     *
     * <p>And it answers a question a text diff cannot. Delete a word and type the same word again:
     * a text diff sees nothing at all, because the strings match. The identifiers do not match, so
     * this sees a removal and an insertion — which is what actually happened, and what the person
     * asking "what did they change" wants to know.
     *
     * <p>The cost is two state rebuilds, exactly like two previews. There is no index for this and
     * there does not need to be: the comparison is O(n) once the states exist, and the states are
     * what the timeline already pays for.
     */
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
                // Everything skipped over in the earlier state is gone from the later one.
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

    /** Closes the current run when the kind changes; returns the kind now in progress. */
    private static String flush(List<Segment> segments, StringBuilder run, String current,
                                String next) {
        if (current != null && !current.equals(next) && !run.isEmpty()) {
            segments.add(new Segment(current, run.toString()));
            run.setLength(0);
        }
        return next;
    }

    /** The visible characters of a state, with the identifier each one carries. */
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

    /**
     * The operations needed to restore the state from a given moment.
     *
     * <p><b>A restore is a NEW CHANGE, not an undo of the log</b> (point 6). Truncating
     * the log would destroy three things at once: auditability (who restored, and when), the
     * consistency of connected clients (their replicas would still hold the removed operations) and
     * the "append-only table" invariant that write idempotence rests on.
     *
     * <p>The operations are generated on a replica holding the <b>current</b> state of the document
     * and bring it to the target state. To connected clients it looks like an ordinary edit —
     * because it is one.
     *
     * <h2>Formatting is restored; conversations are not touched (CP22)</h2>
     * Until CP22 this method took a {@code String} and restored the text alone, so rolling a
     * document back left it wearing whatever formatting happened to be current. It now brings the
     * marks back as well — <b>except comment anchors</b>.
     *
     * <p>That exception is a decision, not an oversight. Formatting is how the document looked at
     * that moment, so it belongs to the version. A comment is people talking, and this project
     * already ruled once that text deletion must not delete conversations (CP14: the thread
     * survives, marked as detached). Reconciling comment anchors here would mean a rollback
     * silently detaching every conversation started since — a bigger loss than the one the
     * rollback was asked for, and one nobody would connect to the button they pressed.
     *
     * @param replica a replica with the current state, on which we generate the change
     */
    public List<Op> restoreOperations(RgaDocument replica, State target) {
        List<Op> generated = new ArrayList<>(restoreText(replica, target.text()));
        // Marks second, and this order is required rather than tidy: mark operations address
        // ranges of the CURRENT text, so they have to be computed after the text is back.
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

        int suffix = 0;
        int maxSuffix = Math.min(current.length() - prefix, targetText.length() - prefix);
        while (suffix < maxSuffix
            && current.charAt(current.length() - 1 - suffix)
            == targetText.charAt(targetText.length() - 1 - suffix)) {
            suffix++;
        }

        // A common prefix and suffix instead of a full diff: a restore usually rolls back one
        // contiguous fragment, and an operation covering too wide a range is correct, merely less
        // economical. A full diff (Myers) would give smaller operations at the cost of complexity
        // that does not show in the result here.
        List<Op> generated = new ArrayList<>();
        int removeFrom = prefix;
        int removeTo = current.length() - suffix;
        if (removeTo > removeFrom) {
            generated.add(replica.delete(removeFrom, removeTo - removeFrom));
        }
        String insert = targetText.substring(prefix, targetText.length() - suffix);
        if (!insert.isEmpty()) {
            generated.add(replica.insert(prefix, insert));
        }
        return generated;
    }

    /**
     * Brings the formatting back to what it was, by adding and removing marks.
     *
     * <p>Not by re-applying the old operations: those are already in the log and in every replica,
     * and re-applying them would be a no-op by idempotence. Restoring the <i>state</i>
     * therefore means new operations that produce it — the same principle the text half of this
     * method follows, and the same one that makes a restore mergeable rather than special.
     *
     * <p>The comparison walks the boundaries of both span lists rather than the characters. A
     * per-character map would be simpler and would allocate one map per character of a hundred
     * thousand character document to answer a question with a handful of answers in it.
     */
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

            // Extend over the neighbouring intervals that need the same thing done to them, so a
            // paragraph that lost its bolding is one operation rather than one per span boundary.
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
                // containsKey BEFORE comparing values, and the value comparison is
                // Objects.equals. Both halves were bugs in the first version of this loop, and
                // both come from the same fact: a mark's value is null for every type that
                // carries no payload — bold, italic, underline, strike — and only `link` and
                // `comment` have one. Comparing with equals() threw on the commonest mark in
                // the project; treating null as absent then made "italic present, no value"
                // indistinguishable from "no italic", so a restore silently kept the formatting
                // it was asked to remove. The test that found the second one is
                // VersionComparisonIT.restoringBringsFormattingBack.
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

    /** Whether these marks contain exactly this type with exactly this value, null included. */
    private static boolean wants(Map<String, String> marks, String type, String value) {
        return marks.containsKey(type) && Objects.equals(marks.get(type), value);
    }

    /** A comment anchor is a mark, and a restore deliberately leaves it alone — see above. */
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
