package dev.collabedit.crdt;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public final class VersionVector {
    private static final VersionVector EMPTY = new VersionVector(Map.of());

    private final Map<Integer, Long> maxSeen;

    private VersionVector(Map<Integer, Long> maxSeen) {
        this.maxSeen = maxSeen;
    }

    public static VersionVector empty() {
        return EMPTY;
    }

    public static VersionVector of(Map<Integer, Long> maxSeen) {
        return maxSeen.isEmpty() ? EMPTY : new VersionVector(Map.copyOf(maxSeen));
    }

    public long get(int replicaId) {
        return maxSeen.getOrDefault(replicaId, -1L);
    }

    private Set<Integer> replicas() {
        return Collections.unmodifiableSet(maxSeen.keySet());
    }

    public boolean isEmpty() {
        return maxSeen.isEmpty();
    }

    public VersionVector join(VersionVector other) {
        if (other.isEmpty()) {
            return this;
        }
        if (isEmpty()) {
            return other;
        }
        Map<Integer, Long> merged = new HashMap<>(maxSeen);
        other.maxSeen.forEach((replica, lamport) -> merged.merge(replica, lamport, Math::max));
        return new VersionVector(Map.copyOf(merged));
    }

    public static VersionVector meet(Iterable<VersionVector> vectors) {
        Map<Integer, Long> result = null;
        for (VersionVector v : vectors) {
            if (result == null) {
                result = new HashMap<>(v.maxSeen);
                continue;
            }
            result.keySet().retainAll(v.maxSeen.keySet());
            result.replaceAll((replica, lamport) -> Math.min(lamport, v.maxSeen.get(replica)));
        }
        return result == null || result.isEmpty() ? EMPTY : new VersionVector(Map.copyOf(result));
    }

    public static Integer laggingReplica(VersionVector stable, VersionVector target) {
        Integer worst = null;
        long worstGap = 0;
        for (int replica : target.replicas()) {
            long gap = target.get(replica) - stable.get(replica);
            if (gap > worstGap) {
                worstGap = gap;
                worst = replica;
            }
        }
        return worst;
    }

    public Map<Integer, Long> asMap() {
        return new TreeMap<>(maxSeen);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof VersionVector other && maxSeen.equals(other.maxSeen);
    }

    @Override
    public int hashCode() {
        return maxSeen.hashCode();
    }

    @Override
    public String toString() {
        return new TreeMap<>(maxSeen).toString();
    }
}
