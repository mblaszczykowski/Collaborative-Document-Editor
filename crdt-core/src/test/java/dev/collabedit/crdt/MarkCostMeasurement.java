package dev.collabedit.crdt;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the mark layer actually costs (CP15).
 *
 * <p>Plan v2, point 5.5, assumed the anchors might block the garbage collector: if every mark pins
 * two tombstones and protects them from removal, a document with thousands of comments will stop
 * giving memory back. That is a fear <b>stated before the implementation</b>, and CP15 is to
 * confirm or refute it with a number rather than an adjective.
 *
 * <p>This is not a performance test and it <b>does not measure time</b>. Time on a machine shared
 * with Postgres, Redis and a browser means nothing; a byte count and a count of reclaimed
 * tombstones mean the same regardless of load. The assertions are deliberately loose — they are
 * meant to catch a change of <b>an order of magnitude</b>, not to pin down an exact number that
 * will move with the first correction to the wire format.
 *
 * <p>The results go into the README (the "cost of the mark layer" section). When they diverge, fix
 * the README, not the assertion.
 */
class MarkCostMeasurement {

    private static final int TEXT_LENGTH = 50_000;

    /** How many marks a "dense" document carries — roughly one per paragraph. */
    private static final int DENSE_MARKS = 500;

    private static RgaDocument document() {
        return new RgaDocument(1, new TreapBlockList());
    }

    /**
     * Text that compresses like prose rather than like a test fixture.
     *
     * <h2>Why {@code "x".repeat(n)} was the wrong input, and what it cost</h2>
     * The snapshot sizes here used to be measured on fifty thousand copies of the letter x. Gzip
     * turns that into <b>83 bytes</b>. So the absolute figures published from this file
     * ("107 B -&gt; 108 B at 50 000 characters") described the compressibility of the input rather
     * than any property of the format, and the README quoted them as the latter.
     *
     * <p>The README condemns exactly this substitution three hundred lines further down, about a
     * different measurement: <i>"the measurement uses prose rather than `x` repeated, and that
     * distinction is the difference between a measurement and a compliment — gzip crushes a
     * repeated character and would have reported 259x."</i> The lesson was learned once and not
     * carried across the file boundary.
     *
     * <p>The DELTAS this file asserts on — bytes per mark — were never wrong, because a difference
     * between two snapshots of the same text cancels the text out. What was wrong was the baseline
     * printed beside them.
     *
     * <h2>And a small vocabulary is barely better than one letter</h2>
     * The first attempt at this cycled through thirty-four words and produced <b>706 B</b> from
     * 50 000 characters — a ratio of 71:1, where real English gzips at roughly 3:1. A short
     * repeating dictionary sits entirely inside the compressor's window, so it is the same mistake
     * one step less obvious.
     *
     * <p>Hence a larger vocabulary in a deterministic pseudo-random order. It still does not
     * compress exactly like a real document, and the printed ratio says so — a number that carries
     * its own caveat is quotable; one that needs a footnote somebody else has to remember is not.
     *
     * <p>Deterministic rather than random, for the reason every seed in this project is: a
     * measurement that changes between runs cannot be compared with the one in the README.
     */
    private static String prose(int length) {
        String[] words = {
            "document", "converges", "without", "arbiter", "because", "replica", "applies",
            "operation", "order", "reaches", "state", "architecture", "safe", "usually",
            "working", "under", "load", "several", "authors", "tombstone", "causal", "stability",
            "boundary", "snapshot", "cache", "disposable", "rebuild", "log", "broadcast",
            "unreliable", "anti", "entropy", "sweep", "interval", "divergence", "repair",
            "lamport", "clock", "timestamp", "identifier", "collision", "silent", "anchor",
            "formatting", "range", "expands", "typing", "comment", "thread", "suggestion",
            "proposal", "accepted", "rejected", "permission", "revoked", "session", "subscribe",
            "frame", "refused", "budget", "chunk", "resynchronise", "cursor", "presence",
            "heartbeat", "reconnect", "buffer", "acknowledged", "durable", "instance", "cluster",
            "partition", "convergent", "commutative", "idempotent", "invariant", "property",
            "counterexample", "shrunk", "mutation", "measurement", "regression", "checkpoint",
        };
        // splitmix64, the same generator TreapBlockList uses for its priorities — deterministic,
        // and without the low-bit correlation that made `new Random(seed).nextDouble()` return
        // nearly the same value for seeds 0..9 and turn a smooth curve into a fake cliff
        // (InterleavingMeasurement records that one).
        long state = 0x9E3779B97F4A7C15L;
        StringBuilder text = new StringBuilder(length + 16);
        int since = 0;
        while (text.length() < length) {
            state += 0x9E3779B97F4A7C15L;
            long z = state;
            z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
            z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
            z = z ^ (z >>> 31);
            text.append(words[(int) Math.floorMod(z, words.length)]).append(' ');
            if (++since >= 12) {
                text.append(". ");
                since = 0;
            }
        }
        return text.substring(0, length);
    }

    private static int snapshotBytes(RgaDocument document) {
        return SnapshotCodec.encode(document.snapshot()).length;
    }

    @Test
    @DisplayName("marks cost what they weigh in a snapshot — they do not duplicate the text")
    void marksCostProportionallyToTheirCount() {
        RgaDocument plain = document();
        plain.insert(0, prose(TEXT_LENGTH));
        int withoutMarks = snapshotBytes(plain);

        RgaDocument marked = document();
        marked.insert(0, prose(TEXT_LENGTH));
        for (int i = 0; i < DENSE_MARKS; i++) {
            int from = i * (TEXT_LENGTH / DENSE_MARKS);
            marked.addMark(from, from + 20, MarkTypes.BOLD, null);
        }
        int withMarks = snapshotBytes(marked);

        int perMark = (withMarks - withoutMarks) / DENSE_MARKS;
        System.out.printf(
            "%nMARK COST IN A SNAPSHOT%n"
                + "  text: %,d characters of synthetic prose, gzipped %.1f:1%n"
                + "        (the ratio is printed because the baseline below is a property of the%n"
                + "         TEXT as much as of the format — real English gzips at roughly 3:1,%n"
                + "         and \"x\" repeated 50 000 times at 600:1)%n"
                + "  snapshot without marks:  %,d B%n"
                + "  the same text, %d marks: %,d B%n"
                + "  overhead per mark:       %d B%n",
            TEXT_LENGTH, (double) TEXT_LENGTH / withoutMarks,
            withoutMarks, DENSE_MARKS, withMarks, perMark);

        // A mark is two anchors (replica + lamport + side) plus a type and a value. Hundreds of
        // bytes would mean the encoder is writing something more — for example copying the covered
        // text, which is exactly the mistake Peritext exists to prevent.
        assertThat(perMark)
            .as("a mark weighs as much as a fragment of text in the snapshot — the encoder duplicates something")
            .isLessThan(120);
    }

    /**
     * Median of eleven calls, after a warm-up.
     *
     * <p>An earlier version timed a single call and reported 0.8 ms one run and 4.7 ms the next
     * for the same 400 marks on the same machine and the same code. A five-fold spread makes a
     * before/after comparison meaningless, and a before/after comparison is the only thing this
     * table is for.
     */
    private static long medianResolveNanos(RgaDocument document) {
        // Warm-up: the first calls pay for JIT compilation and for filling the structures, so
        // timing them would describe the JVM rather than the algorithm.
        for (int i = 0; i < 20; i++) {
            document.formatting();
        }
        long[] samples = new long[11];
        for (int sample = 0; sample < samples.length; sample++) {
            long began = System.nanoTime();
            document.formatting();
            samples[sample] = System.nanoTime() - began;
        }
        java.util.Arrays.sort(samples);
        return samples[samples.length / 2];
    }

    /**
     * Lays {@code markCount} ranges over the text <b>without letting them overlap</b>.
     *
     * <p>The width is capped at one less than the stride, and that cap is the whole point of
     * this method. The previous version used a fixed width of 30 characters at every mark
     * count, which is fine while the stride is larger than 30 — and at 6400 marks over 50 000
     * characters the stride is 7. The ranges then overlapped four to five deep, and since they
     * all carried the same type and the same value, {@code mergeAdjacent} collapsed the entire
     * document into <b>one</b> span.
     *
     * <p>So the last row of the table measured a different shape of problem from the three
     * above it (1600 ranges → 1600 spans, 6400 ranges → 1 span) while being labelled as a plain
     * ×4 in mark count. That row was added in CP20 specifically to make the O(m²) → O(m log m)
     * difference visible, which made it the one row that had to be comparable and the one row
     * that was not.
     */
    private static RgaDocument withDisjointMarks(int markCount) {
        RgaDocument document = document();
        document.insert(0, "x".repeat(TEXT_LENGTH));
        int stride = TEXT_LENGTH / markCount;
        int width = Math.min(30, stride - 1);
        for (int i = 0; i < markCount; i++) {
            int from = i * stride;
            document.addMark(from, from + width, MarkTypes.BOLD, null);
        }
        return document;
    }

    @Test
    @DisplayName("resolving formatting scales with the mark count, geometry held constant")
    void resolvingFormattingScalesWithMarkCount() {
        System.out.printf("%nCOST OF RESOLVING FORMATTING (%,d characters, disjoint ranges)%n"
            + "  marks    spans   median resolve      growth%n", TEXT_LENGTH);

        long previous = 0;
        int previousCount = 0;
        for (int markCount : new int[] {100, 400, 1_600, 6_400}) {
            RgaDocument document = withDisjointMarks(markCount);
            int spans = document.formatting().size();
            long elapsed = medianResolveNanos(document);

            System.out.printf("  %6d   %6d   %,12d ns%s%n", markCount, spans, elapsed,
                previous == 0 ? "" : String.format("   x%.1f at x%d marks",
                    (double) elapsed / previous, markCount / previousCount));
            previous = elapsed;
            previousCount = markCount;

            // Every count must produce one span per mark. This is the assertion that keeps the
            // table honest: if a future change makes the ranges overlap again, the series stops
            // comparing like with like, and it should fail here rather than be read as a
            // complexity regression.
            assertThat(spans)
                .as("ranges are meant to stay disjoint at %d marks, so spans should equal marks; "
                    + "%d spans means the geometry changed and the row is not comparable "
                    + "with the others", markCount, spans)
                .isEqualTo(markCount);

            // The only timing assertion: resolving has to TERMINATE. Not an assertion on
            // milliseconds — on a machine shared with Postgres, Redis and a browser, time means
            // nothing and the test would be randomly red. This bound catches an order-of-
            // magnitude regression, not noise.
            assertThat(elapsed)
                .as("resolving formatting with %d marks took over a second", markCount)
                .isLessThan(1_000_000_000L);
        }
    }

    /**
     * The cost of <b>overlap depth</b>, which is a separate variable from the mark count.
     *
     * <p>Split out of the table above rather than mixed into it. Depth drives a different part
     * of the sweep: it is the size of the {@code TreeSet} of active ranges that
     * {@code winnersOf} reads the front of, whereas the mark count drives how many boundaries
     * there are to walk. Holding the mark count fixed at 1600 and varying only the width shows
     * the second cost without the first moving underneath it.
     *
     * <p>Note what happens to the span count as depth rises: identical marks stacked on the
     * same text resolve to one winner, so {@code mergeAdjacent} folds the result back down.
     * More work in, less output out — which is exactly why measuring this together with the
     * mark count produced a number that could not be interpreted.
     */
    @Test
    @DisplayName("overlap depth is a second, separate cost from the number of marks")
    void overlapDepthIsMeasuredSeparately() {
        final int markCount = 1_600;
        int stride = TEXT_LENGTH / markCount;

        System.out.printf("%nCOST OF OVERLAP DEPTH (%,d characters, %d marks held constant)%n"
            + "  width   approx depth   spans   median resolve%n", TEXT_LENGTH, markCount);

        for (int width : new int[] {stride - 1, stride * 2, stride * 4, stride * 8}) {
            RgaDocument document = document();
            document.insert(0, "x".repeat(TEXT_LENGTH));
            for (int i = 0; i < markCount; i++) {
                int from = i * stride;
                document.addMark(from, Math.min(from + width, TEXT_LENGTH), MarkTypes.BOLD, null);
            }

            int spans = document.formatting().size();
            long elapsed = medianResolveNanos(document);
            System.out.printf("  %5d   %12d   %5d   %,12d ns%n",
                width, Math.max(1, width / stride), spans, elapsed);

            assertThat(elapsed)
                .as("resolving formatting at overlap width %d took over a second", width)
                .isLessThan(1_000_000_000L);
        }
    }

    private static final int CHARS = 1_000;
    private static final int MARKS = 100;

    /** A document written in ONE run — the whole text fits in a single run-length block. */
    private static RgaDocument writtenInOneRun() {
        RgaDocument document = document();
        document.insert(0, "x".repeat(CHARS));
        return document;
    }

    /**
     * The same text typed so that every character ends up in a separate block.
     *
     * <p>Inserting at position 0 cannot be run-length merged: every new character anchors to the
     * start of the document rather than to its predecessor. The opposite extreme from
     * `writtenInOneRun`, and that is the entire point of the pair — <b>an anchor's cost depends on
     * how the text was created</b>.
     */
    private static RgaDocument writtenCharByChar() {
        RgaDocument document = document();
        for (int i = 0; i < CHARS; i++) {
            document.insert(0, "x");
        }
        return document;
    }

    private static int collectAfterDeletingEverything(RgaDocument document, int markCount) {
        for (int i = 0; i < markCount; i++) {
            int from = i * (CHARS / Math.max(markCount, 1));
            document.addMark(from, from + 5, MarkTypes.LINK, "https://example.test");
        }
        document.delete(0, CHARS);
        return document.gc(VersionVector.of(Map.of(1, 1_000_000L)));
    }

    @Test
    @DisplayName("after CP18 an anchor no longer blocks memory — the block gives up its content and keeps its identity")
    void anchorsNoLongerBlockReclamation() {
        int oneRunWithout = collectAfterDeletingEverything(writtenInOneRun(), 0);
        int oneRunWith = collectAfterDeletingEverything(writtenInOneRun(), MARKS);
        int perCharWithout = collectAfterDeletingEverything(writtenCharByChar(), 0);
        int perCharWith = collectAfterDeletingEverything(writtenCharByChar(), MARKS);

        System.out.printf(
            "%nEFFECT OF ANCHORS ON GC (%d chars, all deleted, %d marks = %d anchors)%n"
                + "  how the text was written    without marks     with marks    reclaimed%n"
                + "  in one run (1 block)        %10d      %10d      %6.0f%%%n"
                + "  char by char (%d blocks)  %10d      %10d      %6.0f%%%n",
            CHARS, MARKS, 2 * MARKS,
            oneRunWithout, oneRunWith, 100.0 * oneRunWith / oneRunWithout,
            CHARS, perCharWithout, perCharWith, 100.0 * perCharWith / perCharWithout);

        // CP15 MEASURED THIS AS ZERO. This assertion is that assertion inverted, and that is the
        // entire content of CP18.
        //
        // Previously an anchor blocked the WHOLE run-length block: GC works on blocks, and a block
        // in which even one character is an anchor stayed whole. Text written in one run is one
        // block, so a single anchor rescued the entire document — 0% reclaimed.
        //
        // Now a block that must not be removed GIVES UP ITS CONTENT while keeping its identity,
        // length and position. All three reasons for keeping a tombstone (origin, anchor, causal
        // stability) need the character's identifier rather than its content — so nothing breaks
        // and the memory comes back.
        assertThat(oneRunWith)
            .as("a run-length block with an anchor blocks memory again — a CP18 regression")
            .isEqualTo(oneRunWithout);

        // The other extreme must be unchanged: there GC could already remove whole blocks, so
        // CP18 had nothing to improve. If this number DROPPED it would mean dropping content had
        // replaced removing blocks rather than arriving beside it.
        assertThat(perCharWith)
            .as("reclamation with small blocks dropped — content dropping displaced block removal")
            .isGreaterThanOrEqualTo(perCharWithout - 2 * MARKS);
    }
}
