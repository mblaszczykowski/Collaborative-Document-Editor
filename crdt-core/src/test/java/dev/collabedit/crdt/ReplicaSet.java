package dev.collabedit.crdt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.Supplier;

/**
 * A simulator of a set of replicas editing the same document — a test tool, not production code.
 *
 * <p>It models the worst possible case for the algorithm: the replicas edit <b>entirely
 * independently</b> (maximum concurrency) and only then exchange operations in a <b>random
 * order</b>. A real network is gentler; if the algorithm passes here, it passes everywhere.
 *
 * <p>There is deliberately no delivery ordering and no deduplication on the transport side — all
 * the work is to be done by {@link RgaDocument}. That is the point of this test.
 */
final class ReplicaSet {

    private final List<RgaDocument> replicas = new ArrayList<>();
    /** Operations together with the index of the replica that generated them. */
    private final List<Emitted> emitted = new ArrayList<>();
    /** Indices of operations already delivered to a given replica — to avoid duplicate delivery. */
    private final List<Set<Integer>> delivered = new ArrayList<>();

    record Emitted(int sourceReplica, Op op) { }

    ReplicaSet(int count, Supplier<BlockList> storage) {
        for (int i = 0; i < count; i++) {
            // Replica identifiers start at 1: 0 is easy to confuse with "no replica" when reading
            // logs.
            replicas.add(new RgaDocument(i + 1, storage.get()));
            delivered.add(new HashSet<>());
        }
    }

    int size() {
        return replicas.size();
    }

    RgaDocument get(int index) {
        return replicas.get(index);
    }

    /** A local insertion on a replica; the operation goes into the pool for later delivery. */
    void insert(int replicaIndex, int position, String text) {
        emitted.add(new Emitted(replicaIndex, replicas.get(replicaIndex).insert(position, text)));
    }

    /** A local deletion on a replica; the operation goes into the pool for later delivery. */
    void delete(int replicaIndex, int position, int count) {
        emitted.add(new Emitted(replicaIndex, replicas.get(replicaIndex).delete(position, count)));
    }

    /** Applying formatting (CP13); as above — the operation goes into the pool. */
    void addMark(int replicaIndex, int from, int to, String type) {
        emitted.add(new Emitted(replicaIndex,
            replicas.get(replicaIndex).addMark(from, to, type, null)));
    }

    /** Removing formatting (CP13). */
    void removeMark(int replicaIndex, int from, int to, String type) {
        emitted.add(new Emitted(replicaIndex,
            replicas.get(replicaIndex).removeMark(from, to, type)));
    }

    /**
     * Each replica's formatting — the equivalent of {@link #texts()} for the mark layer.
     *
     * <p>Convergence of the text alone does NOT prove convergence of the formatting: marks have
     * their own set of operations and their own resolution rule, so they can diverge on identical
     * text. A test comparing only the content would let such a bug through entirely.
     */
    List<List<MarkSet.Span>> formattings() {
        return replicas.stream().map(RgaDocument::formatting).toList();
    }

    boolean allFormattingConverged() {
        List<List<MarkSet.Span>> all = formattings();
        return all.stream().allMatch(spans -> spans.equals(all.getFirst()));
    }

    /**
     * Delivers every other replica's operations to each replica in a random order.
     *
     * <p>Each replica gets a <b>different</b> permutation — if they all got the same one, the test
     * would be checking a much weaker property (determinism under a fixed order rather than
     * commutativity).
     */
    void deliverAllShuffled(Random random) {
        for (int target = 0; target < replicas.size(); target++) {
            List<Op> inbox = new ArrayList<>();
            for (int i = 0; i < emitted.size(); i++) {
                if (emitted.get(i).sourceReplica() != target && delivered.get(target).add(i)) {
                    inbox.add(emitted.get(i).op());
                }
            }
            Collections.shuffle(inbox, random);
            replicas.get(target).applyAll(inbox);
        }
    }

    /**
     * Delivers a random <b>subset</b> of the not-yet-delivered operations to each replica.
     *
     * <p>It exists because without it the whole test suite had a blind spot: under the "all edits
     * locally first, then the whole exchange" scheme a replica never generates an operation
     * <i>after</i> receiving somebody else's. And that is exactly when the Lamport clock bumping
     * rule matters (the README, "What the Lamport clock is actually for").
     *
     * <p><b>This method is necessary and was not sufficient, and the javadoc here claimed
     * otherwise for a long time.</b> It used to end "without this method, removing the clock bump
     * failed no test at all" — true — and left the reader to conclude that with it, the mutation
     * is caught. It was not. Re-running the mutation showed all fifteen property tests still green
     * at 3000 tries: this method makes the code PATH execute, and nothing asserted anything
     * sensitive to its result. Convergence cannot be that assertion, because every replica runs
     * the same code and agrees on the same wrong answer.
     *
     * <p>What catches it is the timestamp condition in {@link RgaInvariants#violations} — a
     * structural claim about one replica, checked against the algorithm's rule rather than against
     * the other replicas. Interleaved delivery is what makes that condition reachable; the
     * condition is what makes it fail.
     */
    void deliverSome(Random random, int maxPerReplica) {
        for (int target = 0; target < replicas.size(); target++) {
            List<Integer> candidates = new ArrayList<>();
            for (int i = 0; i < emitted.size(); i++) {
                if (emitted.get(i).sourceReplica() != target && !delivered.get(target).contains(i)) {
                    candidates.add(i);
                }
            }
            Collections.shuffle(candidates, random);
            List<Op> inbox = new ArrayList<>();
            for (int i = 0; i < Math.min(maxPerReplica, candidates.size()); i++) {
                delivered.get(target).add(candidates.get(i));
                inbox.add(emitted.get(candidates.get(i)).op());
            }
            replicas.get(target).applyAll(inbox);
        }
    }

    /** As {@link #deliverAllShuffled}, but every operation is delivered twice. */
    void deliverAllShuffledTwice(Random random) {
        for (int target = 0; target < replicas.size(); target++) {
            List<Op> inbox = new ArrayList<>();
            for (int i = 0; i < emitted.size(); i++) {
                if (emitted.get(i).sourceReplica() != target) {
                    delivered.get(target).add(i);
                    inbox.add(emitted.get(i).op());
                    inbox.add(emitted.get(i).op());
                }
            }
            Collections.shuffle(inbox, random);
            replicas.get(target).applyAll(inbox);
        }
    }

    List<Emitted> emitted() {
        return List.copyOf(emitted);
    }

    /** Every replica's text — for convergence assertions. */
    List<String> texts() {
        return replicas.stream().map(RgaDocument::text).toList();
    }

    boolean allConverged() {
        List<String> texts = texts();
        return texts.stream().allMatch(t -> t.equals(texts.getFirst()));
    }

    /** Whether any replica has unapplied operations left — always a bug. */
    boolean hasPendingOps() {
        return replicas.stream().anyMatch(r -> r.pendingCount() > 0);
    }

    String diagnostics() {
        StringBuilder sb = new StringBuilder();
        for (RgaDocument r : replicas) {
            sb.append(r).append('\n');
        }
        return sb.toString();
    }
}
