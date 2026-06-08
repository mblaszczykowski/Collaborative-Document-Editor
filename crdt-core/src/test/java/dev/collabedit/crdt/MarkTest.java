package dev.collabedit.crdt;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Formatowanie tekstu (CP13) — zachowania, które muszą być prawdziwe, żeby rich text
 * w CRDT w ogóle miał sens.
 *
 * <table>
 *   <caption>Test → failure mode</caption>
 *   <tr><th>Test</th><th>Co wyłapuje</th></tr>
 *   <tr><td>{@code textInsertedIntoMarkedRangeInheritsMark}</td>
 *       <td><b>Klasyczny błąd „formatowanie jako atrybut znaku".</b> Tekst wpisany w środek
 *           pogrubionego zakresu przez osobę, która pogrubienia jeszcze nie widziała,
 *           zostaje niepogrubiony — dziura, której nikt nie zrobił. To jest cały powód,
 *           dla którego znacznik jest zakresem między kotwicami, a nie polem znaku.</td></tr>
 *   <tr><td>{@code expandingMarkGrowsAtEnd} / {@code nonExpandingMarkDoesNot}</td>
 *       <td>Zignorowana strona kotwicy. Wtedy albo pogrubienie nie rośnie przy pisaniu
 *           (irytujące), albo link rośnie (błędny adres w treści).</td></tr>
 *   <tr><td>{@code markDoesNotGrowBackwards}</td>
 *       <td>Kotwica początkowa po niewłaściwej stronie — tekst wpisany PRZED pogrubieniem
 *           staje się pogrubiony.</td></tr>
 *   <tr><td>{@code concurrentAddAndRemoveConverge}</td>
 *       <td>Brak porządku totalnego na znacznikach: dwie repliki widzą różne formatowanie
 *           tego samego tekstu i nigdy się nie uzgodnią.</td></tr>
 *   <tr><td>{@code markSurvivesDeletionOfNeighbourhood}</td>
 *       <td>Kotwica na tombstonie traktowana jak nieistniejąca — formatowanie znika
 *           przy skasowaniu sąsiedniego tekstu.</td></tr>
 *   <tr><td>{@code markWaitsForItsAnchors}</td>
 *       <td>Znacznik zastosowany przed dotarciem kotwic ląduje w przypadkowym miejscu.</td></tr>
 * </table>
 */
class MarkTest {

    private static RgaDocument document(int replicaId) {
        return new RgaDocument(replicaId, new TreapBlockList());
    }

    /** Formatowanie znaku na danej pozycji — wygodniejsze w asercjach niż lista odcinków. */
    private static Map<String, String> marksAt(RgaDocument document, int index) {
        for (MarkSet.Span span : document.formatting()) {
            if (index >= span.from() && index < span.to()) {
                return span.marks();
            }
        }
        return Map.of();
    }

    @Test
    @DisplayName("tekst wpisany w środek pogrubienia dziedziczy je — również gdy autor go nie widział")
    void textInsertedIntoMarkedRangeInheritsMark() {
        RgaDocument ania = document(1);
        RgaDocument bartek = document(2);

        Op.Insert base = ania.insert(0, "abcdef");
        bartek.apply(base);

        // Ania pogrubia „bcde" (pozycje 1..5).
        Op.AddMark bold = ania.addMark(1, 5, MarkTypes.BOLD, null);

        // Bartek — NIE widząc jeszcze pogrubienia — wpisuje „XY" w środek tego zakresu.
        Op.Insert inserted = bartek.insert(3, "XY");

        ania.apply(inserted);
        bartek.apply(bold);

        assertThat(ania.text()).isEqualTo("abcXYdef");
        assertThat(bartek.text()).isEqualTo(ania.text());

        // Wstawiony tekst jest w środku zakresu, więc MUSI być pogrubiony — mimo że
        // w chwili jego powstania pogrubienia jeszcze nie było.
        assertThat(marksAt(ania, 3))
            .as("tekst wpisany w środek pogrubienia nie odziedziczył go — dziura w formatowaniu")
            .containsEntry(MarkTypes.BOLD, null);
        assertThat(marksAt(bartek, 3)).isEqualTo(marksAt(ania, 3));
        assertThat(bartek.formatting()).isEqualTo(ania.formatting());
    }

    @Test
    @DisplayName("pogrubienie rośnie, gdy dopisać tuż za nim")
    void expandingMarkGrowsAtEnd() {
        RgaDocument document = document(1);
        document.insert(0, "abcdef");
        document.addMark(1, 3, MarkTypes.BOLD, null);   // „bc"

        assertThat(marksAt(document, 2)).containsKey(MarkTypes.BOLD);
        assertThat(marksAt(document, 3)).doesNotContainKey(MarkTypes.BOLD);

        document.insert(3, "X");   // tuż za pogrubionym „bc"

        assertThat(document.text()).isEqualTo("abcXdef");
        assertThat(marksAt(document, 3))
            .as("pogrubienie nie objęło tekstu dopisanego na jego końcu")
            .containsKey(MarkTypes.BOLD);
    }

    @Test
    @DisplayName("link NIE rośnie, gdy dopisać tuż za nim")
    void nonExpandingMarkDoesNot() {
        RgaDocument document = document(1);
        document.insert(0, "abcdef");
        document.addMark(1, 3, MarkTypes.LINK, "https://example.test");

        document.insert(3, "X");

        assertThat(marksAt(document, 2)).containsEntry(MarkTypes.LINK, "https://example.test");
        assertThat(marksAt(document, 3))
            .as("link objął tekst dopisany za nim — adres linku obejmuje treść, która do niego nie należy")
            .doesNotContainKey(MarkTypes.LINK);
    }

    @Test
    @DisplayName("formatowanie nie rośnie wstecz")
    void markDoesNotGrowBackwards() {
        RgaDocument document = document(1);
        document.insert(0, "abcdef");
        document.addMark(2, 4, MarkTypes.BOLD, null);   // „cd"

        document.insert(2, "X");   // tuż PRZED pogrubieniem

        assertThat(document.text()).isEqualTo("abXcdef");
        assertThat(marksAt(document, 2))
            .as("tekst wpisany przed pogrubieniem stał się pogrubiony")
            .doesNotContainKey(MarkTypes.BOLD);
        assertThat(marksAt(document, 3)).containsKey(MarkTypes.BOLD);
    }

    @Test
    @DisplayName("współbieżne nadanie i zdjęcie formatowania zbiegają się do tego samego wyniku")
    void concurrentAddAndRemoveConverge() {
        RgaDocument ania = document(1);
        RgaDocument bartek = document(2);

        Op.Insert base = ania.insert(0, "abcdef");
        bartek.apply(base);

        // Oboje działają na tym samym zakresie, nie widząc się nawzajem.
        Op.AddMark bold = ania.addMark(1, 4, MarkTypes.BOLD, null);
        Op.RemoveMark unbold = bartek.removeMark(1, 4, MarkTypes.BOLD);

        ania.apply(unbold);
        bartek.apply(bold);

        assertThat(bartek.formatting())
            .as("repliki widzą różne formatowanie tego samego tekstu — brak porządku totalnego")
            .isEqualTo(ania.formatting());

        // Kolejność zastosowania nie może mieć znaczenia — to jest ta sama własność,
        // której property testy dowodzą dla samego tekstu.
        RgaDocument trzeci = document(3);
        trzeci.apply(base);
        trzeci.apply(unbold);
        trzeci.apply(bold);
        assertThat(trzeci.formatting()).isEqualTo(ania.formatting());
    }

    @Test
    @DisplayName("formatowanie przeżywa skasowanie sąsiedniego tekstu")
    void markSurvivesDeletionOfNeighbourhood() {
        RgaDocument document = document(1);
        document.insert(0, "abcdef");
        document.addMark(2, 4, MarkTypes.BOLD, null);   // „cd"

        document.delete(0, 2);   // kasujemy „ab" przed zakresem

        assertThat(document.text()).isEqualTo("cdef");
        assertThat(marksAt(document, 0))
            .as("formatowanie zniknęło po skasowaniu tekstu OBOK niego")
            .containsKey(MarkTypes.BOLD);
        assertThat(marksAt(document, 2)).doesNotContainKey(MarkTypes.BOLD);
    }

    @Test
    @DisplayName("znacznik czeka w buforze na swoje kotwice")
    void markWaitsForItsAnchors() {
        RgaDocument ania = document(1);
        RgaDocument bartek = document(2);

        Op.Insert base = ania.insert(0, "abcdef");
        Op.AddMark bold = ania.addMark(1, 4, MarkTypes.BOLD, null);

        // Bartek dostaje znacznik ZANIM dostanie tekst, którego dotyczy.
        assertThat(bartek.apply(bold)).isEqualTo(RgaDocument.ApplyResult.BUFFERED);
        assertThat(bartek.formatting())
            .as("znacznik zastosowany bez kotwic — formatowanie w przypadkowym miejscu")
            .isEmpty();

        bartek.apply(base);

        assertThat(bartek.formatting())
            .as("znacznik nie został ponowiony po dotarciu kotwic")
            .isEqualTo(ania.formatting());
        assertThat(bartek.pendingCount()).isZero();
    }

    @Test
    @DisplayName("sąsiadujące odcinki o tym samym formatowaniu są sklejane")
    void adjacentIdenticalSpansAreMerged() {
        RgaDocument document = document(1);
        document.insert(0, "abcdef");
        document.addMark(0, 3, MarkTypes.BOLD, null);
        document.addMark(3, 6, MarkTypes.BOLD, null);

        List<MarkSet.Span> spans = document.formatting();

        assertThat(spans)
            .as("dwa stykające się pogrubienia dały dwa odcinki zamiast jednego")
            .hasSize(1);
        assertThat(spans.getFirst().from()).isZero();
        assertThat(spans.getFirst().to()).isEqualTo(6);
    }

    @Test
    @DisplayName("powtórzony znacznik jest rozpoznawany jako duplikat")
    void duplicateMarkIsRecognised() {
        RgaDocument ania = document(1);
        RgaDocument bartek = document(2);

        bartek.apply(ania.insert(0, "abcdef"));
        Op.AddMark bold = ania.addMark(1, 4, MarkTypes.BOLD, null);

        assertThat(bartek.apply(bold)).isEqualTo(RgaDocument.ApplyResult.APPLIED);
        assertThat(bartek.apply(bold))
            .as("retransmisja znacznika po ponownym połączeniu nie jest rozpoznawana jako duplikat")
            .isEqualTo(RgaDocument.ApplyResult.DUPLICATE);
        assertThat(bartek.markCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("format przewodowy znacznika przechodzi w obie strony")
    void markSurvivesWireRoundTrip() {
        RgaDocument document = document(1);
        document.insert(0, "abcdef");

        Op.AddMark added = document.addMark(1, 4, MarkTypes.LINK, "https://example.test/a?b=c");
        Op.RemoveMark removed = document.removeMark(2, 5, MarkTypes.BOLD);

        assertThat(OpCodec.decode(OpCodec.encode(added))).isEqualTo(added);
        assertThat(OpCodec.decode(OpCodec.encode(removed))).isEqualTo(removed);
    }
}
