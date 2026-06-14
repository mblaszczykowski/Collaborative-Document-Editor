package dev.collabedit.crdt;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Random;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

/**
 * Property tests for the formatting layer (CP13).
 *
 * <h2>Why checking the text is not enough</h2>
 * Convergence of the text alone does <b>not prove</b> convergence of the formatting. Marks have
 * their own set of operations and their own conflict resolution rule, so two replicas can have
 * identical content and a different appearance. A test comparing only the text would let such a bug
 * through entirely — and it would be exactly the class of blind spot CP2 found in itself by
 * mutation.
 *
 * <h2>What exactly is proven here</h2>
 * That after the same set of operations has been delivered — in any order, with any repetitions —
 * every replica has <b>identical formatting segments</b>, not merely identical text.
 */
class MarkConvergenceProperties {

    private static final int REPLICAS = 3;
    private static final String[] TYPES = {MarkTypes.BOLD, MarkTypes.ITALIC, MarkTypes.LINK};

    /**
     * Polecenie skryptu: edycja tekstu albo operacja znacznikowa.
     *
     * @param kind 0 = insert, 1 = delete, 2 = add mark, 3 = remove mark
     */
    record Cmd(int replicaSeed, int kind, int positionSeed, int lengthSeed, int typeSeed, String text) { }

    @Provide
    Arbitrary<List<Cmd>> scripts() {
        Arbitrary<Cmd> command = net.jqwik.api.Combinators.combine(
            Arbitraries.integers().between(0, 255),
            // Insertions dominate so the document has something to stand on. Without that most
            // scenarios would be formatting empty text, that is, testing nothing.
            Arbitraries.of(0, 0, 0, 1, 2, 2, 3),
            Arbitraries.integers().between(0, 100_000),
            Arbitraries.integers().between(1, 5),
            Arbitraries.integers().between(0, 100),
            Arbitraries.strings().withCharRange('a', 'z').ofMinLength(1).ofMaxLength(4)
        ).as(Cmd::new);
        return command.list().ofMinSize(5).ofMaxSize(40);
    }

    private static void run(ReplicaSet replicas, List<Cmd> script, Random random, boolean interleave) {
        int executed = 0;
        for (Cmd cmd : script) {
            int replica = cmd.replicaSeed() % replicas.size();
            int length = replicas.get(replica).visibleLength();
            String type = TYPES[cmd.typeSeed() % TYPES.length];

            switch (cmd.kind()) {
                case 1 -> {
                    if (length > 0) {
                        int position = cmd.positionSeed() % length;
                        replicas.delete(replica, position, Math.min(cmd.lengthSeed(), length - position));
                    }
                }
                case 2, 3 -> {
                    // A mark requires a non-empty range — on an empty document there is nothing
                    // to format and the command is skipped.
                    if (length >= 2) {
                        int from = cmd.positionSeed() % (length - 1);
                        int to = Math.min(length, from + 1 + (cmd.lengthSeed() % (length - from)));
                        if (cmd.kind() == 2) {
                            replicas.addMark(replica, from, to, type);
                        } else {
                            replicas.removeMark(replica, from, to, type);
                        }
                    }
                }
                default -> replicas.insert(replica, cmd.positionSeed() % (length + 1), cmd.text());
            }

            if (interleave && ++executed % 4 == 0) {
                replicas.deliverSome(random, 3);
            }
        }
    }

    @Property
    @net.jqwik.api.Label("formatting converges regardless of delivery order")
    void formattingConverges(@ForAll("scripts") List<Cmd> script, @ForAll @IntRange(min = 0, max = 9999) int seed) {
        Random random = new Random(seed);
        ReplicaSet replicas = new ReplicaSet(REPLICAS, TreapBlockList::new);

        run(replicas, script, random, false);
        replicas.deliverAllShuffled(random);

        assertThat(replicas.allConverged())
            .as("the text diverged: %s", replicas.texts())
            .isTrue();
        assertThat(replicas.allFormattingConverged())
            .as("the TEXT converged and the FORMATTING did not: %s", replicas.formattings())
            .isTrue();
    }

    @Property
    @net.jqwik.api.Label("formatting converges when replicas format on somebody else's state too")
    void formattingConvergesWithInterleavedDelivery(
        @ForAll("scripts") List<Cmd> script, @ForAll @IntRange(min = 0, max = 9999) int seed) {

        Random random = new Random(seed);
        ReplicaSet replicas = new ReplicaSet(REPLICAS, TreapBlockList::new);

        // Interleaving matters more here than for the text alone: only it creates the situation
        // where a replica applies a mark to a range CONTAINING somebody else's freshly merged text
        // — that is, the one where the anchors have to behave correctly.
        run(replicas, script, random, true);
        replicas.deliverAllShuffled(random);

        assertThat(replicas.allConverged()).isTrue();
        assertThat(replicas.allFormattingConverged())
            .as("formatting diverged when editing on merged state: %s", replicas.formattings())
            .isTrue();
    }

    @Property
    @net.jqwik.api.Label("repeated delivery does not change the formatting")
    void formattingIsIdempotent(@ForAll("scripts") List<Cmd> script, @ForAll @IntRange(min = 0, max = 9999) int seed) {
        Random random = new Random(seed);
        ReplicaSet replicas = new ReplicaSet(REPLICAS, TreapBlockList::new);

        run(replicas, script, random, false);
        replicas.deliverAllShuffledTwice(random);

        assertThat(replicas.allFormattingConverged())
            .as("retransmitting marks changed the result — not idempotent: %s", replicas.formattings())
            .isTrue();
    }

    @Property
    @net.jqwik.api.Label("formatting segments are disjoint and ordered")
    void spansAreDisjointAndOrdered(@ForAll("scripts") List<Cmd> script, @ForAll @IntRange(min = 0, max = 9999) int seed) {
        Random random = new Random(seed);
        ReplicaSet replicas = new ReplicaSet(REPLICAS, TreapBlockList::new);

        run(replicas, script, random, false);
        replicas.deliverAllShuffled(random);

        for (int i = 0; i < REPLICAS; i++) {
            List<MarkSet.Span> spans = replicas.get(i).formatting();
            int visible = replicas.get(i).visibleLength();
            int previousEnd = 0;
            for (MarkSet.Span span : spans) {
                assertThat(span.from())
                    .as("formatting segments overlap or are out of order: %s", spans)
                    .isGreaterThanOrEqualTo(previousEnd);
                assertThat(span.from()).isLessThan(span.to());
                assertThat(span.to())
                    .as("a formatting segment extends beyond the text (%d characters): %s", visible, spans)
                    .isLessThanOrEqualTo(visible);
                previousEnd = span.to();
            }
        }
    }
}
