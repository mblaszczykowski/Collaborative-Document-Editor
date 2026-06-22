package dev.collabedit.crdt;

import java.util.List;
import java.util.Random;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;

/**
 * A generator of random edit sequences for the property-based tests.
 *
 * <p>A command does not contain an absolute position but a <b>seed</b>, from which the position is
 * computed relative to the current text length. Without that, most generated commands would be
 * rejected as being outside the document, and the test would mostly be checking argument validation
 * rather than the algorithm.
 */
final class EditScript {

    private EditScript() {
    }

    /**
     * @param replicaSeed which replica performs the edit (modulo the replica count)
     * @param insert      true = insertion, false = deletion
     * @param positionSeed the position seed, taken modulo the current length
     * @param lengthSeed  how many characters to delete (capped at the end of the text)
     * @param text        the text to insert
     */
    record Cmd(int replicaSeed, boolean insert, int positionSeed, int lengthSeed, String text) { }

    static Arbitrary<List<Cmd>> scripts() {
        return scripts(1, 40);
    }

    static Arbitrary<List<Cmd>> scripts(int minSize, int maxSize) {
        Arbitrary<Cmd> command = Combinators.combine(
            Arbitraries.integers().between(0, 255),
            // Insertions outweigh deletions 2:1. With a 1:1 distribution the text would hover
            // around zero and most scenarios would be testing an empty document — that is, nothing
            // interesting.
            Arbitraries.of(true, true, false),
            Arbitraries.integers().between(0, 100_000),
            Arbitraries.integers().between(1, 4),
            // Short words from a narrow alphabet: when a counterexample appears I want to be able
            // to read the result and see what went wrong, not decipher random Unicode.
            Arbitraries.strings().withCharRange('a', 'z').ofMinLength(1).ofMaxLength(4)
        ).as(Cmd::new);

        return command.list().ofMinSize(minSize).ofMaxSize(maxSize);
    }

    /**
     * Runs a script over a set of replicas — every edit locally, with no propagation. Propagation
     * is a separate step, because its order is precisely what the test is about.
     */
    static void runIndependently(ReplicaSet replicas, List<Cmd> script) {
        for (Cmd cmd : script) {
            applyOne(replicas, cmd);
        }
    }

    /**
     * Runs a script with <b>interleaving</b>: every few edits the replicas exchange some of their
     * operations and then keep editing — now on state that includes other people's changes.
     *
     * <p>This is a scenario closer to reality than {@link #runIndependently}, but above all it is
     * the <b>only</b> scenario in which a replica generates an operation after receiving somebody
     * else's. Without it the Lamport clock bumping rule is never executed at all.
     *
     * <p>This paragraph used to continue "and the tests pass despite its removal — verified
     * experimentally". The first half was right and the conclusion drawn from it was not: the
     * tests passed despite its removal <b>with this scenario in place too</b>, because executing a
     * line is not the same as asserting on it. The mutation is caught by the timestamp condition
     * in {@link RgaInvariants#violations}, and this scenario is what makes that condition
     * reachable — necessary, not sufficient.
     */
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
