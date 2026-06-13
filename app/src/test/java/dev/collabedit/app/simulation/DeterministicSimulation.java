package dev.collabedit.app.simulation;

import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Random;

/**
 * A single-threaded scheduler in which a seed decides everything that is normally timing (CP17).
 *
 * <h2>Why this exists</h2>
 * {@code ClusterChaosIT} stops Redis mid-write and checks the cluster repairs itself. It is a
 * good test and it found real bugs. It has one flaw that no amount of care fixes: <b>its result
 * depends on how threads happened to interleave</b>. {@code nightly.yml} runs it ten times in a
 * row precisely because one green run proves so little, and the workflow says so out loud —
 * "a test that passes once may have passed by accident".
 *
 * <p>That framing concedes the real problem, which is not detection but <b>reproduction</b>.
 * When the ten-run job does go red, there is no way to run <i>that</i> failure again. Debugging
 * becomes a loop and a hope.
 *
 * <p>This class removes timing from the picture. Nothing sleeps, nothing races, and there is no
 * wall clock: time is a {@code long} that only moves when the next event says so. Message order,
 * delays, partitions and crashes are all drawn from one seeded {@link Random}. The consequence
 * that matters:
 *
 * <blockquote>
 * A failure stops being "one run in ten" and becomes {@code seed = 8471293} — a one-line
 * regression test that reproduces the same interleaving on any machine, forever.
 * </blockquote>
 *
 * <h2>What it does not do</h2>
 * This is a simulation of the design in ADR-0004, driven by the <b>real</b> merge algorithm from
 * {@code crdt-core}. It does not touch Postgres, Redis, Spring or a socket. So it can find a bug
 * in the distributed logic and cannot find a bug in the wiring — which is why
 * {@code ClusterChaosIT} stays exactly where it is. Replacing one with the other would be the
 * same mistake as treating a snapshot as the source of truth.
 *
 * <h2>Determinism rules for anything built on this</h2>
 * Every one of these is a way the property has already been lost in projects like this one:
 * <ul>
 *   <li>no {@code System.currentTimeMillis}, {@code Instant.now} or {@code Thread.sleep} —
 *       use {@link #now()};</li>
 *   <li>no unseeded randomness — use {@link #random()};</li>
 *   <li>no iteration over {@code HashMap} or {@code HashSet}, whose order is stable within a
 *       JVM run but not guaranteed across versions — use the linked or sorted variants;</li>
 *   <li>ties between events at the same virtual instant break by insertion order, never by
 *       object identity or hash.</li>
 * </ul>
 * {@code sameSeedProducesIdenticalHistory} is the test that these rules are actually being
 * followed, and it is more important than any scenario built on top of this class.
 */
final class DeterministicSimulation {

    /** One thing that happens at one virtual instant. */
    private record ScheduledEvent(long at, long sequence, String label, Runnable action)
        implements Comparable<ScheduledEvent> {

        @Override
        public int compareTo(ScheduledEvent other) {
            int byTime = Long.compare(at, other.at);
            // Insertion order breaks ties. Without this the queue would be free to return
            // same-instant events in any order it liked, and the whole property would rest on
            // an implementation detail of PriorityQueue.
            return byTime != 0 ? byTime : Long.compare(sequence, other.sequence);
        }
    }

    private final PriorityQueue<ScheduledEvent> queue = new PriorityQueue<>();
    private final Random random;
    private final long seed;

    /**
     * Everything that happened, in order.
     *
     * <p>This is the artifact the determinism test compares. Two runs of the same seed must
     * produce identical lists — not merely identical final states, because a system can reach
     * the right answer by two different routes and only one of them may be reproducible.
     */
    private final List<String> history = new ArrayList<>();

    private long now;
    private long sequence;
    private long stepsExecuted;

    DeterministicSimulation(long seed) {
        this.seed = seed;
        // java.util.Random rather than ThreadLocalRandom or Math.random: its algorithm is
        // specified by the JDK, so the same seed yields the same sequence on every platform and
        // every JVM version. A faster generator with an unspecified algorithm would make the
        // seed meaningless across machines, which is the one thing this class sells.
        this.random = new Random(seed);
    }

    long seed() {
        return seed;
    }

    long now() {
        return now;
    }

    Random random() {
        return random;
    }

    List<String> history() {
        return List.copyOf(history);
    }

    long stepsExecuted() {
        return stepsExecuted;
    }

    /** Records something that happened, for the determinism comparison. */
    void record(String event) {
        history.add(now + " " + event);
    }

    /** Schedules an action {@code delayMillis} of virtual time from now. */
    void schedule(long delayMillis, String label, Runnable action) {
        if (delayMillis < 0) {
            throw new IllegalArgumentException("cannot schedule into the past: " + delayMillis);
        }
        queue.add(new ScheduledEvent(now + delayMillis, sequence++, label, action));
    }

    /** A delay drawn from the seeded generator, inclusive on both ends. */
    long delayBetween(long minMillis, long maxMillis) {
        return minMillis + random.nextInt((int) (maxMillis - minMillis + 1));
    }

    /**
     * Checked after every single event.
     *
     * <p>A hook rather than a final assertion because the interesting failures are transient:
     * a structure that is briefly wrong between two events and correct again by the end would
     * pass any check that only runs when the queue drains.
     *
     * @see SimulatedCluster#violatedInvariant()
     */
    private java.util.function.Supplier<String> invariant = () -> null;

    void checkAfterEachStep(java.util.function.Supplier<String> invariantCheck) {
        this.invariant = invariantCheck;
    }

    /**
     * Runs every event scheduled up to {@code deadlineMillis} of virtual time, then stops.
     *
     * <p>A horizon rather than "until the queue is empty", because the queue is never empty:
     * anti-entropy reschedules itself for as long as the cluster is alive, exactly as the real
     * periodic sweep does. Draining it would mean simulating until the heat death of the
     * cluster — the first version of this class did, and every scenario hit the runaway guard.
     *
     * <p>Choosing the horizon is therefore part of writing a scenario: it has to be far enough
     * past the last interesting event for the repair mechanisms to have had their chance. Too
     * short and the test asserts that a system mid-repair has not finished repairing, which is
     * true and uninteresting.
     *
     * @param stepLimit a runaway guard; a scenario that schedules faster than it drains is a
     *                  bug in the scenario, and hanging CI is a bad way to report it
     */
    void runUntil(long deadlineMillis, long stepLimit) {
        while (!queue.isEmpty() && queue.peek().at() <= deadlineMillis) {
            if (stepsExecuted >= stepLimit) {
                throw new IllegalStateException(
                    "simulation exceeded " + stepLimit + " steps (seed " + seed + "); "
                        + "something is scheduling without end");
            }
            ScheduledEvent event = queue.poll();
            // Virtual time only ever moves forward, and only to the next event. Nothing waits.
            now = event.at();
            stepsExecuted++;
            event.action().run();

            String violation = invariant.get();
            if (violation != null) {
                throw new AssertionError("invariant broken after step " + stepsExecuted
                    + " (" + event.label() + ") at t=" + now + ", seed " + seed + ": " + violation
                    + System.lineSeparator() + "history:" + System.lineSeparator()
                    + String.join(System.lineSeparator(), history));
            }
        }
    }
}
