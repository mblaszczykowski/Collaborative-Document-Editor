package dev.collabedit.crdt;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MarkCostMeasurement {
    private static final int TEXT_LENGTH = 50_000;

    private static final int DENSE_MARKS = 500;

    private static RgaDocument document() {
        return new RgaDocument(1, new TreapBlockList());
    }

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
    @DisplayName("marks cost what they weigh in a snapshot - they do not duplicate the text")
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
                + "         TEXT as much as of the format - real English gzips at roughly 3:1,%n"
                + "         and \"x\" repeated 50 000 times at 600:1)%n"
                + "  snapshot without marks:  %,d B%n"
                + "  the same text, %d marks: %,d B%n"
                + "  overhead per mark:       %d B%n",
            TEXT_LENGTH, (double) TEXT_LENGTH / withoutMarks,
            withoutMarks, DENSE_MARKS, withMarks, perMark);

        assertThat(perMark)
            .as("a mark weighs as much as a fragment of text in the snapshot - the encoder duplicates something")
            .isLessThan(120);
    }

    private static long medianResolveNanos(RgaDocument document) {
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

            assertThat(spans)
                .as("ranges are meant to stay disjoint at %d marks, so spans should equal marks; "
                    + "%d spans means the geometry changed and the row is not comparable "
                    + "with the others", markCount, spans)
                .isEqualTo(markCount);

            assertThat(elapsed)
                .as("resolving formatting with %d marks took over a second", markCount)
                .isLessThan(1_000_000_000L);
        }
    }

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

    private static RgaDocument writtenInOneRun() {
        RgaDocument document = document();
        document.insert(0, "x".repeat(CHARS));
        return document;
    }

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
    @DisplayName("an anchor no longer blocks memory - the block gives up its content and keeps its identity")
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

        assertThat(oneRunWith)
            .as("a run-length block with an anchor blocks memory again - a regression in content-dropping")
            .isEqualTo(oneRunWithout);

        assertThat(perCharWith)
            .as("reclamation with small blocks dropped - content dropping displaced block removal")
            .isGreaterThanOrEqualTo(perCharWithout - 2 * MARKS);
    }
}
