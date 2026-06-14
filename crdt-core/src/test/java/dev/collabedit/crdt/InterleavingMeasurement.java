package dev.collabedit.crdt;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * How often RGA's interleaving anomaly actually happens, and how badly.
 *
 * <h2>Why this exists</h2>
 * The interleaving anomaly is the most-cited weakness of the algorithm this project chose, and
 * until now it was the one claim in the repository stated with an adjective.
 * {@link RgaInterleavingTest} proves the anomaly <b>exists</b> — it constructs the case by hand
 * and pins it. ADR-0001 names Fugue as the fix and declines it on budget. Neither says how often
 * a real editing session runs into it.
 *
 * <p>That is a gap the project's own thesis does not permit. Plan v3 §9 rejects Fugue with a good
 * argument — swapping a strong "I understand this trade-off" for a weaker "I have a slightly
 * better algorithm" — but that argument defends <i>not rewriting the core</i>. It does not defend
 * <i>not measuring</i>. Measuring is cheap, because {@code ReplicaSet} and the property harness
 * were already built for something else.
 *
 * <h2>The variable that turns out to matter</h2>
 * Not the number of authors, and not the document size. <b>Cursor behaviour.</b>
 *
 * <p>RGA orders siblings — insertions sharing one origin — by timestamp alone, and that is the
 * only place authors can get mixed. Typing forward never produces siblings: each character's
 * origin is the character just typed, so a run of forward typing is a chain, and a chain cannot
 * be broken by anything arriving concurrently. Typing that returns to the same point produces
 * nothing but siblings.
 *
 * <p>So the measurement sweeps exactly that: at each keystroke the author's cursor advances with
 * probability {@code p} and stays put with probability {@code 1 - p}. {@code p = 1} is ordinary
 * typing; {@code p = 0} is the hand-built case in {@link RgaInterleavingTest}. Everything a real
 * person does is somewhere between.
 *
 * <h2>The metric</h2>
 * <b>Fragments</b>: the number of maximal runs an author's characters form in the merged text.
 * One fragment means the author's writing survived in one piece. Eight fragments out of eight
 * characters means it was shredded completely.
 *
 * <p>Ownership is read from the CRDT rather than from the characters themselves —
 * {@code idAtVisible(i).replicaId()} — so the measurement does not depend on authors writing
 * distinguishable text.
 */
@DisplayName("How often RGA interleaves concurrent authors")
class InterleavingMeasurement {

    /** The replica that seeds the shared base text, so no author owns it. */
    private static final int SEED_REPLICA = 100;

    private static final String BASE = "0123456789";

    /** Everyone starts typing at the same point — the worst case, and the interesting one. */
    private static final int MEETING_POINT = 5;

    private static final int WORD = 8;

    /**
     * Trials per row.
     *
     * <p>2000 rather than a round 1000 because the rows near {@code p = 1} produce rates in the
     * low single-digit percent, and at 1000 trials the third significant figure would be noise
     * being reported as a measurement.
     */
    private static final int TRIALS = 2_000;

    /** What one trial produced. */
    private record Trial(boolean converged, int worstFragments) { }

    /**
     * Scrambles a trial number into a usable seed, and this is not a formality.
     *
     * <h2>The first version of this measurement reported numbers that were an artefact</h2>
     * Trials were seeded {@code new Random(0)}, {@code new Random(1)}, … and the table came out
     * saying 0% interleaving at a forward probability of 0.90 and 100% at 0.50 — a cliff where
     * the mechanism predicts a smooth curve. Chasing it found nothing wrong with the merge rule.
     * The fault was here:
     *
     * <pre>
     *   new Random(0).nextDouble() = 0.730968
     *   new Random(1).nextDouble() = 0.730878
     *   new Random(2).nextDouble() = 0.731147
     *   new Random(9).nextDouble() = 0.730162
     * </pre>
     *
     * <p>{@code java.util.Random} is a linear congruential generator whose <b>first</b> output is
     * almost entirely determined by the low bits of the seed. Across consecutive small seeds it
     * barely moves. So every trial made the <i>same</i> decision at the first keystroke — and the
     * first keystroke is the only one that matters here, because it decides whether an author
     * gets two children of the shared origin. Above 0.731 the answer was always "advance" and
     * nothing ever interleaved; below it, always "stay" and everything did. The measurement was
     * reporting the properties of the generator with three-significant-figure confidence.
     *
     * <p>splitmix64 — the same mixing function {@link TreapBlockList} uses to derive heap
     * priorities, and for the same reason — spreads consecutive integers across the whole range
     * before they are used.
     */
    private static Random seededBy(long trial) {
        long z = trial + 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return new Random(z ^ (z >>> 31));
    }

    /**
     * One concurrent-editing scenario.
     *
     * <p>Every author edits in complete isolation and only then exchanges operations, each in
     * its own shuffled order — the same worst-case model {@code ReplicaSet} uses. A real network
     * is gentler, so any rate measured here is an upper bound on the rate in practice.
     */
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
                // Advance, or stay and let the next character land in front of this one. This
                // single branch is the whole experiment.
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

    /** Number of maximal runs of one replica's characters in the merged visible text. */
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

                // Convergence is not the subject here, but every trial is a free chance to
                // check it, and a measurement running on a diverged replica would be measuring
                // nothing.
                assertThat(result.converged())
                    .as("replicas diverged at p=%.2f, trial %d — this is a convergence bug, "
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

        // This is the assertion that makes the whole table mean something. If forward typing
        // could interleave, the anomaly would be a property of the algorithm under ordinary use
        // and the numbers above would be describing a much more serious problem than they do.
        //
        // It holds for a structural reason, not a statistical one: typing forward makes each
        // character the origin of the next, so a run is a chain rather than a set of siblings,
        // and RGA only ever reorders siblings.
        assertThat(interleaved)
            .as("forward typing interleaved in %d of %d trials. RGA orders siblings by "
                + "timestamp, and forward typing produces no siblings — so this is either a "
                + "bug in the merge rule or a bug in this measurement", interleaved, TRIALS)
            .isZero();
    }

    @Test
    @DisplayName("more authors meeting at one point makes it worse, and by how much")
    void interleavingByAuthorCount() {
        // 0.9 rather than 1.0: at p = 1 nothing interleaves regardless of the author count, so
        // the row would be zeros and say nothing. This is the shape of writing where a cursor
        // occasionally goes back — a correction, a forgotten word — which is what makes the
        // author count visible at all.
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
