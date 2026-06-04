package dev.collabedit.crdt;

/**
 * Ciągły przebieg znaków od jednej repliki — jednostka przechowywania w dokumencie.
 *
 * <p>Dokument nie jest listą znaków, tylko listą bloków. Wpisanie 20 znaków pod rząd to
 * jeden {@code Block}, a nie 20 obiektów. Uzasadnienie i niezmienniki w
 * {@code docs/crdt-design.md}, sekcja 3.
 *
 * <p><b>Niezmiennik:</b> blok jest w całości żywy albo w całości usunięty. Usunięcie fragmentu
 * najpierw dzieli blok. Alternatywa (tablica flag per znak) komplikowałaby każdą operację
 * indeksowania, oszczędzając niewiele.
 *
 * <p>Znak o indeksie {@code i} ma identyfikator {@code start.plus(i)}, a jego lewym originem
 * jest znak {@code i-1} (dla {@code i == 0} jest to {@link #originLeft}).
 *
 * <p>Klasa jest niemutowalna poza flagą {@code deleted} — celowo, bo oznaczenie tombstone'em
 * jest jedyną zmianą, jaka może spotkać istniejący blok bez jego podziału.
 */
public final class Block {

    private final Id start;
    private final String text;
    private final Id originLeft;
    private boolean deleted;

    public Block(Id start, String text, Id originLeft, boolean deleted) {
        if (text.isEmpty()) {
            throw new IllegalArgumentException("blok nie może być pusty");
        }
        this.start = start;
        this.text = text;
        this.originLeft = originLeft;
        this.deleted = deleted;
    }

    public Id start() {
        return start;
    }

    public String text() {
        return text;
    }

    /** Lewy origin <b>pierwszego</b> znaku bloku; {@code null} oznacza początek dokumentu. */
    public Id originLeft() {
        return originLeft;
    }

    public boolean deleted() {
        return deleted;
    }

    public void markDeleted() {
        this.deleted = true;
    }

    public int length() {
        return text.length();
    }

    /** Liczba znaków widocznych w tekście — 0 dla tombstone'u. */
    public int visibleLength() {
        return deleted ? 0 : text.length();
    }

    public Id idAt(int offset) {
        return start.plus(offset);
    }

    /** Identyfikator ostatniego znaku bloku. */
    public Id lastId() {
        return start.plus(text.length() - 1);
    }

    public boolean containsId(Id id) {
        return id.replicaId() == start.replicaId()
            && id.lamport() >= start.lamport()
            && id.lamport() < start.lamport() + text.length();
    }

    /** Przesunięcie znaku w bloku; wynik ma sens tylko gdy {@link #containsId} zwraca prawdę. */
    public int offsetOf(Id id) {
        return (int) (id.lamport() - start.lamport());
    }

    /**
     * Dzieli blok na dwa: {@code [0, offset)} oraz {@code [offset, length)}.
     *
     * <p>Origin prawej części to ostatni znak lewej — dzięki temu podział nie zmienia
     * struktury logicznej dokumentu, tylko jej reprezentację. To jest kluczowy niezmiennik:
     * podział musi być operacją czysto techniczną, niewidoczną dla algorytmu scalania.
     */
    public Block[] splitAt(int offset) {
        if (offset <= 0 || offset >= text.length()) {
            throw new IllegalArgumentException(
                "podział poza wnętrzem bloku: offset=" + offset + ", długość=" + text.length());
        }
        Block left = new Block(start, text.substring(0, offset), originLeft, deleted);
        Block right = new Block(start.plus(offset), text.substring(offset), start.plus(offset - 1), deleted);
        return new Block[] {left, right};
    }

    /**
     * Czy {@code other} da się dopisać na końcu tego bloku bez zmiany znaczenia — czyli czy
     * pochodzi z tej samej repliki, ma kolejny lamport, ten sam stan tombstone'u i jest
     * zaczepiony dokładnie o ostatni znak tego bloku.
     *
     * <p>To jest szybka ścieżka dla zwykłego pisania: bez niej każdy naciśnięty klawisz
     * tworzyłby osobny blok, a run-length nie dawałby nic.
     */
    public boolean canAppend(Block other) {
        return !deleted
            && !other.deleted
            && other.start.isDirectSuccessorOf(lastId())
            && lastId().equals(other.originLeft);
    }

    public Block append(String more) {
        return new Block(start, text + more, originLeft, deleted);
    }

    @Override
    public String toString() {
        return (deleted ? "~" : "") + start + ":\"" + text + "\"←" + originLeft;
    }
}
