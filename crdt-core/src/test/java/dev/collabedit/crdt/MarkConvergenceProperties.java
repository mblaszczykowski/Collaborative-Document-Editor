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
 * Property testy warstwy formatowania (CP13).
 *
 * <h2>Dlaczego to nie wystarczy sprawdzić na tekście</h2>
 * Zbieżność samego tekstu <b>nie dowodzi</b> zbieżności formatowania. Znaczniki mają własny
 * zbiór operacji i własną regułę rozstrzygania konfliktów, więc dwie repliki mogą mieć
 * identyczną treść i różny wygląd. Test porównujący wyłącznie tekst przepuściłby taki błąd
 * w całości — i byłaby to dokładnie ta klasa dziury, którą CP2 wykrył u siebie mutacją.
 *
 * <h2>Co dokładnie jest tu dowodzone</h2>
 * Że po dostarczeniu tego samego zbioru operacji — w dowolnej kolejności, z dowolnymi
 * powtórzeniami — wszystkie repliki mają <b>identyczne odcinki formatowania</b>, a nie tylko
 * identyczny tekst.
 */
class MarkConvergenceProperties {

    private static final int REPLICAS = 3;
    private static final String[] TYPES = {MarkTypes.BOLD, MarkTypes.ITALIC, MarkTypes.LINK};

    /**
     * Polecenie skryptu: edycja tekstu albo operacja znacznikowa.
     *
     * @param kind 0 = wstawienie, 1 = usunięcie, 2 = nadanie znacznika, 3 = zdjęcie znacznika
     */
    record Cmd(int replicaSeed, int kind, int positionSeed, int lengthSeed, int typeSeed, String text) { }

    @Provide
    Arbitrary<List<Cmd>> scripts() {
        Arbitrary<Cmd> command = net.jqwik.api.Combinators.combine(
            Arbitraries.integers().between(0, 255),
            // Wstawienia przeważają, żeby dokument miał na czym stać. Bez tego większość
            // scenariuszy formatowałaby pusty tekst, czyli nie testowałaby niczego.
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
                    // Znacznik wymaga niepustego zakresu — na pustym dokumencie nie ma
                    // czego formatować i polecenie jest pomijane.
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
    @net.jqwik.api.Label("formatowanie zbiega się niezależnie od kolejności dostarczenia")
    void formattingConverges(@ForAll("scripts") List<Cmd> script, @ForAll @IntRange(min = 0, max = 9999) int seed) {
        Random random = new Random(seed);
        ReplicaSet replicas = new ReplicaSet(REPLICAS, TreapBlockList::new);

        run(replicas, script, random, false);
        replicas.deliverAllShuffled(random);

        assertThat(replicas.allConverged())
            .as("tekst się rozjechał: %s", replicas.texts())
            .isTrue();
        assertThat(replicas.allFormattingConverged())
            .as("TEKST jest zbieżny, a FORMATOWANIE nie: %s", replicas.formattings())
            .isTrue();
    }

    @Property
    @net.jqwik.api.Label("formatowanie zbiega się również, gdy repliki formatują na cudzym stanie")
    void formattingConvergesWithInterleavedDelivery(
        @ForAll("scripts") List<Cmd> script, @ForAll @IntRange(min = 0, max = 9999) int seed) {

        Random random = new Random(seed);
        ReplicaSet replicas = new ReplicaSet(REPLICAS, TreapBlockList::new);

        // Przeplot jest tu ważniejszy niż przy samym tekście: dopiero on tworzy sytuację,
        // w której replika nadaje znacznik na zakresie ZAWIERAJĄCYM cudzy, świeżo scalony
        // tekst — czyli tę, w której kotwice muszą się zachować poprawnie.
        run(replicas, script, random, true);
        replicas.deliverAllShuffled(random);

        assertThat(replicas.allConverged()).isTrue();
        assertThat(replicas.allFormattingConverged())
            .as("formatowanie rozjechało się przy edycji na stanie scalonym: %s", replicas.formattings())
            .isTrue();
    }

    @Property
    @net.jqwik.api.Label("powtórzone dostarczenie nie zmienia formatowania")
    void formattingIsIdempotent(@ForAll("scripts") List<Cmd> script, @ForAll @IntRange(min = 0, max = 9999) int seed) {
        Random random = new Random(seed);
        ReplicaSet replicas = new ReplicaSet(REPLICAS, TreapBlockList::new);

        run(replicas, script, random, false);
        replicas.deliverAllShuffledTwice(random);

        assertThat(replicas.allFormattingConverged())
            .as("retransmisja znaczników zmieniła wynik — brak idempotencji: %s", replicas.formattings())
            .isTrue();
    }

    @Property
    @net.jqwik.api.Label("odcinki formatowania są rozłączne i uporządkowane")
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
                    .as("odcinki formatowania zachodzą na siebie albo są nieuporządkowane: %s", spans)
                    .isGreaterThanOrEqualTo(previousEnd);
                assertThat(span.from()).isLessThan(span.to());
                assertThat(span.to())
                    .as("odcinek formatowania wychodzi poza tekst (%d znaków): %s", visible, spans)
                    .isLessThanOrEqualTo(visible);
                previousEnd = span.to();
            }
        }
    }
}
