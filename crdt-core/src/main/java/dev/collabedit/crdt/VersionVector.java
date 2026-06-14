package dev.collabedit.crdt;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The highest Lamport clock value seen from each replica.
 *
 * <p><b>What this is NOT for:</b> detecting duplicate operations. A Lamport clock is not contiguous
 * within a replica (it jumps when remote operations arrive), so "I have seen everything from A up
 * to 48" cannot tell a hole left by a lost operation from a hole left by a clock jump. Duplicate
 * insertions are detected by checking whether a character with that identifier is already in the
 * structure; duplicate deletions are harmless, because setting a tombstone is idempotent. Expanded
 * on in {@code docs/crdt-design.md}, section 2.
 *
 * <p><b>What it is for:</b>
 * <ul>
 *   <li>causal stability for tombstone GC (ADR-0005) — {@link #meet},</li>
 *   <li>the "what do I have" exchange on reconnect (CP6),</li>
 *   <li>diagnostics: which replica is blocking GC.</li>
 * </ul>
 *
 * <p>An immutable structure. With a few dozen replicas per document, copying the map on every
 * operation would be wasteful, so a mutable working variant lives inside {@link RgaDocument} and
 * this class represents a snapshot.
 */
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

    /**
     * The highest lamport seen from a given replica; {@code -1} when we have seen nothing from it.
     */
    public long get(int replicaId) {
        return maxSeen.getOrDefault(replicaId, -1L);
    }

    public Set<Integer> replicas() {
        return Collections.unmodifiableSet(maxSeen.keySet());
    }

    public boolean isEmpty() {
        return maxSeen.isEmpty();
    }

    public VersionVector with(int replicaId, long lamport) {
        if (get(replicaId) >= lamport) {
            return this;
        }
        Map<Integer, Long> next = new HashMap<>(maxSeen);
        next.put(replicaId, lamport);
        return new VersionVector(Map.copyOf(next));
    }

    /**
     * The meet (component-wise minimum) — the point up to which <b>every</b> given replica has
     * certainly seen the history.
     *
     * <p>This is the {@code stableVersion} from ADR-0005: operations below that boundary are
     * causally stable, so their tombstones may be physically removed.
     *
     * <p>Note the edge case: if any replica has seen <i>nothing</i> from replica R, then the minimum
     * for R is {@code -1} and nothing from R is stable. That is intentional — a newly connected
     * replica must not unblock GC merely because its version vector is empty.
     */
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

    /**
     * The replica holding {@code stableVersion} back the most relative to a given target state —
     * that is, the answer to "why is GC stuck" (the metric from ADR-0005). {@code null} when
     * nothing is lagging.
     */
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

    /** A copy as a plain map — for serialisation and for assertions in tests. */
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
