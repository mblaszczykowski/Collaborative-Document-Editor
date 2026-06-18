package dev.collabedit.app.simulation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Deterministic simulation of the cluster design (CP17).
 *
 * <table>
 *   <caption>Test → failure mode</caption>
 *   <tr><th>Test</th><th>What it catches</th></tr>
 *   <tr><td>{@code sameSeedProducesIdenticalHistory}</td>
 *       <td><b>The simulation is not deterministic</b> — meaning every other test in this class
 *           is a chaos test with extra steps, and a failure found here still could not be
 *           reproduced. This is the most important test in the file.</td></tr>
 *   <tr><td>{@code differentSeedsProduceDifferentHistories}</td>
 *       <td>The seed is ignored and every run explores the same single interleaving — a suite
 *           that looks like it covers a thousand schedules while covering one.</td></tr>
 *   <tr><td>{@code clusterConvergesUnderAdversarialDelivery}</td>
 *       <td>The ADR-0004 claim is false: a dropped or reordered broadcast leaves instances
 *           permanently disagreeing rather than merely late.</td></tr>
 *   <tr><td>{@code clusterConvergesAcrossCrashAndRestart}</td>
 *       <td>Rebuilding a replica from the log produces a different document than the one that
 *           was lost.</td></tr>
 *   <tr><td>{@code clusterConvergesAcrossPartition}</td>
 *       <td>Anti-entropy does not actually repair a Redis outage, so the fast path is load
 *           bearing after all.</td></tr>
 *   <tr><td>{@code totalFastPathFailureStillConverges}</td>
 *       <td>The strongest form: with the fast path dropping <b>everything</b>, the log alone
 *           must still bring every instance to the same text. If this fails, "Redis is an
 *           unreliable fast path" is not a true description of the system.</td></tr>
 * </table>
 *
 * <p>Every scenario asserts convergence <i>and</i> checks the cross-replica invariant after
 * every single event, via {@link DeterministicSimulation#checkAfterEachStep}.
 *
 * <h2>When one of these fails</h2>
 * The failure message carries the seed and the full event history. Add the seed to
 * {@code REGRESSION_SEEDS} below and it becomes a permanent one-line regression test that
 * reproduces the identical interleaving on any machine. That is the entire reason this file
 * exists, and it is the thing {@code ClusterChaosIT} cannot do.
 */
class ClusterSimulationTest {

    private static final long STEP_LIMIT = 200_000;

    /**
     * How long each scenario runs in virtual milliseconds.
     *
     * <p>Typing finishes well before 3 000 ms, so this leaves room for roughly twenty
     * anti-entropy sweeps after the last edit. That margin is the point: the claim under test is
     * that the cluster <b>ends up</b> agreeing, and a horizon that cut in during repair would
     * assert only that repair takes time.
     */
    private static final long HORIZON_MS = 8_000;

    /**
     * Seeds that once found a real bug.
     *
     * <p><b>Empty, and honestly so.</b> No counterexample has been found yet — CP17 built the
     * machine, it did not (so far) catch anything with it. Writing a plausible-looking seed in
     * here would be inventing a bug that never happened.
     *
     * <p>The list is not decoration: when the randomised runs below do go red, the seed from the
     * failure message goes here, and the bug can never come back unnoticed.
     */
    private static final List<Long> REGRESSION_SEEDS = List.of();

    // ------------------------------------------------------------------
    // The property the whole approach rests on
    // ------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(longs = {1L, 42L, 8_471_293L, Long.MAX_VALUE})
    @DisplayName("the same seed replays the same history, event for event")
    void sameSeedProducesIdenticalHistory(long seed) {
        List<String> first = runScenario(seed).history();
        List<String> second = runScenario(seed).history();

        // Comparing histories rather than final texts on purpose. A system can arrive at the
        // same answer by two different routes, and only one of those routes is reproducible —
        // comparing end states would call that determinism when it is not.
        assertThat(second)
            .as("""
                Two runs of seed %d diverged. Every scenario in this class is now just a chaos \
                test: a failure found here could not be reproduced, which is the exact problem \
                CP17 exists to remove. Look for a HashMap iteration, an unseeded random, a real \
                clock, or a tie between two events at the same instant.""", seed)
            .containsExactlyElementsOf(first);
    }

    @Test
    @DisplayName("different seeds really do explore different interleavings")
    void differentSeedsProduceDifferentHistories() {
        // Without this, a bug that made the generator constant would leave every test above
        // passing perfectly while covering exactly one schedule out of the millions claimed.
        Set<List<String>> distinct = new HashSet<>();
        for (long seed = 1; seed <= 20; seed++) {
            distinct.add(runScenario(seed).history());
        }

        assertThat(distinct)
            .as("20 seeds produced %d distinct histories; the seed is being ignored somewhere",
                distinct.size())
            .hasSizeGreaterThan(15);
    }

    // ------------------------------------------------------------------
    // Scenarios
    // ------------------------------------------------------------------

    /**
     * How many seeds each scenario explores.
     *
     * <p>200 on a pull request, because the whole class then runs in under a second and fast
     * feedback is the point. The nightly job raises it with {@code -Dsimulation.seeds=20000},
     * mirroring how {@code -Pthorough} raises the jqwik try count.
     *
     * <p>The split matters for the same reason it does there: the pull-request run <b>confirms</b>
     * known-good behaviour on a fixed set of seeds, the nightly run <b>searches</b> for a
     * counterexample. A search that has to finish in a second is not much of a search.
     */
    private static final int SEED_COUNT =
        Integer.getInteger("simulation.seeds", 200);

    static IntStream seeds() {
        return IntStream.rangeClosed(1, SEED_COUNT);
    }

    @ParameterizedTest
    @MethodSource("seeds")
    @DisplayName("the cluster converges however the fast path misbehaves")
    void clusterConvergesUnderAdversarialDelivery(int seed) {
        assertConverged(runScenario(seed));
    }

    @ParameterizedTest
    @MethodSource("seeds")
    @DisplayName("the cluster converges across crash and restart")
    void clusterConvergesAcrossCrashAndRestart(int seed) {
        DeterministicSimulation sim = new DeterministicSimulation(seed);
        SimulatedCluster cluster = new SimulatedCluster(sim, 4, 0.15);
        sim.checkAfterEachStep(cluster::violatedInvariant);

        scheduleTyping(sim, cluster, 4, 60);

        // One instance dies mid-flight and comes back with nothing in memory. ADR-0003 says the
        // log is the source of truth and the snapshot is a removable cache; if that is right,
        // the rebuilt replica must be indistinguishable from the one that was lost.
        sim.schedule(300, "crash", () -> cluster.crash(2));
        sim.schedule(900, "restart", () -> cluster.restart(2));

        sim.runUntil(HORIZON_MS, STEP_LIMIT);
        cluster.settle();
        assertConverged(sim, cluster);
    }

    @ParameterizedTest
    @MethodSource("seeds")
    @DisplayName("the cluster converges across a fast-path partition")
    void clusterConvergesAcrossPartition(int seed) {
        DeterministicSimulation sim = new DeterministicSimulation(seed);
        SimulatedCluster cluster = new SimulatedCluster(sim, 4, 0.1);
        sim.checkAfterEachStep(cluster::violatedInvariant);

        scheduleTyping(sim, cluster, 4, 60);

        // This is ClusterChaosIT's scenario — Redis cut while people are writing — except that
        // here it happens at a virtual instant chosen by the seed rather than wherever the
        // thread scheduler landed, so a failure is replayable.
        sim.schedule(200, "partition", () -> cluster.partition(3, true));
        sim.schedule(1_000, "heal", () -> cluster.partition(3, false));

        sim.runUntil(HORIZON_MS, STEP_LIMIT);
        cluster.settle();
        assertConverged(sim, cluster);
    }

    @ParameterizedTest
    @MethodSource("seeds")
    @DisplayName("with the fast path dropping everything, the log alone still converges")
    void totalFastPathFailureStillConverges(int seed) {
        // The strongest statement of ADR-0004. If Redis contributes nothing whatsoever and the
        // instances still agree, then "unreliable fast path" is a true description rather than
        // an aspiration — losing it costs latency and nothing else.
        DeterministicSimulation sim = new DeterministicSimulation(seed);
        SimulatedCluster cluster = new SimulatedCluster(sim, 3, 1.0);
        sim.checkAfterEachStep(cluster::violatedInvariant);

        scheduleTyping(sim, cluster, 3, 50);
        sim.runUntil(HORIZON_MS, STEP_LIMIT);
        cluster.settle();

        assertConverged(sim, cluster);
        assertThat(cluster.logSize())
            .as("nothing was written, so convergence is trivially true and proves nothing")
            .isGreaterThan(0);
    }

    // ------------------------------------------------------------------
    // Durability — the half CP17 explicitly did not model
    // ------------------------------------------------------------------

    /**
     * An acknowledged operation is never lost, however badly the instance behaves.
     *
     * <h2>Why this scenario had to exist</h2>
     * {@code SimulatedCluster} used to append to the log synchronously with the edit, and named
     * that as its boundary: it left out <i>"the window in which an instance broadcasts an
     * operation and dies before persisting it"</i>, because the repair lives in the client's
     * unacknowledged buffer and there was no client in the model. It ended with "that is the
     * first thing to add when extending this".
     *
     * <p>The omission mattered more than it looks. ADR-0003 makes a promise a user cannot check
     * for themselves — <b>an acknowledgement means the work is durable</b> — and the ordering it
     * rests on was written down only in prose, in the javadoc of
     * {@code DocumentReplica.drainForFlush}. Nothing executable held it. "CP17 found zero bugs
     * across 806 scenarios" is a weaker statement than it appears when the layer carrying that
     * promise was outside the model.
     *
     * <p>{@code Durability.DEFERRED} puts the append 60 ms after the broadcast, so a crash lands
     * inside the window regularly rather than by luck.
     */
    @ParameterizedTest
    @MethodSource("seeds")
    @DisplayName("no acknowledged operation is ever lost, across crashes inside the write window")
    void acknowledgedWorkSurvivesCrashes(int seed) {
        Run run = runDurabilityScenario(seed);

        assertThat(run.cluster().acknowledgedButNotLogged())
            .as("""
                The system told a client its work was durable and then lost it. This is the one \
                promise in ADR-0003 that a user has no way of checking, which is why it is \
                asserted rather than trusted: the acknowledgement goes out AFTER the append, \
                never after the broadcast.""")
            .isEmpty();
    }

    /**
     * Work that was never acknowledged still arrives, because the client resends it.
     *
     * <p>The complement of the invariant above, and the reason a client is in the model at all.
     * Durability says an ack is honest; this says a missing ack is not silent loss — the browser
     * keeps the operation and retransmits on reconnect, which is exactly why the ack is tied to
     * the write rather than to the broadcast.
     */
    @ParameterizedTest
    @MethodSource("seeds")
    @DisplayName("unacknowledged work is retransmitted rather than dropped")
    void unacknowledgedWorkIsRetransmitted(int seed) {
        Run run = runDurabilityScenario(seed);

        assertThat(run.cluster().unacknowledgedCount())
            .as("""
                Operations were still sitting in a client buffer after everything settled and \
                every instance had come back. They were accepted from the user, never confirmed \
                and never resent — the silent loss the buffer exists to prevent.""")
            .isZero();
    }

    /**
     * The scenario has to actually reach the window, or the two assertions above prove nothing.
     *
     * <p>Without this, a change that made crashes impossible — or the persist delay zero — would
     * leave them green and empty. The same failure mode as the {@code *Measurement} class that
     * never ran: passing because it never got to the interesting part.
     */
    @Test
    @DisplayName("the durability scenario really does crash inside the write window")
    void durabilityScenarioReachesTheWindow() {
        int seedsLosingAnUnpersistedOperation = 0;
        int seedsAcknowledgingSomething = 0;

        for (int seed = 1; seed <= 100; seed++) {
            Run run = runDurabilityScenario(seed);
            if (run.history().stream().anyMatch(entry -> entry.contains("persist lost"))) {
                seedsLosingAnUnpersistedOperation++;
            }
            if (run.cluster().acknowledgedCount() > 0) {
                seedsAcknowledgingSomething++;
            }
        }

        assertThat(seedsLosingAnUnpersistedOperation)
            .as("""
                No seed ever crashed an instance between the broadcast and the append, so the \
                durability assertion never had anything to catch. Either the crash schedule or \
                the persist delay stopped producing the window this scenario exists for.""")
            .isGreaterThan(0);

        assertThat(seedsAcknowledgingSomething)
            .as("nothing was ever acknowledged, so the invariant ranged over an empty set")
            .isGreaterThan(50);
    }

    /**
     * Three instances in deferred-durability mode, crashing far more often than {@link
     * #runScenario} does.
     *
     * <p>The frequency is deliberate: this scenario is not hunting for convergence under a lossy
     * fast path, it is hunting for the narrow window between a broadcast and its append, and a
     * rare crash would find it rarely.
     */
    private static Run runDurabilityScenario(long seed) {
        DeterministicSimulation sim = new DeterministicSimulation(seed);
        SimulatedCluster cluster = new SimulatedCluster(sim, 3, 0.2,
            SimulatedCluster.Durability.DEFERRED);
        sim.checkAfterEachStep(cluster::violatedInvariant);

        scheduleTyping(sim, cluster, 3, 60);

        // Crashes scheduled up front, like the typing: an instance that comes back has to
        // retransmit, so every crash is paired with a restart. Leaving one down at the end would
        // strand a client buffer and fail the retransmission assertion for a reason about the
        // scenario rather than about the system.
        long at = 0;
        for (int i = 0; i < 12; i++) {
            at += sim.delayBetween(80, 400);
            int victim = 1 + sim.random().nextInt(3);
            long crashAt = at;
            sim.schedule(crashAt, "crash", () -> cluster.crash(victim));
            sim.schedule(crashAt + sim.delayBetween(40, 250), "restart",
                () -> cluster.restart(victim));
        }

        sim.runUntil(HORIZON_MS, STEP_LIMIT);
        // Anything still down never got to retransmit; bring it back and let the queue drain.
        cluster.restartAll();
        sim.runUntil(HORIZON_MS * 2, STEP_LIMIT);
        cluster.settle();
        return new Run(sim, cluster);
    }

    @ParameterizedTest
    @MethodSource("regressionSeeds")
    @DisplayName("a seed that once found a bug still passes")
    void knownFailingSeedStaysFixed(long seed) {
        assertConverged(runScenario(seed));
    }

    static List<Long> regressionSeeds() {
        // JUnit refuses an empty argument set, so an always-passing placeholder stands in until
        // the first real counterexample arrives. It is listed as such rather than dressed up.
        return REGRESSION_SEEDS.isEmpty() ? List.of(0L) : REGRESSION_SEEDS;
    }

    // ------------------------------------------------------------------

    private record Run(DeterministicSimulation sim, SimulatedCluster cluster) {
        List<String> history() {
            return sim.history();
        }
    }

    /** The default scenario: four instances, a lossy fast path, people typing over each other. */
    private static Run runScenario(long seed) {
        DeterministicSimulation sim = new DeterministicSimulation(seed);
        SimulatedCluster cluster = new SimulatedCluster(sim, 4, 0.2);
        sim.checkAfterEachStep(cluster::violatedInvariant);

        scheduleTyping(sim, cluster, 4, 80);
        sim.runUntil(HORIZON_MS, STEP_LIMIT);
        cluster.settle();
        return new Run(sim, cluster);
    }

    /**
     * Schedules {@code edits} keystrokes spread across the cluster.
     *
     * <p>{@code instanceCount} is a parameter rather than a constant because it was a constant
     * once: the scenario with three instances kept addressing a fourth, and the failure surfaced
     * as a NullPointerException rather than as anything about convergence.
     */
    private static void scheduleTyping(DeterministicSimulation sim, SimulatedCluster cluster,
                                       int instanceCount, int edits) {
        long at = 0;
        for (int i = 0; i < edits; i++) {
            at += sim.delayBetween(5, 60);
            int instance = 1 + sim.random().nextInt(instanceCount);
            int position = sim.random().nextInt(40);
            boolean deleting = sim.random().nextInt(100) < 20;
            String text = String.valueOf((char) ('a' + sim.random().nextInt(26)));

            // Position 0 as often as anywhere else: appending at the end compresses into one
            // run-length block and skips the path that actually scales, which is the same
            // reason the load harness types at the front.
            if (deleting) {
                sim.schedule(at, "delete", () -> cluster.deleteAt(instance, position, 1));
            } else {
                sim.schedule(at, "edit", () -> cluster.edit(instance, position, text));
            }
        }
    }

    private static void assertConverged(Run run) {
        assertConverged(run.sim(), run.cluster());
    }

    private static void assertConverged(DeterministicSimulation sim, SimulatedCluster cluster) {
        Map<Integer, String> texts = cluster.texts();

        assertThat(cluster.liveInstanceCount())
            .as("every instance is down, so there is nothing to compare")
            .isGreaterThan(1);

        assertThat(texts.values().stream().distinct().toList())
            .as("""
                Instances disagree after everything settled — seed %d.

                Reproduce with exactly this seed; the interleaving is identical on any machine. \
                Add it to REGRESSION_SEEDS once fixed.

                texts: %s

                history:
                %s""",
                sim.seed(), texts, String.join(System.lineSeparator(), sim.history()))
            .hasSize(1);
    }
}
