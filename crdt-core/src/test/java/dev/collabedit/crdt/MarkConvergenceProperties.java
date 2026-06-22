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
     * A script command: either a text edit or a mark operation.
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

    /**
     * <b>The sweep agrees with a naive resolver.</b> The one assertion in this file that is not
     * self-referential.
     *
     * <h2>What every other property here could not see</h2>
     * All of them compare the replicas against <b>each other</b>. Every replica runs the same code,
     * so a mark layer that is wrong in one way is wrong in the same way everywhere and every one of
     * those assertions holds. Measured rather than argued: with {@link MarkSet#spans(BlockList)}
     * replaced by {@code return List.of();} all four of the original properties stayed green —
     * {@code allFormattingConverged} is vacuously true when everyone returns nothing, and
     * {@code spansAreDisjointAndOrdered} loops over spans and so asserts nothing when there are
     * none. Reversing the priority order — oldest mark wins instead of newest — was invisible for
     * the same reason.
     *
     * <p>{@link NaiveMarkResolver} is the answer the text side has had since CP2 in the form of a
     * {@code StringBuilder} reference model: an independent implementation, obviously correct and
     * far too slow to ship, that the optimised one is measured against. It resolves anchors from
     * the block list rather than through {@code MarkSet}, so the two share nothing but the
     * document.
     *
     * <p>The coverage check below is the anti-vacuity guard this file needed as much as the oracle:
     * without it a generator that stopped producing marks would leave the property green while
     * comparing two empty lists, which is the failure mode being fixed, one level up.
     */
    @Property
    @net.jqwik.api.Label("the mark sweep agrees with an independent naive resolver")
    void sweepAgreesWithNaiveResolver(
        @ForAll("scripts") List<Cmd> script, @ForAll @IntRange(min = 0, max = 9999) int seed) {

        Random random = new Random(seed);
        ReplicaSet replicas = new ReplicaSet(REPLICAS, TreapBlockList::new);

        run(replicas, script, random, true);
        replicas.deliverAllShuffled(random);

        boolean anyFormatting = false;
        for (int i = 0; i < REPLICAS; i++) {
            RgaDocument document = replicas.get(i);
            List<MarkSet.Span> sweep = document.formatting();
            List<MarkSet.Span> naive = NaiveMarkResolver.spans(document);
            anyFormatting |= !sweep.isEmpty();
            assertThat(sweep)
                .as("replica %d: the sweep in MarkSet and the naive resolver disagree.%n"
                    + "text  = %s%nsweep = %s%nnaive = %s", i, document.text(), sweep, naive)
                .isEqualTo(naive);
        }

        net.jqwik.api.statistics.Statistics.label("formatted")
            .collect(anyFormatting)
            .coverage(checker -> checker.check(true).percentage(p -> p > 20.0));
    }

    /**
     * <b>Repeated delivery changes nothing.</b>
     *
     * <p>This used to deliver everything twice and assert only that the replicas still agreed —
     * which is "double delivery converges", not "double delivery is a no-op". A mark that
     * duplicated identically on every replica passed it. The text side gets this right
     * ({@code RgaConvergenceProperties.idempotence} builds a single-delivery baseline and
     * compares), and the fix here is the same shape: two runs of the same script, one delivery
     * each way, compared against each other.
     */
    @Property
    @net.jqwik.api.Label("repeated delivery does not change the formatting")
    void formattingIsIdempotent(@ForAll("scripts") List<Cmd> script, @ForAll @IntRange(min = 0, max = 9999) int seed) {
        ReplicaSet once = new ReplicaSet(REPLICAS, TreapBlockList::new);
        run(once, script, new Random(seed), false);
        once.deliverAllShuffled(new Random(seed));

        ReplicaSet twice = new ReplicaSet(REPLICAS, TreapBlockList::new);
        run(twice, script, new Random(seed), false);
        twice.deliverAllShuffledTwice(new Random(seed));

        assertThat(twice.allFormattingConverged())
            .as("retransmitting marks made the replicas disagree: %s", twice.formattings())
            .isTrue();
        assertThat(twice.get(0).formatting())
            .as("delivering every mark twice produced DIFFERENT formatting from delivering it "
                + "once — the mark layer is not idempotent.%nonce  = %s%ntwice = %s",
                once.get(0).formatting(), twice.get(0).formatting())
            .isEqualTo(once.get(0).formatting());
        assertThat(twice.get(0).text()).isEqualTo(once.get(0).text());
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
