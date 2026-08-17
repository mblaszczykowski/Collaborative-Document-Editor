package dev.collabedit.crdt;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;

class RgaConvergenceProperties {
    @Provide
    Arbitrary<List<EditScript.Cmd>> editScripts() {
        return EditScript.scripts();
    }

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

    @Property
    void gcPreservesText(
        @ForAll("editScripts") List<EditScript.Cmd> script,
        @ForAll @IntRange(min = 2, max = 4) int replicaCount,
        @ForAll @LongRange(min = 0, max = Long.MAX_VALUE) long deliverySeed
    ) {
        ReplicaSet replicas = new ReplicaSet(replicaCount, ArrayBlockList::new);
        EditScript.runIndependently(replicas, script);
        replicas.deliverAllShuffled(new Random(deliverySeed));

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

        RgaDocument first = replicas.get(0);
        first.insert(first.visibleLength(), "x");
        assertThat(first.text()).endsWith("x");
    }

    @Property
    void gcNeverRemovesWhatAnotherReplicaStillShows(
        @ForAll("editScripts") List<EditScript.Cmd> script,
        @ForAll @IntRange(min = 2, max = 4) int replicaCount,
        @ForAll @LongRange(min = 0, max = Long.MAX_VALUE) long deliverySeed
    ) {
        ReplicaSet replicas = new ReplicaSet(replicaCount, TreapBlockList::new);
        Random random = new Random(deliverySeed);
        EditScript.runInterleaved(replicas, script, random, 2);

        VersionVector stable = VersionVector.meet(
            java.util.stream.IntStream.range(0, replicas.size())
                .mapToObj(i -> replicas.get(i).version())
                .toList());

        List<Set<Id>> heldBefore = new ArrayList<>();
        for (int i = 0; i < replicas.size(); i++) {
            heldBefore.add(idsIn(replicas.get(i), false));
        }
        for (int i = 0; i < replicas.size(); i++) {
            replicas.get(i).gc(stable);
        }

        for (int collector = 0; collector < replicas.size(); collector++) {
            Set<Id> removed = new HashSet<>(heldBefore.get(collector));
            removed.removeAll(idsIn(replicas.get(collector), false));
            if (removed.isEmpty()) {
                continue;
            }
            for (int other = 0; other < replicas.size(); other++) {
                if (other == collector) {
                    continue;
                }
                Set<Id> stillVisible = idsIn(replicas.get(other), true);
                stillVisible.retainAll(removed);
                assertThat(stillVisible)
                    .as("replica %d physically removed characters that replica %d is still "
                        + "DISPLAYING - it has not seen the deletion, so it can still anchor to "
                        + "them.%nstable = %s%ncollector = %s%nother = %s",
                        collector, other, stable.asMap(),
                        replicas.get(collector), replicas.get(other))
                            .isEmpty();
            }
        }
    }

    private static Set<Id> idsIn(RgaDocument document, boolean visibleOnly) {
        Set<Id> ids = new HashSet<>();
        for (Block block : document.blocks()) {
            if (visibleOnly && block.deleted()) {
                continue;
            }
            for (int offset = 0; offset < block.length(); offset++) {
                ids.add(block.idAt(offset));
            }
        }
        return ids;
    }
}
