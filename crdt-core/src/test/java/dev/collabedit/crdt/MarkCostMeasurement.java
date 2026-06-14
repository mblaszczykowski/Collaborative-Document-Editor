package dev.collabedit.crdt;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Ile faktycznie kosztuje warstwa znaczników (CP15).
 *
 * <p>Plan v2 zakładał w punkcie 5.5, że kotwice mogą zablokować garbage collector: skoro
 * każdy znacznik zaczepia dwa tombstone'y i chroni je przed usunięciem, to dokument
 * z tysiącami komentarzy przestanie oddawać pamięć. To jest obawa <b>sformułowana przed
 * implementacją</b> i CP15 ma ją potwierdzić albo obalić liczbą, a nie przymiotnikiem.
 *
 * <p>To nie jest test wydajności i <b>nie mierzy czasu</b>. Czas na współdzielonej maszynie
 * z Postgresem, Redisem i przeglądarką w tle nie znaczy nic; liczba bajtów i liczba
 * odzyskanych tombstone'ów znaczą tyle samo niezależnie od obciążenia. Asercje są luźne
 * z rozmysłem — mają wyłapać zmianę <b>rzędu wielkości</b>, a nie utrwalać dokładną liczbę,
 * która zmieni się przy pierwszej korekcie formatu przewodowego.
 *
 * <p>Wyniki trafiają do README (sekcja „Koszt warstwy znaczników"). Gdy się rozjadą,
 * trzeba poprawić README, a nie asercję.
 */
class MarkCostMeasurement {

    private static final int TEXT_LENGTH = 50_000;

    /** Ile znaczników nakłada dokument „gęsty" — mniej więcej jeden na akapit. */
    private static final int DENSE_MARKS = 500;

    private static RgaDocument document() {
        return new RgaDocument(1, new TreapBlockList());
    }

    private static int snapshotBytes(RgaDocument document) {
        return SnapshotCodec.encode(document.snapshot()).length;
    }

    @Test
    @DisplayName("znaczniki kosztują w snapshocie tyle, ile ważą — nie powielają tekstu")
    void marksCostProportionallyToTheirCount() {
        RgaDocument plain = document();
        plain.insert(0, "x".repeat(TEXT_LENGTH));
        int withoutMarks = snapshotBytes(plain);

        RgaDocument marked = document();
        marked.insert(0, "x".repeat(TEXT_LENGTH));
        for (int i = 0; i < DENSE_MARKS; i++) {
            int from = i * (TEXT_LENGTH / DENSE_MARKS);
            marked.addMark(from, from + 20, MarkTypes.BOLD, null);
        }
        int withMarks = snapshotBytes(marked);

        int perMark = (withMarks - withoutMarks) / DENSE_MARKS;
        System.out.printf(
            "%nKOSZT ZNACZNIKÓW W SNAPSHOCIE%n"
                + "  tekst %,d znaków, bez znaczników:  %,d B  (run-length ściska go do JEDNEGO bloku,%n"
                + "                                             więc długość tekstu prawie nic tu nie waży)%n"
                + "  ten sam tekst, %d znaczników:      %,d B%n"
                + "  narzut na znacznik:                %d B%n",
            TEXT_LENGTH, withoutMarks, DENSE_MARKS, withMarks, perMark);

        // Znacznik to dwie kotwice (replika + lamport + strona) plus typ i wartość.
        // Setki bajtów oznaczałyby, że koder zapisuje coś więcej — na przykład kopiuje
        // objęty tekst, co jest dokładnie tym błędem, przed którym broni Peritext.
        assertThat(perMark)
            .as("znacznik waży w snapshocie tyle, co fragment tekstu — koder coś powiela")
            .isLessThan(120);
    }

    /**
     * Median of eleven calls, after a warm-up.
     *
     * <p>An earlier version timed a single call and reported 0.8 ms one run and 4.7 ms the next
     * for the same 400 marks on the same machine and the same code. A five-fold spread makes a
     * before/after comparison meaningless, and a before/after comparison is the only thing this
     * table is for.
     */
    private static long medianResolveNanos(RgaDocument document) {
        // Warm-up: the first calls pay for JIT compilation and for filling the structures, so
        // timing them would describe the JVM rather than the algorithm.
        for (int i = 0; i < 20; i++) {
            document.formatting();
        }
        long[] samples = new long[11];
        for (int sample = 0; sample < samples.length; sample++) {
            long began = System.nanoTime();
            document.formatting();
            samples[sample] = System.nanoTime() - began;
        }
        java.util.Arrays.sort(samples);
        return samples[samples.length / 2];
    }

    /**
     * Lays {@code markCount} ranges over the text <b>without letting them overlap</b>.
     *
     * <p>The width is capped at one less than the stride, and that cap is the whole point of
     * this method. The previous version used a fixed width of 30 characters at every mark
     * count, which is fine while the stride is larger than 30 — and at 6400 marks over 50 000
     * characters the stride is 7. The ranges then overlapped four to five deep, and since they
     * all carried the same type and the same value, {@code mergeAdjacent} collapsed the entire
     * document into <b>one</b> span.
     *
     * <p>So the last row of the table measured a different shape of problem from the three
     * above it (1600 ranges → 1600 spans, 6400 ranges → 1 span) while being labelled as a plain
     * ×4 in mark count. That row was added in CP20 specifically to make the O(m²) → O(m log m)
     * difference visible, which made it the one row that had to be comparable and the one row
     * that was not.
     */
    private static RgaDocument withDisjointMarks(int markCount) {
        RgaDocument document = document();
        document.insert(0, "x".repeat(TEXT_LENGTH));
        int stride = TEXT_LENGTH / markCount;
        int width = Math.min(30, stride - 1);
        for (int i = 0; i < markCount; i++) {
            int from = i * stride;
            document.addMark(from, from + width, MarkTypes.BOLD, null);
        }
        return document;
    }

    @Test
    @DisplayName("resolving formatting scales with the mark count, geometry held constant")
    void resolvingFormattingScalesWithMarkCount() {
        System.out.printf("%nCOST OF RESOLVING FORMATTING (%,d characters, disjoint ranges)%n"
            + "  marks    spans   median resolve      growth%n", TEXT_LENGTH);

        long previous = 0;
        int previousCount = 0;
        for (int markCount : new int[] {100, 400, 1_600, 6_400}) {
            RgaDocument document = withDisjointMarks(markCount);
            int spans = document.formatting().size();
            long elapsed = medianResolveNanos(document);

            System.out.printf("  %6d   %6d   %,12d ns%s%n", markCount, spans, elapsed,
                previous == 0 ? "" : String.format("   x%.1f at x%d marks",
                    (double) elapsed / previous, markCount / previousCount));
            previous = elapsed;
            previousCount = markCount;

            // Every count must produce one span per mark. This is the assertion that keeps the
            // table honest: if a future change makes the ranges overlap again, the series stops
            // comparing like with like, and it should fail here rather than be read as a
            // complexity regression.
            assertThat(spans)
                .as("ranges are meant to stay disjoint at %d marks, so spans should equal marks; "
                    + "%d spans means the geometry changed and the row is not comparable "
                    + "with the others", markCount, spans)
                .isEqualTo(markCount);

            // The only timing assertion: resolving has to TERMINATE. Not an assertion on
            // milliseconds — on a machine shared with Postgres, Redis and a browser, time means
            // nothing and the test would be randomly red. This bound catches an order-of-
            // magnitude regression, not noise.
            assertThat(elapsed)
                .as("resolving formatting with %d marks took over a second", markCount)
                .isLessThan(1_000_000_000L);
        }
    }

    /**
     * The cost of <b>overlap depth</b>, which is a separate variable from the mark count.
     *
     * <p>Split out of the table above rather than mixed into it. Depth drives a different part
     * of the sweep: it is the size of the {@code TreeSet} of active ranges that
     * {@code winnersOf} reads the front of, whereas the mark count drives how many boundaries
     * there are to walk. Holding the mark count fixed at 1600 and varying only the width shows
     * the second cost without the first moving underneath it.
     *
     * <p>Note what happens to the span count as depth rises: identical marks stacked on the
     * same text resolve to one winner, so {@code mergeAdjacent} folds the result back down.
     * More work in, less output out — which is exactly why measuring this together with the
     * mark count produced a number that could not be interpreted.
     */
    @Test
    @DisplayName("overlap depth is a second, separate cost from the number of marks")
    void overlapDepthIsMeasuredSeparately() {
        final int markCount = 1_600;
        int stride = TEXT_LENGTH / markCount;

        System.out.printf("%nCOST OF OVERLAP DEPTH (%,d characters, %d marks held constant)%n"
            + "  width   approx depth   spans   median resolve%n", TEXT_LENGTH, markCount);

        for (int width : new int[] {stride - 1, stride * 2, stride * 4, stride * 8}) {
            RgaDocument document = document();
            document.insert(0, "x".repeat(TEXT_LENGTH));
            for (int i = 0; i < markCount; i++) {
                int from = i * stride;
                document.addMark(from, Math.min(from + width, TEXT_LENGTH), MarkTypes.BOLD, null);
            }

            int spans = document.formatting().size();
            long elapsed = medianResolveNanos(document);
            System.out.printf("  %5d   %12d   %5d   %,12d ns%n",
                width, Math.max(1, width / stride), spans, elapsed);

            assertThat(elapsed)
                .as("resolving formatting at overlap width %d took over a second", width)
                .isLessThan(1_000_000_000L);
        }
    }

    private static final int CHARS = 1_000;
    private static final int MARKS = 100;

    /** Dokument napisany JEDNYM ciągiem — cały tekst mieści się w jednym bloku run-length. */
    private static RgaDocument writtenInOneRun() {
        RgaDocument document = document();
        document.insert(0, "x".repeat(CHARS));
        return document;
    }

    /**
     * Ten sam tekst wpisany tak, że każdy znak trafia do osobnego bloku.
     *
     * <p>Wstawianie na pozycji 0 nie da się skleić run-lengthem: każdy nowy znak zaczepia się
     * o początek dokumentu, a nie o poprzednik. Skrajność w drugą stronę niż `writtenInOneRun`,
     * i to jest cały sens tej pary — <b>koszt kotwicy zależy od tego, jak tekst powstał</b>.
     */
    private static RgaDocument writtenCharByChar() {
        RgaDocument document = document();
        for (int i = 0; i < CHARS; i++) {
            document.insert(0, "x");
        }
        return document;
    }

    private static int collectAfterDeletingEverything(RgaDocument document, int markCount) {
        for (int i = 0; i < markCount; i++) {
            int from = i * (CHARS / Math.max(markCount, 1));
            document.addMark(from, from + 5, MarkTypes.LINK, "https://example.test");
        }
        document.delete(0, CHARS);
        return document.gc(VersionVector.of(Map.of(1, 1_000_000L)));
    }

    @Test
    @DisplayName("po CP18 kotwica nie blokuje już pamięci — blok oddaje treść, zachowując tożsamość")
    void anchorsNoLongerBlockReclamation() {
        int oneRunWithout = collectAfterDeletingEverything(writtenInOneRun(), 0);
        int oneRunWith = collectAfterDeletingEverything(writtenInOneRun(), MARKS);
        int perCharWithout = collectAfterDeletingEverything(writtenCharByChar(), 0);
        int perCharWith = collectAfterDeletingEverything(writtenCharByChar(), MARKS);

        System.out.printf(
            "%nWPŁYW KOTWIC NA GC (%d znaków, wszystko skasowane, %d znaczników = %d kotwic)%n"
                + "  jak powstał tekst          bez znaczników   ze znacznikami   odzyskane%n"
                + "  jednym ciągiem (1 blok)    %10d       %10d       %6.0f%%%n"
                + "  znak po znaku (%d bloków) %10d       %10d       %6.0f%%%n",
            CHARS, MARKS, 2 * MARKS,
            oneRunWithout, oneRunWith, 100.0 * oneRunWith / oneRunWithout,
            CHARS, perCharWithout, perCharWith, 100.0 * perCharWith / perCharWithout);

        // CP15 ZMIERZYŁ, ŻE TU BYŁO ZERO. Ta asercja jest tamtą asercją odwróconą, i to jest
        // cała treść CP18.
        //
        // Wcześniej kotwica blokowała CAŁY blok run-length: GC działa na blokach, a blok,
        // w którym choć jeden znak jest kotwicą, zostawał w całości. Tekst napisany ciągiem
        // to jeden blok, więc jedna kotwica ratowała cały dokument — 0% odzysku.
        //
        // Teraz blok, którego nie wolno usunąć, ODDAJE TREŚĆ, zachowując tożsamość, długość
        // i pozycję. Wszystkie trzy powody trzymania tombstone'u (origin, kotwica, stabilność
        // przyczynowa) potrzebują identyfikatora znaku, nie jego treści — więc nic się nie psuje,
        // a pamięć wraca.
        assertThat(oneRunWith)
            .as("blok run-length z kotwicą znów blokuje pamięć — regresja CP18")
            .isEqualTo(oneRunWithout);

        // Drugie skrajne ustawienie musi zostać bez zmian: tam GC i tak potrafił usuwać bloki
        // w całości, więc CP18 nie miał czego poprawiać. Gdyby ta liczba SPADŁA, znaczyłoby to,
        // że porzucanie treści weszło w miejsce usuwania bloku, zamiast obok niego.
        assertThat(perCharWith)
            .as("odzysk przy drobnych blokach spadł — porzucanie treści wyparło usuwanie bloków")
            .isGreaterThanOrEqualTo(perCharWithout - 2 * MARKS);
    }
}
