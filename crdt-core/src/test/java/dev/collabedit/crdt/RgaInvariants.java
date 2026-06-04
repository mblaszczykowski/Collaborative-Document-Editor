package dev.collabedit.crdt;

import java.util.ArrayList;
import java.util.List;

/**
 * Sprawdzanie niezmienników strukturalnych RGA — niezależnie od tego, jaki tekst wychodzi.
 *
 * <p>Testy zbieżności porównują repliki między sobą, więc nie wykryją błędu, w którym
 * wszystkie repliki mylą się <b>tak samo</b>. Ten checker patrzy na strukturę pojedynczej
 * repliki i pyta, czy jest w ogóle poprawną linearyzacją RGA.
 */
final class RgaInvariants {

    private RgaInvariants() {
    }

    /**
     * Niezmiennik linearyzacji RGA: dla każdego bloku B o originie O
     * <ol>
     *   <li>O występuje w sekwencji <b>przed</b> B,</li>
     *   <li>każdy element leżący <b>ściśle między</b> O a B ma wyższy znacznik czasu niż B.</li>
     * </ol>
     *
     * <p>Drugi warunek jest sercem RGA: to on sprawia, że pozycja elementu jest funkcją
     * zbioru elementów, a nie kolejności ich wstawiania. Jego naruszenie oznacza, że dwie
     * repliki mogą umieścić ten sam element w różnych miejscach.
     *
     * @return lista naruszeń w formie czytelnej dla człowieka; pusta gdy struktura jest poprawna
     */
    static List<String> violations(RgaDocument document) {
        BlockList blocks = document.blocks();
        List<String> problems = new ArrayList<>();

        for (int i = 0; i < blocks.size(); i++) {
            Block block = blocks.get(i);
            Id origin = block.originLeft();
            if (origin == null) {
                continue;
            }

            BlockList.Locator originLoc = blocks.locateId(origin);
            if (originLoc == null) {
                problems.add("blok " + block + " ma origin " + origin + ", którego nie ma w strukturze");
                continue;
            }
            if (originLoc.blockIndex() > i) {
                problems.add("blok " + block + " stoi PRZED swoim originem " + origin);
                continue;
            }
            // Origin musi być ostatnim znakiem swojego bloku albo blok originu musi być
            // bezpośrednio przed nami — inaczej „między" liczyłoby się źle.
            for (int between = originLoc.blockIndex() + 1; between < i; between++) {
                Block middle = blocks.get(between);
                if (middle.start().compareTo(block.start()) <= 0) {
                    problems.add("między originem " + origin + " a blokiem " + block
                        + " leży " + middle + " o NIŻSZYM znaczniku — naruszenie reguły RGA");
                }
            }
        }
        return problems;
    }

    /**
     * Czy każdy blok run-length jest maksymalny, tzn. nie da się go scalić z następnym.
     * Naruszenie nie jest błędem poprawności — jest błędem wydajności: struktura rozdrabnia
     * się na bloki jednoznakowe i kompresja run-length przestaje cokolwiek dawać.
     */
    static List<String> fragmentation(RgaDocument document) {
        BlockList blocks = document.blocks();
        List<String> problems = new ArrayList<>();
        for (int i = 0; i + 1 < blocks.size(); i++) {
            if (blocks.get(i).canAppend(blocks.get(i + 1))) {
                problems.add("bloki " + blocks.get(i) + " i " + blocks.get(i + 1)
                    + " powinny być scalone");
            }
        }
        return problems;
    }
}
