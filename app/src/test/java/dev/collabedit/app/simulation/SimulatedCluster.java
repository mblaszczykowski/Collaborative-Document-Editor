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

final class SimulatedCluster {
    private static final long MIN_DELIVERY_MS = 1;
    private static final long MAX_DELIVERY_MS = 40;

    private static final long ANTI_ENTROPY_INTERVAL_MS = 250;

    enum Durability {
        SYNCHRONOUS,
        DEFERRED,
        ACKNOWLEDGE_ON_BROADCAST
    }

    private static final long PERSIST_DELAY_MS = 60;

    private final DeterministicSimulation sim;
    private final double dropRate;
    private final Durability durability;

    private final List<Op> log = new ArrayList<>();

    private final Set<Id> logged = new LinkedHashSet<>();

    private final Map<Integer, Instance> instances = new TreeMap<>();

    SimulatedCluster(DeterministicSimulation sim, int instanceCount, double dropRate) {
        this(sim, instanceCount, dropRate, Durability.SYNCHRONOUS);
    }

    SimulatedCluster(DeterministicSimulation sim, int instanceCount, double dropRate,
                     Durability durability) {
        this.sim = sim;
        this.dropRate = dropRate;
        this.durability = durability;
        for (int id = 1; id <= instanceCount; id++) {
            instances.put(id, new Instance(id));
        }
        for (Instance instance : instances.values()) {
            scheduleAntiEntropy(instance);
        }
    }

    private final class Instance {
        private final int id;
        private RgaDocument document;
        private int logCursor;
        private boolean down;
        private boolean partitioned;

        private final Set<Id> received = new LinkedHashSet<>();

        private long receivedFingerprint;

        boolean receive(Id id) {
            if (!received.add(id)) {
                return false;
            }
            receivedFingerprint ^= mix(id.hashCode());
            return true;
        }

        private static long mix(long value) {
            long x = value * 0x9E3779B97F4A7C15L;
            x ^= (x >>> 31);
            x *= 0xBF58476D1CE4E5B9L;
            return x ^ (x >>> 29);
        }

        private final Map<Id, Op> unacknowledged = new LinkedHashMap<>();

        private final Set<Id> acknowledged = new LinkedHashSet<>();

        Instance(int id) {
            this.id = id;
            this.document = new RgaDocument(id);
        }
    }

    void edit(int instanceId, int position, String text) {
        Instance instance = instances.get(instanceId);
        if (instance.down) {
            return;
        }
        int clamped = Math.min(position, instance.document.visibleLength());
        Op op = instance.document.insert(clamped, text);

        instance.receive(op.id());
        sim.record("edit i" + instanceId + " @" + clamped + " " + describe(op));
        submit(instance, op);
    }

    void deleteAt(int instanceId, int position, int count) {
        Instance instance = instances.get(instanceId);
        if (instance.down || instance.document.visibleLength() == 0) {
            return;
        }
        int clampedPosition = Math.min(position, instance.document.visibleLength() - 1);
        int clampedCount = Math.min(count, instance.document.visibleLength() - clampedPosition);
        Op op = instance.document.delete(clampedPosition, clampedCount);

        instance.receive(op.id());
        sim.record("delete i" + instanceId + " @" + clampedPosition + "+" + clampedCount);
        submit(instance, op);
    }

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
        retransmit(instance);
        scheduleAntiEntropy(instance);
    }

    void partition(int instanceId, boolean cut) {
        Instance instance = instances.get(instanceId);
        instance.partitioned = cut;
        sim.record((cut ? "partition i" : "heal i") + instanceId);
    }

    private void submit(Instance instance, Op op) {
        if (durability == Durability.SYNCHRONOUS) {
            append(op);
            broadcast(instance, op);
            return;
        }
        instance.unacknowledged.put(op.id(), op);
        broadcast(instance, op);

        if (durability == Durability.ACKNOWLEDGE_ON_BROADCAST) {
            instance.unacknowledged.remove(op.id());
            instance.acknowledged.add(op.id());
            sim.record("  ack-on-broadcast i" + instance.id + " " + describe(op));
        }

        int instanceId = instance.id;
        sim.schedule(PERSIST_DELAY_MS, "persist", () -> persist(instanceId, op));
    }

    private void persist(int instanceId, Op op) {
        Instance instance = instances.get(instanceId);
        if (instance.down) {
            sim.record("  persist lost (i" + instanceId + " down) " + describe(op));
            return;
        }
        append(op);
        instance.unacknowledged.remove(op.id());
        instance.acknowledged.add(op.id());
        sim.record("  ack i" + instanceId + " " + describe(op));
    }

    private void append(Op op) {
        if (logged.add(op.id())) {
            log.add(op);
        }
    }

    private void retransmit(Instance instance) {
        if (instance.unacknowledged.isEmpty()) {
            return;
        }
        sim.record("  retransmit i" + instance.id + " x" + instance.unacknowledged.size());
        for (Op op : List.copyOf(instance.unacknowledged.values())) {
            instance.receive(op.id());
            instance.document.apply(op);
            submit(instance, op);
        }
    }

    private void broadcast(Instance from, Op op) {
        if (from.partitioned) {
            sim.record("  fastpath blocked at source i" + from.id);
            return;
        }
        for (Instance target : instances.values()) {
            if (target.id == from.id) {
                continue;
            }
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

    void restartAll() {
        for (Integer id : List.copyOf(instances.keySet())) {
            restart(id);
        }
    }

    void settle() {
        for (Instance instance : instances.values()) {
            if (!instance.down) {
                sweep(instance);
            }
        }
    }

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

    List<Id> acknowledgedButNotLogged() {
        List<Id> broken = new ArrayList<>();
        for (Instance instance : instances.values()) {
            for (Id id : instance.acknowledged) {
                if (!logged.contains(id)) {
                    broken.add(id);
                }
            }
        }
        return broken;
    }

    int unacknowledgedCount() {
        return instances.values().stream().mapToInt(i -> i.unacknowledged.size()).sum();
    }

    int acknowledgedCount() {
        return instances.values().stream().mapToInt(i -> i.acknowledged.size()).sum();
    }

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
