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

    @Test
    @DisplayName("wyliczanie wyglądu rośnie szybciej niż liniowo po liczbie znaczników")
    void resolvingFormattingScalesQuadratically() {
        System.out.printf("%nKOSZT WYLICZENIA WYGLĄDU (%d znaków)%n"
            + "  znaczników   odcinków   czas wyliczenia%n", TEXT_LENGTH);

        long previous = 0;
        // 6400 dołożone w CP20. Bez punktu, w którym liczba znaczników robi się duża, różnicy
        // między O(m²) a O(m log m) po prostu nie widać — a przy 100 i 400 nie widać jej wcale,
        // co samo w sobie jest wynikiem wartym zapisania.
        for (int markCount : new int[] {100, 400, 1_600, 6_400}) {
            RgaDocument document = document();
            document.insert(0, "x".repeat(TEXT_LENGTH));
            for (int i = 0; i < markCount; i++) {
                int from = i * (TEXT_LENGTH / markCount);
                document.addMark(from, from + 30, MarkTypes.BOLD, null);
            }

            // Rozgrzewka: pierwsze wywołanie płaci za kompilację JIT i za zapełnienie
            // struktur, więc mierzenie go opisywałoby JVM, a nie algorytm.
            for (int i = 0; i < 20; i++) {
                document.formatting();
            }

            // MEDIANA Z 11 PRÓBEK, nie pojedynczy pomiar (CP20). Poprzednia wersja mierzyła
            // jedno wywołanie i dawała przy 400 znacznikach raz 0,8 ms, raz 4,7 ms — pięciokrotny
            // rozrzut na tej samej maszynie i tym samym kodzie. Porównanie „przed i po" na takich
            // liczbach nie znaczy nic, a to jest jedyne, do czego ta tabela służy.
            int spans = 0;
            long[] samples = new long[11];
            for (int sample = 0; sample < samples.length; sample++) {
                long began = System.nanoTime();
                spans = document.formatting().size();
                samples[sample] = System.nanoTime() - began;
            }
            java.util.Arrays.sort(samples);
            long elapsed = samples[samples.length / 2];

            System.out.printf("  %10d   %8d   %,10d ns%s%n", markCount, spans, elapsed,
                previous == 0 ? "" : String.format("   (×%.1f przy ×4 znaczników)",
                    (double) elapsed / previous));
            previous = elapsed;

            // Jedyna asercja: rozstrzyganie ma się KOŃCZYĆ. Nie asercja na czas — na maszynie
            // dzielonej z Postgresem, Redisem i przeglądarką czas nie znaczy nic i test
            // byłby losowo czerwony. Ta granica wyłapie regresję o rząd wielkości, nie szum.
            assertThat(elapsed)
                .as("wyliczenie wyglądu przy %d znacznikach przekroczyło sekundę", markCount)
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
