package dev.collabedit.crdt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.Supplier;

final class ReplicaSet {
    private final List<RgaDocument> replicas = new ArrayList<>();
    private final List<Emitted> emitted = new ArrayList<>();
    private final List<Set<Integer>> delivered = new ArrayList<>();

    record Emitted(int sourceReplica, Op op) { }

    ReplicaSet(int count, Supplier<BlockList> storage) {
        for (int i = 0; i < count; i++) {
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

    void insert(int replicaIndex, int position, String text) {
        emitted.add(new Emitted(replicaIndex, replicas.get(replicaIndex).insert(position, text)));
    }

    void delete(int replicaIndex, int position, int count) {
        emitted.add(new Emitted(replicaIndex, replicas.get(replicaIndex).delete(position, count)));
    }

    void addMark(int replicaIndex, int from, int to, String type) {
        emitted.add(new Emitted(replicaIndex,
            replicas.get(replicaIndex).addMark(from, to, type, null)));
    }

    void removeMark(int replicaIndex, int from, int to, String type) {
        emitted.add(new Emitted(replicaIndex,
            replicas.get(replicaIndex).removeMark(from, to, type)));
    }

    List<List<MarkSet.Span>> formattings() {
        return replicas.stream().map(RgaDocument::formatting).toList();
    }

    boolean allFormattingConverged() {
        List<List<MarkSet.Span>> all = formattings();
        return all.stream().allMatch(spans -> spans.equals(all.getFirst()));
    }

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

    List<String> texts() {
        return replicas.stream().map(RgaDocument::text).toList();
    }

    boolean allConverged() {
        List<String> texts = texts();
        return texts.stream().allMatch(t -> t.equals(texts.getFirst()));
    }

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
