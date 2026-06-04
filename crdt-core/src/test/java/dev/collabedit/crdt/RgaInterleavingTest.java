package dev.collabedit.crdt;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Zachowanie intencji autora przy współbieżnym pisaniu — czyli to, co odróżnia „algorytm
 * zbieżny" od „algorytm użyteczny".
 *
 * <p>Zbieżność gwarantuje, że wszyscy zobaczą <b>to samo</b>. Nie gwarantuje, że to coś będzie
 * miało sens. Klasyczna patologia to <b>przeplot</b>: dwie osoby piszą jednocześnie w tym samym
 * miejscu i wynik jest ich tekstami przeplecionymi znak po znaku.
 *
 * <p>Ta klasa zawiera zarówno przypadek, który RGA obsługuje poprawnie, jak i przypadek,
 * którego <b>nie obsługuje</b> — ten drugi jest tu celowo, jako wykonywalna dokumentacja
 * granicy algorytmu (ADR-0001, sekcja „Znane ograniczenie").
 */
class RgaInterleavingTest {

    /** Dwie repliki zsynchronizowane na wspólnym tekście, gotowe do współbieżnej edycji. */
    private static RgaDocument[] twoReplicasSharing(String base) {
        RgaDocument first = new RgaDocument(1);
        Op.Insert seed = first.insert(0, base);
        RgaDocument second = new RgaDocument(2);
        second.apply(seed);
        return new RgaDocument[] {first, second};
    }

    @Test
    @DisplayName("pisanie w przód: współbieżne słowa zostają zwarte")
    void forwardTypingStaysContiguous() {
        RgaDocument[] replicas = twoReplicasSharing("AB");
        RgaDocument anna = replicas[0];
        RgaDocument marek = replicas[1];

        // Obie osoby wpisują słowo między A i B, znak po znaku — tak, jak pisze człowiek.
        java.util.List<Op> fromAnna = new java.util.ArrayList<>();
        java.util.List<Op> fromMarek = new java.util.ArrayList<>();
        String annaWord = "hello";
        String marekWord = "world";
        for (int i = 0; i < annaWord.length(); i++) {
            fromAnna.add(anna.insert(1 + i, String.valueOf(annaWord.charAt(i))));
        }
        for (int i = 0; i < marekWord.length(); i++) {
            fromMarek.add(marek.insert(1 + i, String.valueOf(marekWord.charAt(i))));
        }

        anna.applyAll(fromMarek);
        marek.applyAll(fromAnna);

        assertThat(anna.text()).isEqualTo(marek.text());
        assertThat(anna.text())
            .as("słowo każdego autora musi zostać w jednym kawałku, wynik: %s", anna.text())
            .contains(annaWord)
            .contains(marekWord);
    }

    @Test
    @DisplayName("ZNANE OGRANICZENIE RGA: pisanie wstecz przeplata teksty autorów")
    void backwardTypingInterleaves_knownLimitation() {
        // Scenariusz: obie osoby wielokrotnie wstawiają znak na TĘ SAMĄ pozycję, cofając
        // kursor — czyli każdy nowy znak ląduje PRZED poprzednim. Wszystkie te znaki są
        // rodzeństwem o tym samym originie, więc RGA porządkuje je wyłącznie po znaczniku
        // czasu, mieszając autorów.
        RgaDocument anna = new RgaDocument(1);
        RgaDocument marek = new RgaDocument(2);

        java.util.List<Op> fromAnna = new java.util.ArrayList<>();
        java.util.List<Op> fromMarek = new java.util.ArrayList<>();
        for (char c : "cba".toCharArray()) {
            fromAnna.add(anna.insert(0, String.valueOf(c)));
        }
        for (char c : "zyx".toCharArray()) {
            fromMarek.add(marek.insert(0, String.valueOf(c)));
        }

        // Lokalnie każdy widzi swój tekst poprawnie.
        assertThat(anna.text()).isEqualTo("abc");
        assertThat(marek.text()).isEqualTo("xyz");

        anna.applyAll(fromMarek);
        marek.applyAll(fromAnna);

        // Zbieżność zachowana — repliki zgadzają się co do wyniku.
        assertThat(anna.text()).isEqualTo(marek.text());

        String merged = anna.text();

        // ...ale wynik jest przeplotem, a nie dwoma zwartymi słowami.
        // Ten warunek asercji jest tu CELOWO odwrócony: dokumentuje wadę, zamiast ją ukrywać.
        // Gdyby kiedykolwiek przestał być prawdziwy (np. po przejściu na Fugue), test padnie
        // i będzie to sygnał do aktualizacji ADR-0001, a nie regresja.
        assertThat(merged)
            .as("ADR-0001: RGA nie jest maksymalnie non-interleaving. "
                + "Rozwiązaniem jest Fugue, świadomie odrzucony ze względu na budżet. "
                + "Faktyczny wynik scalenia: %s", merged)
            .doesNotContain("abc")
            .doesNotContain("xyz");

        // Wszystkie znaki obu autorów są obecne — to nie jest utrata danych, tylko
        // zła kolejność. Rozróżnienie jest istotne: dane są bezpieczne, cierpi czytelność.
        assertThat(merged).hasSize(6);
        for (char c : "abcxyz".toCharArray()) {
            assertThat(merged).contains(String.valueOf(c));
        }
    }

    @Test
    @DisplayName("jednoczesna edycja tego samego znaku nie gubi żadnej ze zmian")
    void concurrentEditOfSameCharacterKeepsBoth() {
        RgaDocument[] replicas = twoReplicasSharing("kot");
        RgaDocument anna = replicas[0];
        RgaDocument marek = replicas[1];

        // Anna zamienia "kot" na "kort", Marek jednocześnie na "koty".
        Op annaOp = anna.insert(2, "r");
        Op marekOp = marek.insert(3, "y");

        anna.apply(marekOp);
        marek.apply(annaOp);

        assertThat(anna.text()).isEqualTo(marek.text());
        assertThat(anna.text())
            .as("obie zmiany muszą przetrwać — to jest cała teza projektu")
            .contains("r")
            .contains("y")
            .hasSize(5);
    }

    @Test
    @DisplayName("usunięcie i wstawienie w tym samym miejscu są przemienne")
    void deleteCommutesWithConcurrentInsert() {
        RgaDocument[] replicas = twoReplicasSharing("abcdef");
        RgaDocument anna = replicas[0];
        RgaDocument marek = replicas[1];

        Op deletion = anna.delete(2, 2);        // usuwa "cd"
        Op insertion = marek.insert(3, "XY");   // wstawia między "c" i "d"

        anna.apply(insertion);
        marek.apply(deletion);

        assertThat(anna.text()).isEqualTo(marek.text());
        // Wstawiony tekst przeżywa usunięcie sąsiadów — nie znika razem z nimi.
        assertThat(anna.text()).isEqualTo("abXYef");
    }
}
