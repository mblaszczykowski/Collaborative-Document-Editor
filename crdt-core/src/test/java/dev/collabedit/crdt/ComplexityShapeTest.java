package dev.collabedit.crdt;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ComplexityShapeTest {
    private static final int SMALL = 1_000;
    private static final int LARGE = 50_000;

    private static final double MAX_GROWTH_FACTOR = 10.0;

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
    @DisplayName("the same measurement does catch a linear index - so it is measuring something")
    void naiveLookupIsVisiblyLinear() {
        double small = medianLookupNanos(buildDocument(new ArrayBlockList(), SMALL), SMALL);
        double large = medianLookupNanos(buildDocument(new ArrayBlockList(), LARGE), LARGE);
        double growth = large / small;

        System.out.printf("  naive list growth: %.1fx (control)%n", growth);

        assertThat(growth)
            .as("""
                The naive linear index grew only %.1fx for a 50x larger document. That is not \
                good news - it means this measurement cannot tell a scan from a search, and the \
                gate above is passing without checking anything.""", growth)
            .isGreaterThan(MIN_NAIVE_GROWTH);
    }

    private static RgaDocument buildDocument(BlockList storage, int characters) {
        RgaDocument document = new RgaDocument(1, storage);
        for (int i = 0; i < characters; i++) {
            document.insert(0, "x");
        }
        return document;
    }

    private static double medianLookupNanos(RgaDocument document, int characters) {
        BlockList blocks = document.blockList();
        Random random = new Random(20260815L);

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
