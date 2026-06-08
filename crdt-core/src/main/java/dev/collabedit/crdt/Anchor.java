package dev.collabedit.crdt;

/**
 * Miejsce w dokumencie zaczepione o <b>znak</b>, a nie o pozycję.
 *
 * <h2>Dlaczego nie offset</h2>
 * Ten sam powód, dla którego kursory są identyfikatorami znaków (ADR-0006): offset przestaje
 * wskazywać to samo miejsce, gdy ktoś wstawi tekst wcześniej. Kotwica przesuwa się razem
 * z tekstem, bo jest przyczepiona do znaku.
 *
 * <h2>Dlaczego strona jest istotna, a nie kosmetyczna</h2>
 * To jest sedno rich textu w CRDT i najłatwiejsza rzecz do przeoczenia. Kotwica „za znakiem X"
 * i kotwica „przed znakiem Y" wskazują to samo miejsce w tekście {@code …XY…} — <b>dopóki
 * nikt nie wstawi czegoś między X a Y</b>. Wtedy się rozchodzą, i właśnie ta różnica
 * decyduje o zachowaniu formatowania:
 *
 * <pre>
 *   tekst:            a [b] c          (pogrubione „b")
 *
 *   koniec = AFTER(b):   zakres kończy się tuż za „b"
 *                        wpisanie „x" po „b" → a [b] x c     — pogrubienie NIE rośnie
 *
 *   koniec = BEFORE(c):  zakres kończy się tuż przed „c"
 *                        wpisanie „x" po „b" → a [b x] c     — pogrubienie ROŚNIE
 * </pre>
 *
 * <p>Pisanie na końcu pogrubionego fragmentu ma zostać pogrubione, a pisanie na końcu
 * linku — już do niego nie należeć. Obie te rzeczy wyraża ten sam mechanizm, różnicą jest
 * wyłącznie wybór strony kotwicy końcowej. Bez {@code Side} trzeba by to rozstrzygać
 * osobnym polem „czy rozszerzać", które opisuje to samo, tylko gorzej — bo w oderwaniu
 * od tego, gdzie kotwica faktycznie jest.
 *
 * @param charId znak, do którego kotwica jest przyczepiona
 * @param side   po której jego stronie
 */
public record Anchor(Id charId, Side side) {

    public enum Side {
        /** Tuż przed znakiem. Tekst wstawiony bezpośrednio przed nim znajdzie się PO kotwicy. */
        BEFORE,
        /** Tuż za znakiem. Tekst wstawiony bezpośrednio za nim znajdzie się PO kotwicy. */
        AFTER
    }

    public Anchor {
        if (charId == null) {
            throw new IllegalArgumentException("kotwica musi wskazywać znak");
        }
        if (side == null) {
            throw new IllegalArgumentException("kotwica musi mieć stronę");
        }
    }

    public static Anchor before(Id charId) {
        return new Anchor(charId, Side.BEFORE);
    }

    public static Anchor after(Id charId) {
        return new Anchor(charId, Side.AFTER);
    }

    @Override
    public String toString() {
        return (side == Side.BEFORE ? "◂" : "▸") + charId;
    }
}
