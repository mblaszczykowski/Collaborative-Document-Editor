package dev.collabedit.crdt;

import java.util.List;
import java.util.Random;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;

/**
 * Generator losowych sekwencji edycji dla testów property-based.
 *
 * <p>Polecenie nie zawiera pozycji bezwzględnej, tylko <b>ziarno</b>, z którego pozycja jest
 * wyliczana względem aktualnej długości tekstu. Bez tego większość wygenerowanych poleceń
 * byłaby odrzucona jako wykraczająca poza dokument, a test sprawdzałby głównie walidację
 * argumentów zamiast algorytmu.
 */
final class EditScript {

    private EditScript() {
    }

    /**
     * @param replicaSeed która replika wykonuje edycję (modulo liczba replik)
     * @param insert      true = wstawienie, false = usunięcie
     * @param positionSeed ziarno pozycji, brane modulo aktualna długość
     * @param lengthSeed  ile znaków usunąć (ograniczone do końca tekstu)
     * @param text        tekst do wstawienia
     */
    record Cmd(int replicaSeed, boolean insert, int positionSeed, int lengthSeed, String text) { }

    static Arbitrary<List<Cmd>> scripts() {
        return scripts(1, 40);
    }

    static Arbitrary<List<Cmd>> scripts(int minSize, int maxSize) {
        Arbitrary<Cmd> command = Combinators.combine(
            Arbitraries.integers().between(0, 255),
            // Wstawienia przeważają nad usunięciami 2:1. Przy rozkładzie 1:1 tekst
            // oscylowałby wokół zera i większość scenariuszy testowałaby pusty dokument —
            // czyli nie testowałaby niczego ciekawego.
            Arbitraries.of(true, true, false),
            Arbitraries.integers().between(0, 100_000),
            Arbitraries.integers().between(1, 4),
            // Krótkie słowa z wąskiego alfabetu: przy kontrprzykładzie chcę móc przeczytać
            // wynik i zobaczyć, co poszło nie tak, a nie odcyfrowywać losowy Unicode.
            Arbitraries.strings().withCharRange('a', 'z').ofMinLength(1).ofMaxLength(4)
        ).as(Cmd::new);

        return command.list().ofMinSize(minSize).ofMaxSize(maxSize);
    }

    /**
     * Wykonuje skrypt na zbiorze replik — każda edycja lokalnie, bez propagacji.
     * Propagacja jest osobnym krokiem, bo to właśnie jej kolejność jest przedmiotem testu.
     */
    static void runIndependently(ReplicaSet replicas, List<Cmd> script) {
        for (Cmd cmd : script) {
            applyOne(replicas, cmd);
        }
    }

    /**
     * Wykonuje skrypt z <b>przeplotem</b>: co kilka edycji repliki wymieniają się częścią
     * operacji, po czym edytują dalej — już na stanie uwzględniającym cudze zmiany.
     *
     * <p>To jest scenariusz bliższy rzeczywistości niż {@link #runIndependently}, ale przede
     * wszystkim jest to <b>jedyny</b> scenariusz, w którym replika generuje operację po
     * odebraniu cudzej. Bez niego reguła podbijania zegara Lamporta nie jest w ogóle
     * wykonywana, a testy przechodzą mimo jej usunięcia — sprawdzone eksperymentalnie
     * przez wprowadzenie mutacji do kodu produkcyjnego.
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
