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

class MarkConvergenceProperties {
    private static final int REPLICAS = 3;
    private static final String[] TYPES = {MarkTypes.BOLD, MarkTypes.ITALIC, MarkTypes.LINK};

    record Cmd(int replicaSeed, int kind, int positionSeed, int lengthSeed, int typeSeed, String text) { }

    @Provide
    Arbitrary<List<Cmd>> scripts() {
        Arbitrary<Cmd> command = net.jqwik.api.Combinators.combine(
            Arbitraries.integers().between(0, 255),
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

        run(replicas, script, random, true);
        replicas.deliverAllShuffled(random);

        assertThat(replicas.allConverged()).isTrue();
        assertThat(replicas.allFormattingConverged())
            .as("formatting diverged when editing on merged state: %s", replicas.formattings())
            .isTrue();
    }

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
                + "once - the mark layer is not idempotent.%nonce  = %s%ntwice = %s",
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
