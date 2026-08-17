package dev.collabedit.crdt;

public record Id(int replicaId, long lamport) implements Comparable<Id> {
    public Id {
        if (lamport < 0) {
            throw new IllegalArgumentException("lamport cannot be negative: " + lamport);
        }
    }

    public Id plus(int offset) {
        return new Id(replicaId, lamport + offset);
    }

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
