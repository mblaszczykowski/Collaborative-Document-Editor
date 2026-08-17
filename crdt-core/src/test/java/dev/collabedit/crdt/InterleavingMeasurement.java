package dev.collabedit.crdt;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("How often RGA interleaves concurrent authors")
class InterleavingMeasurement {
    private static final int SEED_REPLICA = 100;

    private static final String BASE = "0123456789";

    private static final int MEETING_POINT = 5;

    private static final int WORD = 8;

    private static final int TRIALS = 2_000;

    private record Trial(boolean converged, int worstFragments) { }

    private static Random seededBy(long trial) {
        long z = trial + 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return new Random(z ^ (z >>> 31));
    }

    private static Trial run(long seed, double forwardProbability, int authors, int word) {
        Random random = seededBy(seed);

        RgaDocument origin = new RgaDocument(SEED_REPLICA);
        Op.Insert base = origin.insert(0, BASE);

        List<RgaDocument> replicas = new ArrayList<>();
        List<List<Op>> emitted = new ArrayList<>();
        for (int author = 0; author < authors; author++) {
            RgaDocument replica = new RgaDocument(author + 1);
            replica.apply(base);
            replicas.add(replica);
            emitted.add(new ArrayList<>());
        }

        for (int author = 0; author < authors; author++) {
            RgaDocument replica = replicas.get(author);
            int cursor = MEETING_POINT;
            for (int k = 0; k < word; k++) {
                emitted.get(author).add(replica.insert(cursor, "x"));
                if (random.nextDouble() < forwardProbability) {
                    cursor++;
                }
            }
        }

        for (int target = 0; target < authors; target++) {
            List<Op> inbox = new ArrayList<>();
            for (int author = 0; author < authors; author++) {
                if (author != target) {
                    inbox.addAll(emitted.get(author));
                }
            }
            Collections.shuffle(inbox, random);
            replicas.get(target).applyAll(inbox);
        }

        List<String> texts = replicas.stream().map(RgaDocument::text).toList();
        boolean converged = texts.stream().allMatch(text -> text.equals(texts.getFirst()));

        int worst = 1;
        for (int author = 0; author < authors; author++) {
            worst = Math.max(worst, fragmentsOf(replicas.getFirst(), author + 1));
        }
        return new Trial(converged, worst);
    }

    private static int fragmentsOf(RgaDocument document, int replicaId) {
        int fragments = 0;
        boolean inside = false;
        for (int i = 0; i < document.visibleLength(); i++) {
            boolean mine = document.idAtVisible(i).replicaId() == replicaId;
            if (mine && !inside) {
                fragments++;
            }
            inside = mine;
        }
        return fragments;
    }

    @Test
    @DisplayName("interleaving as a function of how the author moves the cursor")
    void interleavingRateByCursorBehaviour() {
        System.out.printf("%nINTERLEAVING OF CONCURRENT TYPING%n"
                + "  2 authors, %d characters each, at the same point, %,d trials per row%n"
                + "  forward p   interleaved   median fragments   worst seen%n",
            WORD, TRIALS);

        for (double forward : new double[] {1.00, 0.99, 0.95, 0.90, 0.75, 0.50, 0.25, 0.00}) {
            int interleaved = 0;
            int worstSeen = 1;
            int[] fragments = new int[TRIALS];
            for (int trial = 0; trial < TRIALS; trial++) {
                Trial result = run(trial, forward, 2, WORD);

                assertThat(result.converged())
                    .as("replicas diverged at p=%.2f, trial %d - this is a convergence bug, "
                        + "not an interleaving result", forward, trial)
                    .isTrue();

                fragments[trial] = result.worstFragments();
                if (result.worstFragments() > 1) {
                    interleaved++;
                }
                worstSeen = Math.max(worstSeen, result.worstFragments());
            }
            java.util.Arrays.sort(fragments);
            System.out.printf("  %9.2f   %10.1f%%   %16d   %10d%n",
                forward, 100.0 * interleaved / TRIALS, fragments[TRIALS / 2], worstSeen);
        }
    }

    @Test
    @DisplayName("pure forward typing never interleaves, and that is the load-bearing claim")
    void forwardTypingNeverInterleaves() {
        int interleaved = 0;
        for (int trial = 0; trial < TRIALS; trial++) {
            Trial result = run(trial, 1.0, 2, WORD);
            assertThat(result.converged()).isTrue();
            if (result.worstFragments() > 1) {
                interleaved++;
            }
        }

        assertThat(interleaved)
            .as("forward typing interleaved in %d of %d trials. RGA orders siblings by "
                + "timestamp, and forward typing produces no siblings - so this is either a "
                + "bug in the merge rule or a bug in this measurement", interleaved, TRIALS)
            .isZero();
    }

    @Test
    @DisplayName("more authors meeting at one point makes it worse, and by how much")
    void interleavingByAuthorCount() {
        final double forward = 0.90;
        System.out.printf("%nINTERLEAVING BY NUMBER OF CONCURRENT AUTHORS%n"
                + "  %d characters each, forward p = %.2f, %,d trials per row%n"
                + "  authors   interleaved   worst fragments seen%n",
            WORD, forward, TRIALS);

        for (int authors : new int[] {2, 3, 5, 8}) {
            int interleaved = 0;
            int worstSeen = 1;
            for (int trial = 0; trial < TRIALS; trial++) {
                Trial result = run(trial, forward, authors, WORD);
                assertThat(result.converged())
                    .as("replicas diverged with %d authors, trial %d", authors, trial)
                    .isTrue();
                if (result.worstFragments() > 1) {
                    interleaved++;
                }
                worstSeen = Math.max(worstSeen, result.worstFragments());
            }
            System.out.printf("  %7d   %10.1f%%   %20d%n",
                authors, 100.0 * interleaved / TRIALS, worstSeen);
        }
    }
}
