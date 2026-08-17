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

class ClusterSimulationTest {
    private static final long STEP_LIMIT = 200_000;

    private static final long HORIZON_MS = 8_000;

    private static final List<Long> REGRESSION_SEEDS = List.of();

    @ParameterizedTest
    @ValueSource(longs = {1L, 42L, 8_471_293L, Long.MAX_VALUE})
    @DisplayName("the same seed replays the same history, event for event")
    void sameSeedProducesIdenticalHistory(long seed) {
        List<String> first = runScenario(seed).history();
        List<String> second = runScenario(seed).history();

        assertThat(second)
            .as("""
                Two runs of seed %d diverged. Every scenario in this class is now just a chaos \
                test: a failure found here could not be reproduced, which is the exact problem \
                the seeded simulation exists to remove. Look for a HashMap iteration, an unseeded \
                random, a real \
                clock, or a tie between two events at the same instant.""", seed)
            .containsExactlyElementsOf(first);
    }

    @Test
    @DisplayName("different seeds really do explore different interleavings")
    void differentSeedsProduceDifferentHistories() {
        Set<List<String>> distinct = new HashSet<>();
        for (long seed = 1; seed <= 20; seed++) {
            distinct.add(runScenario(seed).history());
        }

        assertThat(distinct)
            .as("20 seeds produced %d distinct histories; the seed is being ignored somewhere",
                distinct.size())
            .hasSizeGreaterThan(15);
    }

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

    @ParameterizedTest
    @MethodSource("seeds")
    @DisplayName("no acknowledged operation is ever lost, across crashes inside the write window")
    void acknowledgedWorkSurvivesCrashes(int seed) {
        Run run = runDurabilityScenario(seed);

        assertThat(run.cluster().acknowledgedButNotLogged())
            .as("""
                The system told a client its work was durable and then lost it. This is the one \
                promise in the operation-log decision that a user has no way of checking, which is why it is \
                asserted rather than trusted: the acknowledgement goes out AFTER the append, \
                never after the broadcast.""")
            .isEmpty();
    }

    @Test
    @DisplayName("acknowledging on broadcast instead of on the write IS caught")
    void theDurabilityDetectorCanFail() {
        Run run = runDurabilityScenario(
            1, SimulatedCluster.Durability.ACKNOWLEDGE_ON_BROADCAST);

        assertThat(run.cluster().acknowledgedButNotLogged())
            .as("""
                An acknowledgement was sent before the write, an instance crashed inside that \
                window, and acknowledgedButNotLogged still found nothing - so it cannot detect the \
                failure it exists for, and the invariant next to it proves nothing.""")
            .isNotEmpty();
    }

    @ParameterizedTest
    @MethodSource("seeds")
    @DisplayName("unacknowledged work is retransmitted rather than dropped")
    void unacknowledgedWorkIsRetransmitted(int seed) {
        Run run = runDurabilityScenario(seed);

        assertThat(run.cluster().unacknowledgedCount())
            .as("""
                Operations were still sitting in a client buffer after everything settled and \
                every instance had come back. They were accepted from the user, never confirmed \
                and never resent - the silent loss the buffer exists to prevent.""")
            .isZero();
    }

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

    private static Run runDurabilityScenario(long seed) {
        return runDurabilityScenario(seed, SimulatedCluster.Durability.DEFERRED);
    }

    private static Run runDurabilityScenario(long seed, SimulatedCluster.Durability durability) {
        DeterministicSimulation sim = new DeterministicSimulation(seed);
        SimulatedCluster cluster = new SimulatedCluster(sim, 3, 0.2, durability);
        sim.checkAfterEachStep(cluster::violatedInvariant);

        scheduleTyping(sim, cluster, 3, 60);

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
        return REGRESSION_SEEDS.isEmpty() ? List.of(0L) : REGRESSION_SEEDS;
    }

    private record Run(DeterministicSimulation sim, SimulatedCluster cluster) {
        List<String> history() {
            return sim.history();
        }
    }

    private static Run runScenario(long seed) {
        DeterministicSimulation sim = new DeterministicSimulation(seed);
        SimulatedCluster cluster = new SimulatedCluster(sim, 4, 0.2);
        sim.checkAfterEachStep(cluster::violatedInvariant);

        scheduleTyping(sim, cluster, 4, 80);
        sim.runUntil(HORIZON_MS, STEP_LIMIT);
        cluster.settle();
        return new Run(sim, cluster);
    }

    private static void scheduleTyping(DeterministicSimulation sim, SimulatedCluster cluster,
                                       int instanceCount, int edits) {
        long at = 0;
        for (int i = 0; i < edits; i++) {
            at += sim.delayBetween(5, 60);
            int instance = 1 + sim.random().nextInt(instanceCount);
            int position = sim.random().nextInt(40);
            boolean deleting = sim.random().nextInt(100) < 20;
            String text = String.valueOf((char) ('a' + sim.random().nextInt(26)));

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
                Instances disagree after everything settled - seed %d.

                Reproduce with exactly this seed; the interleaving is identical on any machine. \
                Add it to REGRESSION_SEEDS once fixed.

                texts: %s

                history:
                %s""",
                sim.seed(), texts, String.join(System.lineSeparator(), sim.history()))
            .hasSize(1);
    }
}
