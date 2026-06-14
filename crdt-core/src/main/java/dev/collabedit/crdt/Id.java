package dev.collabedit.crdt;

/**
 * The identifier of a single character in the document.
 *
 * <p>{@code lamport} is a <b>Lamport clock</b> value, not a per-replica sequence counter. That
 * distinction is a correctness requirement rather than a matter of style — details, with a
 * counterexample, in {@code docs/crdt-design.md}, section 1. In short: RGA's insertion rule ("walk
 * right while the elements you meet have a higher timestamp") is correct only when an element
 * created causally later is guaranteed to have a higher timestamp. A per-replica sequence counter
 * does not have that property.
 *
 * <p>The practical consequence: {@code lamport} is <b>not contiguous</b> within a replica — it jumps
 * when remote operations arrive. That is why the version vector is not used here for duplicate
 * detection (see {@link VersionVector}).
 *
 * <p>Natural order: {@code lamport} first, then {@code replicaId} as the tie-break. Ties are common
 * — two replicas working concurrently on the same state will generate the same {@code lamport}.
 * {@code replicaId} makes the order total without needing an arbiter, which is the entire point of
 * the choice in ADR-0001.
 */
public record Id(int replicaId, long lamport) implements Comparable<Id> {

    public Id {
        if (lamport < 0) {
            throw new IllegalArgumentException("lamport cannot be negative: " + lamport);
        }
    }

    /** The identifier of the character {@code offset} further along the same run-length block. */
    public Id plus(int offset) {
        return new Id(replicaId, lamport + offset);
    }

    /**
     * Whether this identifier and {@code other} come from the same replica and are consecutive
     * clock values. Used when merging blocks (run-length) — see {@code docs/crdt-design.md},
     * section 3.
     */
    public boolean isDirectSuccessorOf(Id other) {
        return other != null && other.replicaId == replicaId && other.lamport + 1 == lamport;
    }

    @Override
    public int compareTo(Id other) {
        int byLamport = Long.compare(lamport, other.lamport);
        return byLamport != 0 ? byLamport : Integer.compare(replicaId, other.replicaId);
    }

    @Override
    public String toString() {
        return replicaId + "@" + lamport;
    }
}
