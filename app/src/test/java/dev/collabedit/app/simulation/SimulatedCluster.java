package dev.collabedit.app.simulation;

import dev.collabedit.crdt.Id;
import dev.collabedit.crdt.Op;
import dev.collabedit.crdt.RgaDocument;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The design from ADR-0004, running on the real merge algorithm, with the network under the
 * control of a seed (CP17).
 *
 * <h2>What is modelled</h2>
 * <ul>
 *   <li><b>Instances</b> — each holds its own {@link RgaDocument}. No leader, no distributed
 *       lock, no sticky sessions. This is the whole point of ADR-0004.</li>
 *   <li><b>The operation log</b> — append-only and authoritative, standing in for Postgres.
 *       Every edit lands here.</li>
 *   <li><b>The fast path</b> — best-effort broadcast standing in for Redis pub/sub. Messages
 *       are delayed by a random amount, may be dropped, and stop entirely across a partition.</li>
 *   <li><b>Anti-entropy</b> — an instance periodically reads the log past its cursor and merges
 *       whatever it missed.</li>
 * </ul>
 *
 * <p>The claim this exists to attack is the central one in ADR-0004: <i>a lost broadcast is a
 * latency problem, not a correctness problem.</i> If that is true, no amount of dropping,
 * delaying, reordering or crashing can leave two instances permanently disagreeing, because the
 * log is authoritative and anti-entropy always catches up.
 *
 * <h2>What is deliberately NOT modelled — the boundary of the claim</h2>
 * The log append is <b>synchronous with the edit</b>. That leaves out the window in which an
 * instance broadcasts an operation and dies before persisting it, which the README lists as a
 * real failure mode. It is left out because the repair for it lives in the <i>client</i> — the
 * unacknowledged-operation buffer, which releases only on write confirmation, not on broadcast
 * — and there is no client in this model. Simulating the server half alone would prove a
 * property the real system does not rely on.
 *
 * <p>That is the first thing to add when extending this, and until it is added, this class does
 * not speak to that failure mode at all.
 */
final class SimulatedCluster {

    /** How long a broadcast takes to arrive, when it arrives at all. */
    private static final long MIN_DELIVERY_MS = 1;
    private static final long MAX_DELIVERY_MS = 40;

    /** How often an instance sweeps the log for what it missed. */
    private static final long ANTI_ENTROPY_INTERVAL_MS = 250;

    private final DeterministicSimulation sim;
    private final double dropRate;

    /** Standing in for Postgres: append-only, ordered, authoritative. */
    private final List<Op> log = new ArrayList<>();

    /** Sorted so that iteration order never depends on hashing. */
    private final Map<Integer, Instance> instances = new TreeMap<>();

    SimulatedCluster(DeterministicSimulation sim, int instanceCount, double dropRate) {
        this.sim = sim;
        this.dropRate = dropRate;
        for (int id = 1; id <= instanceCount; id++) {
            instances.put(id, new Instance(id));
        }
        for (Instance instance : instances.values()) {
            scheduleAntiEntropy(instance);
        }
    }

    /** One backend instance: a CRDT replica plus a cursor into the authoritative log. */
    private final class Instance {
        private final int id;
        private RgaDocument document;
        private int logCursor;
        private boolean down;
        private boolean partitioned;

        /**
         * Every operation this instance has been handed, whether or not it could integrate it
         * yet.
         *
         * <p>Tracked here rather than read back from {@link RgaDocument#version()}, and the
         * difference is the first thing this simulation caught — <b>in itself</b>.
         *
         * <p>The invariant below originally compared instances by version vector, on the
         * assumption that an equal vector means an equal set of operations. It does not, and
         * {@code VersionVector}'s own javadoc says so in as many words: the Lamport clock jumps
         * when remote operations arrive, so "seen everything from A up to 48" cannot tell a
         * hole left by a dropped message from a hole left by a clock jump. With a lossy fast
         * path, holes are the normal case. Two instances can therefore share a vector while
         * holding genuinely different operations, and comparing their text is comparing two
         * different questions.
         *
         * <p>Every scenario failed on the first run because of that, and none of those failures
         * were bugs in the system. Recorded because a simulation that is wrong in this
         * direction — confidently reporting bugs that are not there — is more expensive than
         * one that finds nothing.
         */
        private final Set<Id> received = new LinkedHashSet<>();

        /**
         * Order-independent fingerprint of {@link #received}, maintained incrementally.
         *
         * <p>The invariant runs after <b>every</b> event, so comparing the sets themselves cost
         * O(instances x operations) per step and turned a ten-second suite into minutes. XOR is
         * commutative, so the fingerprint does not care what order operations arrived in — which
         * is exactly the property being compared — and updating it is O(1) per operation.
         *
         * <p>Two different sets could in principle collide. That risk is why a suspected
         * violation is confirmed against the real sets before it is reported: fast in the common
         * case, exact in the case that matters.
         */
        private long receivedFingerprint;

        /** @return whether this operation was new to the instance */
        boolean receive(Id id) {
            if (!received.add(id)) {
                return false;
            }
            // Mixed before XOR: raw hashCodes of adjacent ids share most of their bits, and
            // XOR of similar values collides far more readily than XOR of scattered ones.
            receivedFingerprint ^= mix(id.hashCode());
            return true;
        }

        private static long mix(long value) {
            long x = value * 0x9E3779B97F4A7C15L;
            x ^= (x >>> 31);
            x *= 0xBF58476D1CE4E5B9L;
            return x ^ (x >>> 29);
        }

        Instance(int id) {
            this.id = id;
            this.document = new RgaDocument(id);
        }
    }

    // ------------------------------------------------------------------
    // Actions a scenario can take
    // ------------------------------------------------------------------

    /** A user types on this instance. */
    void edit(int instanceId, int position, String text) {
        Instance instance = instances.get(instanceId);
        if (instance.down) {
            // A down instance serves nobody; the edit simply does not happen. Modelling it as
            // buffered would be modelling the client, which this simulation does not have.
            return;
        }
        int clamped = Math.min(position, instance.document.visibleLength());
        Op op = instance.document.insert(clamped, text);

        // Authoritative first, fast path second — the order ADR-0004 specifies. Reversing it is
        // what makes a lost broadcast a correctness problem instead of a latency one.
        // The cursor is deliberately NOT advanced here.
        //
        // It marks how far into the log this instance has read, and appending its own entry
        // says nothing about the entries other instances added in the meantime. An earlier
        // version set it to log.size() — which had the instance claim it had consumed
        // everything, so anti-entropy skipped the operations it was missing, permanently. The
        // convergence tests caught it; the per-step invariant did not, because the instances
        // genuinely held different operation sets the whole time.
        //
        // The sweep will re-apply this instance's own operation later. That is free: apply() is
        // idempotent and returns DUPLICATE.
        log.add(op);
        instance.receive(op.id());
        sim.record("edit i" + instanceId + " @" + clamped + " " + describe(op));

        broadcast(instance, op);
    }

    /** Deletes {@code count} characters, if the instance has that many. */
    void deleteAt(int instanceId, int position, int count) {
        Instance instance = instances.get(instanceId);
        if (instance.down || instance.document.visibleLength() == 0) {
            return;
        }
        int clampedPosition = Math.min(position, instance.document.visibleLength() - 1);
        int clampedCount = Math.min(count, instance.document.visibleLength() - clampedPosition);
        Op op = instance.document.delete(clampedPosition, clampedCount);

        log.add(op);
        instance.receive(op.id());
        sim.record("delete i" + instanceId + " @" + clampedPosition + "+" + clampedCount);

        broadcast(instance, op);
    }

    /**
     * Kills an instance. Its in-memory replica is gone; the log is not.
     *
     * <p>This is the case the README calls "loss of the in-memory replica": state is rebuilt
     * from the log, so the only thing lost is time.
     */
    void crash(int instanceId) {
        Instance instance = instances.get(instanceId);
        if (instance.down) {
            return;
        }
        instance.down = true;
        instance.document = null;
        instance.logCursor = 0;
        instance.received.clear();
        instance.receivedFingerprint = 0;
        sim.record("crash i" + instanceId);
    }

    /** Brings an instance back, rebuilding it from the log exactly as a restart would. */
    void restart(int instanceId) {
        Instance instance = instances.get(instanceId);
        if (!instance.down) {
            return;
        }
        instance.down = false;
        instance.document = new RgaDocument(instance.id);
        instance.logCursor = 0;
        instance.received.clear();
        instance.receivedFingerprint = 0;
        sim.record("restart i" + instanceId);
        sweep(instance);
        scheduleAntiEntropy(instance);
    }

    /** Cuts an instance off the fast path, leaving the log reachable — a Redis outage. */
    void partition(int instanceId, boolean cut) {
        Instance instance = instances.get(instanceId);
        instance.partitioned = cut;
        sim.record((cut ? "partition i" : "heal i") + instanceId);
    }

    // ------------------------------------------------------------------
    // The unreliable fast path
    // ------------------------------------------------------------------

    private void broadcast(Instance from, Op op) {
        if (from.partitioned) {
            sim.record("  fastpath blocked at source i" + from.id);
            return;
        }
        for (Instance target : instances.values()) {
            if (target.id == from.id) {
                continue;
            }
            // Drawn per recipient, not per message: Redis delivering to one subscriber and not
            // another is the interesting case, and a per-message draw would never produce it.
            if (sim.random().nextDouble() < dropRate) {
                sim.record("  drop " + from.id + "->" + target.id + " " + describe(op));
                continue;
            }
            long delay = sim.delayBetween(MIN_DELIVERY_MS, MAX_DELIVERY_MS);
            int targetId = target.id;
            sim.schedule(delay, "deliver", () -> deliver(targetId, from.id, op));
        }
    }

    private void deliver(int targetId, int fromId, Op op) {
        Instance target = instances.get(targetId);
        if (target.down || target.partitioned) {
            sim.record("  undelivered " + fromId + "->" + targetId + " " + describe(op));
            return;
        }
        target.receive(op.id());
        RgaDocument.ApplyResult result = target.document.apply(op);
        sim.record("  deliver " + fromId + "->" + targetId + " " + describe(op) + " " + result);
    }

    // ------------------------------------------------------------------
    // Anti-entropy — the mechanism that makes a dropped broadcast survivable
    // ------------------------------------------------------------------

    private void scheduleAntiEntropy(Instance instance) {
        int id = instance.id;
        sim.schedule(ANTI_ENTROPY_INTERVAL_MS, "anti-entropy", () -> {
            Instance current = instances.get(id);
            if (!current.down) {
                sweep(current);
                scheduleAntiEntropy(current);
            }
        });
    }

    /** Reads everything in the log past this instance's cursor and merges it. */
    private void sweep(Instance instance) {
        int from = instance.logCursor;
        if (from >= log.size()) {
            return;
        }
        for (int i = from; i < log.size(); i++) {
            Op op = log.get(i);
            instance.receive(op.id());
            instance.document.apply(op);
        }
        instance.logCursor = log.size();
        sim.record("  sweep i" + instance.id + " " + from + "->" + log.size());
    }

    /**
     * Brings every live instance fully up to date.
     *
     * <p>Called once after the event queue drains. Convergence is a claim about where the system
     * settles, not about every intermediate instant — asserting it mid-flight would be asserting
     * that the network is never in use.
     */
    void settle() {
        for (Instance instance : instances.values()) {
            if (!instance.down) {
                sweep(instance);
            }
        }
    }

    // ------------------------------------------------------------------
    // Observation
    // ------------------------------------------------------------------

    /** Text of every live instance, keyed by instance id. */
    Map<Integer, String> texts() {
        Map<Integer, String> result = new LinkedHashMap<>();
        for (Instance instance : instances.values()) {
            if (!instance.down) {
                result.put(instance.id, instance.document.text());
            }
        }
        return result;
    }

    int liveInstanceCount() {
        return (int) instances.values().stream().filter(instance -> !instance.down).count();
    }

    int logSize() {
        return log.size();
    }

    /**
     * The invariant checked after <b>every</b> event, not just at the end.
     *
     * <p>Two instances that have seen the same set of operations must show the same text. That
     * is convergence stated in a form that can be tested mid-flight, and it is strictly stronger
     * than comparing final states: a system that reaches the right answer only because
     * everything eventually arrives, while disagreeing whenever two replicas genuinely hold the
     * same operations, is order-dependent — and would pass a final-state check while being
     * broken.
     *
     * @return a description of the violation, or {@code null} when the invariant holds
     */
    String violatedInvariant() {
        Map<List<Long>, Instance> seen = new LinkedHashMap<>();
        for (Instance instance : instances.values()) {
            if (instance.down) {
                continue;
            }
            List<Long> fingerprint =
                List.of((long) instance.received.size(), instance.receivedFingerprint);
            Instance previous = seen.putIfAbsent(fingerprint, instance);
            if (previous == null) {
                continue;
            }
            String previousText = previous.document.text();
            String text = instance.document.text();
            if (previousText.equals(text)) {
                continue;
            }
            // Only now pay for the exact comparison, and only to rule out a fingerprint
            // collision. Reporting a convergence bug that is really a hash collision would
            // waste more time than the check costs.
            if (!previous.received.equals(instance.received)) {
                continue;
            }
            return "instances i" + previous.id + " and i" + instance.id
                + " were given the same " + instance.received.size()
                + " operations but show different text: " + quote(previousText)
                + " vs " + quote(text);
        }
        return null;
    }

    private static String quote(String text) {
        String shortened = text.length() <= 60 ? text : text.substring(0, 60) + "…";
        return "\"" + shortened + "\"";
    }

    private static String describe(Op op) {
        return op.getClass().getSimpleName() + "(" + op.id().replicaId() + ":"
            + op.id().lamport() + ")";
    }
}
