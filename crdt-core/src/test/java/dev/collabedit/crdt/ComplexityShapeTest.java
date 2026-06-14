package dev.collabedit.crdt;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A CI gate on the <b>shape</b> of the index's cost, not on its milliseconds (CP19).
 *
 * <h2>Why not simply assert the README's numbers</h2>
 * Plan v2 listed "a CI job comparing the harness against a stored result" as worth considering,
 * and it was never built. The README carries about two dozen measured numbers with nothing
 * keeping them true; the first change that makes one worse passes unnoticed, and the
 * documentation quietly becomes the thing {@code CLAUDE.md} warns against — written from memory
 * rather than measured.
 *
 * <p>The obvious fix is to re-run the load harness in CI and fail on a regression beyond some
 * percentage. <b>That threshold cannot be defended on a shared runner.</b> The evidence is in
 * this project's own README: repeating one measurement on the <i>same</i> machine gave p50 of
 * 28 ms once and 43 ms another time — a spread of over 50% between runs, with no code change
 * involved. {@code nightly.yml} already says the same about the JMH job: runner numbers detect
 * regressions between runs, they are not quotable. A gate whose threshold has to exceed 50% to
 * avoid false alarms would not catch a real 40% regression, so it would be theatre.
 *
 * <h2>What is gated instead</h2>
 * Ratios measured <b>within a single run, on one machine, in one JVM</b>. Absolute speed varies
 * with whatever else the runner is doing; the ratio between a lookup in a 1 000-character
 * document and the same lookup in a 50 000-character one does not, because both halves of the
 * fraction move together.
 *
 * <p>That ratio is exactly the claim the README makes and the reason the treap was written:
 *
 * <blockquote>
 * "the shape matters more than the individual numbers. For a tenfold larger document, the naive
 * search grows 15x and 11x — linearly — and the treap grows 2.3x and 1.9x — logarithmically."
 * </blockquote>
 *
 * <p>So this test asserts the shape and lets the numbers be numbers. A change that turns the
 * treap lookup back into a scan fails here; a change that makes everything 30% slower does not,
 * and deliberately so — the runner cannot tell that apart from a noisy neighbour.
 *
 * <h2>What this does NOT catch</h2>
 * Constant-factor regressions, which is a real gap and is left open on purpose rather than
 * papered over with a threshold that cannot hold. Catching those needs a dedicated machine,
 * which the "Performance" section of the README already names as the missing piece for its
 * other numbers too.
 */
class ComplexityShapeTest {

    private static final int SMALL = 1_000;
    /**
     * 50 000 rather than the 100 000 the README benchmarks use.
     *
     * <p>The control below builds the same document on the naive list, and building it is
     * quadratic — at 100 000 the pair took ten seconds, which is most of the fast CI job for one
     * assertion. Fifty times the size still separates linear from logarithmic by an order of
     * magnitude, and the point of this test is the separation, not the absolute figure.
     */
    private static final int LARGE = 50_000;

    /**
     * How much slower the large document may be than the small one.
     *
     * <p>Between these two sizes the document grows 50x. Linear lookup would grow by roughly
     * that; logarithmic growth is about {@code log(50000)/log(1000) ≈ 1.6}. The bound sits at
     * <b>10</b> — six times the expected value and five times below the linear one.
     *
     * <p>The margin is that wide on purpose. A tight bound would measure the runner's mood; this
     * one only fires when the growth stops being logarithmic at all, which is the single failure
     * this test claims to detect.
     */
    private static final double MAX_GROWTH_FACTOR = 10.0;

    /**
     * Confirms the measurement can tell the two shapes apart.
     *
     * <p>Without this, a bug that made both timings equal — a broken harness, an optimised-away
     * loop — would leave the assertion below passing forever while measuring nothing. The naive
     * list must visibly blow the same budget the treap stays inside.
     */
    private static final double MIN_NAIVE_GROWTH = 15.0;

    @Test
    @DisplayName("treap lookup stays logarithmic as the document grows 50x")
    void treapLookupDoesNotGrowLinearly() {
        double small = medianLookupNanos(buildDocument(new TreapBlockList(), SMALL), SMALL);
        double large = medianLookupNanos(buildDocument(new TreapBlockList(), LARGE), LARGE);
        double growth = large / small;

        System.out.printf("%nINDEX SHAPE (position -> identifier)%n"
            + "  %,7d characters: %8.0f ns%n"
            + "  %,7d characters: %8.0f ns%n"
            + "  growth: %.1fx (linear would be ~50x, logarithmic ~1.6x)%n",
            SMALL, small, LARGE, large, growth);

        assertThat(growth)
            .as("""
                Lookup cost grew %.1fx for a 50x larger document, which is the shape of a scan \
                rather than of a search. This is the regression the treap exists to prevent, and \
                it is invisible in any test that uses a small document.""", growth)
            .isLessThan(MAX_GROWTH_FACTOR);
    }

    @Test
    @DisplayName("the same measurement does catch a linear index — so it is measuring something")
    void naiveLookupIsVisiblyLinear() {
        // ArrayBlockList is the deliberately naive implementation kept for exactly this kind of
        // comparison. If the harness above could not distinguish it from the treap, the gate
        // would be green regardless of what it was pointed at.
        double small = medianLookupNanos(buildDocument(new ArrayBlockList(), SMALL), SMALL);
        double large = medianLookupNanos(buildDocument(new ArrayBlockList(), LARGE), LARGE);
        double growth = large / small;

        System.out.printf("  naive list growth: %.1fx (control)%n", growth);

        assertThat(growth)
            .as("""
                The naive linear index grew only %.1fx for a 50x larger document. That is not \
                good news — it means this measurement cannot tell a scan from a search, and the \
                gate above is passing without checking anything.""", growth)
            .isGreaterThan(MIN_NAIVE_GROWTH);
    }

    // ------------------------------------------------------------------

    /**
     * A document of {@code characters} length spread over many blocks.
     *
     * <p>Inserting at the front rather than appending, for the same reason the load harness
     * does: continuous typing compresses into one run-length block and skips the path that
     * actually scales with document size. A single-block document would make every
     * implementation look logarithmic.
     */
    private static RgaDocument buildDocument(BlockList storage, int characters) {
        RgaDocument document = new RgaDocument(1, storage);
        for (int i = 0; i < characters; i++) {
            document.insert(0, "x");
        }
        return document;
    }

    /**
     * Median nanoseconds per position lookup.
     *
     * <p>Median rather than mean: one garbage collection pause during the run moves a mean far
     * more than it moves a median, and this test has to survive a busy runner.
     */
    private static double medianLookupNanos(RgaDocument document, int characters) {
        BlockList blocks = document.blocks();
        Random random = new Random(20260815L);

        // Warm-up. Without it the first sample includes interpretation and class loading, which
        // affects the small document far more than the large one and would flatter the ratio.
        for (int i = 0; i < 2_000; i++) {
            blocks.locateVisible(random.nextInt(characters));
        }

        int rounds = 15;
        int lookupsPerRound = 2_000;
        List<Double> samples = new ArrayList<>(rounds);
        for (int round = 0; round < rounds; round++) {
            long start = System.nanoTime();
            for (int i = 0; i < lookupsPerRound; i++) {
                blocks.locateVisible(random.nextInt(characters));
            }
            samples.add((System.nanoTime() - start) / (double) lookupsPerRound);
        }
        samples.sort(Double::compare);
        return samples.get(samples.size() / 2);
    }
}
