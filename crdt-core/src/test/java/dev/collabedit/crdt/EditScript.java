package dev.collabedit.crdt;

import java.util.List;
import java.util.Random;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;

final class EditScript {
    private EditScript() {
    }

    record Cmd(int replicaSeed, boolean insert, int positionSeed, int lengthSeed, String text) { }

    static Arbitrary<List<Cmd>> scripts() {
        return scripts(1, 40);
    }

    static Arbitrary<List<Cmd>> scripts(int minSize, int maxSize) {
        Arbitrary<Cmd> command = Combinators.combine(
            Arbitraries.integers().between(0, 255),
            Arbitraries.of(true, true, false),
            Arbitraries.integers().between(0, 100_000),
            Arbitraries.integers().between(1, 4),
            Arbitraries.strings().withCharRange('a', 'z').ofMinLength(1).ofMaxLength(4)
        ).as(Cmd::new);

        return command.list().ofMinSize(minSize).ofMaxSize(maxSize);
    }

    static void runIndependently(ReplicaSet replicas, List<Cmd> script) {
        for (Cmd cmd : script) {
            applyOne(replicas, cmd);
        }
    }

    static void runInterleaved(ReplicaSet replicas, List<Cmd> script, Random random, int roundSize) {
        int executed = 0;
        for (Cmd cmd : script) {
            applyOne(replicas, cmd);
            if (++executed % roundSize == 0) {
                replicas.deliverSome(random, 3);
            }
        }
    }

    private static void applyOne(ReplicaSet replicas, Cmd cmd) {
        int replica = cmd.replicaSeed() % replicas.size();
        int length = replicas.get(replica).visibleLength();
        if (cmd.insert() || length == 0) {
            replicas.insert(replica, cmd.positionSeed() % (length + 1), cmd.text());
        } else {
            int position = cmd.positionSeed() % length;
            replicas.delete(replica, position, Math.min(cmd.lengthSeed(), length - position));
        }
    }
}
