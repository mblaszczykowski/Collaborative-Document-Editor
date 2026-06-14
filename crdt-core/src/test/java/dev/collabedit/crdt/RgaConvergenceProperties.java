package dev.collabedit.crdt;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Random;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;

/**
 * The most important tests in the whole project.
 *
 * <p>Each of them answers a specific "what real failure mode does this catch" — none exists for
 * coverage. The table is in the README.
 *
 * <p>The tests run by default with a <b>fixed seed</b> (determinism — a counterexample can be
 * reproduced with one command), and in the nightly build with a random seed and 10× the number of
 * tries ({@code -Pjqwik.thorough}). A counterexample found overnight goes into the repository as an
 * ordinary regression test.
 */
class RgaConvergenceProperties {

    @Provide
    Arbitrary<List<EditScript.Cmd>> editScripts() {
        return EditScript.scripts();
    }

    /**
     * <b>Convergence.</b> The replicas edit entirely independently, then exchange operations, each
     * in a different random order. All of them must end up with identical text.
     *
     * <p><b>Failure mode:</b> merging is not commutative — that is, changes are silently lost or
     * duplicated when two people type at once. This is the bug the whole project exists to avoid,
     * and the only one that cannot be caught by clicking around manually.
     */
    @Property
    void convergence(
        @ForAll("editScripts") List<EditScript.Cmd> script,
        @ForAll @IntRange(min = 2, max = 5) int replicaCount,
        @ForAll @LongRange(min = 0, max = Long.MAX_VALUE) long deliverySeed
    ) {
        ReplicaSet replicas = new ReplicaSet(replicaCount, ArrayBlockList::new);
        EditScript.runIndependently(replicas, script);
        replicas.deliverAllShuffled(new Random(deliverySeed));

        assertThat(replicas.texts())
            .as("the replicas did not converge:%n%s", replicas.diagnostics())
            .allMatch(text -> text.equals(replicas.texts().getFirst()));
    }

    /**
     * <b>Convergence with interleaved editing and delivery.</b> The replicas exchange some of
     * their operations while typing, so later edits are made on state that already includes other
     * people's changes.
     *
     * <p><b>Failure mode:</b> bugs that only appear when a replica generates an operation on state
     * already merged with somebody else's changes — that is, in normal use rather than in the
     * artificial arrangement "everyone types in isolation, then synchronises".
     *
     * <p><b>This test exists because it was missing.</b> The original suite contained only the
     * non-interleaved scenario, in which a replica never receives an operation before generating
     * its own — so the entire Lamport clock bumping path ({@code docs/crdt-design.md} section 1)
     * was never executed once. Found by deliberately introducing a mutation into production code
     * and checking whether the tests would catch it.
     */
    @Property
    void convergenceWithInterleavedDelivery(
        @ForAll("editScripts") List<EditScript.Cmd> script,
        @ForAll @IntRange(min = 2, max = 5) int replicaCount,
        @ForAll @LongRange(min = 0, max = Long.MAX_VALUE) long deliverySeed
    ) {
        ReplicaSet replicas = new ReplicaSet(replicaCount, ArrayBlockList::new);
        Random random = new Random(deliverySeed);
        EditScript.runInterleaved(replicas, script, random, 2);
        replicas.deliverAllShuffled(random);

        assertThat(replicas.allConverged())
            .as("the replicas did not converge with interleaved editing and delivery:%n%s", replicas.diagnostics())
            .isTrue();
        assertThat(replicas.hasPendingOps())
            .as("operations are stuck in the buffer:%n%s", replicas.diagnostics())
            .isFalse();
    }

    /**
     * <b>No orphaned operations.</b> Once everything has been delivered, the pending-operation
     * buffer must be empty in every replica.
     *
     * <p><b>Failure mode:</b> an operation stuck in the buffer forever, because its readiness
     * condition was stated too strictly. The text would then look converged (every replica agrees
     * in <i>not</i> having that change), so a convergence test alone would not detect it.
     */
    @Property
    void noOperationStaysPending(
        @ForAll("editScripts") List<EditScript.Cmd> script,
        @ForAll @IntRange(min = 2, max = 5) int replicaCount,
        @ForAll @LongRange(min = 0, max = Long.MAX_VALUE) long deliverySeed
    ) {
        ReplicaSet replicas = new ReplicaSet(replicaCount, ArrayBlockList::new);
        EditScript.runIndependently(replicas, script);
        replicas.deliverAllShuffled(new Random(deliverySeed));

        assertThat(replicas.hasPendingOps())
            .as("operations are stuck in the buffer:%n%s", replicas.diagnostics())
            .isFalse();
    }

    /**
     * <b>Idempotence.</b> Delivering every operation twice gives the same result as delivering it
     * once.
     *
     * <p><b>Failure mode:</b> a retransmission after reconnect (CP6) or a repeated message from
     * Redis (ADR-0004) duplicates text. Without this property the entire architecture with an
     * unreliable broadcast is unsafe.
     */
    @Property
    void idempotence(
        @ForAll("editScripts") List<EditScript.Cmd> script,
        @ForAll @IntRange(min = 2, max = 4) int replicaCount,
        @ForAll @LongRange(min = 0, max = Long.MAX_VALUE) long deliverySeed
    ) {
        ReplicaSet once = new ReplicaSet(replicaCount, ArrayBlockList::new);
        EditScript.runIndependently(once, script);
        once.deliverAllShuffled(new Random(deliverySeed));

        ReplicaSet twice = new ReplicaSet(replicaCount, ArrayBlockList::new);
        EditScript.runIndependently(twice, script);
        twice.deliverAllShuffledTwice(new Random(deliverySeed));

        assertThat(twice.texts().getFirst())
            .as("double delivery changed the result")
            .isEqualTo(once.texts().getFirst());
        assertThat(twice.allConverged()).isTrue();
    }

    /**
     * <b>Delivery order does not matter.</b> The same set of operations delivered in two different
     * random orders gives the same text.
     *
     * <p><b>Failure mode:</b> the algorithm is deterministic but order-dependent — that is, two
     * backend instances (ADR-0004) diverge despite holding the full set of operations. This is
     * insidious, because such a system works correctly in single-server tests and only breaks when
     * scaled out.
     */
    @Property
    void deliveryOrderDoesNotMatter(
        @ForAll("editScripts") List<EditScript.Cmd> script,
        @ForAll @LongRange(min = 0, max = Long.MAX_VALUE) long seedA,
        @ForAll @LongRange(min = 0, max = Long.MAX_VALUE) long seedB
    ) {
        ReplicaSet runA = new ReplicaSet(3, ArrayBlockList::new);
        EditScript.runIndependently(runA, script);
        runA.deliverAllShuffled(new Random(seedA));

        ReplicaSet runB = new ReplicaSet(3, ArrayBlockList::new);
        EditScript.runIndependently(runB, script);
        runB.deliverAllShuffled(new Random(seedB));

        assertThat(runB.texts().getFirst()).isEqualTo(runA.texts().getFirst());
    }

    /**
     * <b>Agreement with a reference model.</b> On a single replica the CRDT must behave exactly
     * like a {@link StringBuilder} — checked after every operation, not only at the end.
     *
     * <p><b>Failure mode:</b> a bug in mapping a visible position to an identifier, in splitting
     * blocks, or in run-length compression. Such bugs can be invisible to a convergence test,
     * because every replica is wrong in the same way — they converge on shared but <i>incorrect</i>
     * text.
     */
    @Property
    void singleReplicaBehavesLikeStringBuilder(@ForAll("editScripts") List<EditScript.Cmd> script) {
        RgaDocument document = new RgaDocument(1);
        StringBuilder reference = new StringBuilder();

        for (EditScript.Cmd cmd : script) {
            int length = reference.length();
            if (cmd.insert() || length == 0) {
                int position = cmd.positionSeed() % (length + 1);
                document.insert(position, cmd.text());
                reference.insert(position, cmd.text());
            } else {
                int position = cmd.positionSeed() % length;
                int count = Math.min(cmd.lengthSeed(), length - position);
                document.delete(position, count);
                reference.delete(position, position + count);
            }
            assertThat(document.text()).isEqualTo(reference.toString());
        }
    }

    /**
     * <b>The RGA linearisation invariant.</b> In every replica, for every block, everything lying
     * between it and its origin must have a higher timestamp.
     *
     * <p><b>Failure mode:</b> a bug in which <i>every</i> replica is wrong in the same way.
     * Convergence tests compare replicas against each other, so such a bug passes them — the
     * replicas agree on the same incorrect structure. This test looks at the structure itself and
     * has no way of being fooled by a shared error.
     */
    @Property
    void rgaLinearizationInvariantHolds(
        @ForAll("editScripts") List<EditScript.Cmd> script,
        @ForAll @IntRange(min = 2, max = 4) int replicaCount,
        @ForAll @LongRange(min = 0, max = Long.MAX_VALUE) long deliverySeed
    ) {
        ReplicaSet replicas = new ReplicaSet(replicaCount, ArrayBlockList::new);
        Random random = new Random(deliverySeed);
        EditScript.runInterleaved(replicas, script, random, 2);
        replicas.deliverAllShuffled(random);

        for (int i = 0; i < replicas.size(); i++) {
            assertThat(RgaInvariants.violations(replicas.get(i)))
                .as("replica %d has an invalid structure", i)
                .isEmpty();
        }
    }

    /**
     * <b>Run-length compression actually works.</b> After ordinary continuous typing the document
     * must not fall apart into one-character blocks.
     *
     * <p><b>Failure mode:</b> the block-merging condition is too strict and never holds.
     * Correctness does not suffer, but the entire benefit of the block representation disappears —
     * a 100 000-character document is 100 000 objects again. A bug invisible to correctness tests
     * and expensive; hence a separate test rather than only a benchmark.
     */
    @Property
    void sequentialTypingCompressesIntoFewBlocks(
        @ForAll @IntRange(min = 20, max = 200) int characters
    ) {
        RgaDocument document = new RgaDocument(1);
        for (int i = 0; i < characters; i++) {
            document.insert(i, "x");
        }
        assertThat(document.text()).hasSize(characters);
        assertThat(document.blockCount())
            .as("typing %d characters in a row produced %d blocks instead of 1", characters, document.blockCount())
            .isEqualTo(1);
        assertThat(RgaInvariants.fragmentation(document)).isEmpty();
    }

    /**
     * <b>GC preserves the text.</b> Removing causally stable tombstones must not change the
     * visible text or corrupt the structure.
     *
     * <p><b>Failure mode:</b> GC deletes a node that is another character's origin — the structure
     * breaks immediately, but the symptom appears only at the next insertion in that spot. See
     * ADR-0005.
     */
    @Property
    void gcPreservesText(
        @ForAll("editScripts") List<EditScript.Cmd> script,
        @ForAll @IntRange(min = 2, max = 4) int replicaCount,
        @ForAll @LongRange(min = 0, max = Long.MAX_VALUE) long deliverySeed
    ) {
        ReplicaSet replicas = new ReplicaSet(replicaCount, ArrayBlockList::new);
        EditScript.runIndependently(replicas, script);
        replicas.deliverAllShuffled(new Random(deliverySeed));

        // Every replica has now seen everything, so stableVersion is simply the meet of their
        // version vectors — the whole history is causally stable.
        VersionVector stable = VersionVector.meet(
            java.util.stream.IntStream.range(0, replicas.size())
                .mapToObj(i -> replicas.get(i).version())
                .toList());

        for (int i = 0; i < replicas.size(); i++) {
            RgaDocument document = replicas.get(i);
            String before = document.text();
            document.gc(stable);
            assertThat(document.text())
                .as("GC changed the text in replica %d", i)
                .isEqualTo(before);
        }

        // After GC the structure must still accept new edits — this is exactly where deleting a
        // node that was somebody's origin shows up.
        RgaDocument first = replicas.get(0);
        first.insert(first.visibleLength(), "x");
        assertThat(first.text()).endsWith("x");
    }
}
