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
 * <h2>Blok bez treści (CP18)</h2>
 * A deleted block may <b>drop its text while staying in the structure</b>. It keeps its
 * identity ({@link #start}), its {@link #length}, its {@link #originLeft} and its position;
 * it loses only the characters, which nobody can see anyway.
 *
 * <p>This exists because {@link RgaDocument#gc} could barely reclaim anything. ADR-0005 lists
 * three reasons a tombstone must be kept, and CP15 measured the cost: a 1000-character document
 * written in one run, fully deleted, with 100 formatting marks, reclaimed <b>0%</b>. All three
 * reasons — being another block's origin, being a mark anchor, causal stability — need the
 * <b>identity</b> of a character, never its content. So the answer was never to keep or remove
 * the whole block; it was to separate the two things being kept.
 *
 * <p>The invariant therefore changes wording, and this is the only semantic change in CP18:
 * <b>a block always has positive length, but a deleted block may have no text.</b> Everything
 * that used to read {@code text.length()} now reads {@link #length}, which is why the field
 * exists at all rather than being derived.
 */
public final class Block {

    private final Id start;

    /**
     * The characters, or {@code null} once dropped.
     *
     * <p>Not final, for the same reason {@link #deleted} is not: dropping content is a change
     * that may happen to an existing block, and copying the structure to express it would cost
     * more than the memory being reclaimed.
     */
    private String text;

    /**
     * Number of characters this block covers — <b>with or without content</b>.
     *
     * <p>Stored rather than derived from {@code text.length()}, because after the content is
     * dropped there is nothing to derive it from, and every index in the document depends on
     * it: the treap aggregates, {@link #containsId}, {@link #lastId}, position lookup. A block
     * that forgets its length forgets where every character after it lives.
     */
    private final int length;

    private final Id originLeft;
    private boolean deleted;

    public Block(Id start, String text, Id originLeft, boolean deleted) {
        if (text.isEmpty()) {
            throw new IllegalArgumentException("blok nie może być pusty");
        }
        this.start = start;
        this.text = text;
        this.length = text.length();
        this.originLeft = originLeft;
        this.deleted = deleted;
    }

    /**
     * A block that has already had its content dropped — used when restoring from a snapshot.
     *
     * <p>Only ever deleted: dropping the content of a visible block would delete text nobody
     * asked to delete, so the constructor refuses rather than trusting the caller.
     */
    static Block withoutContent(Id start, int length, Id originLeft) {
        if (length <= 0) {
            throw new IllegalArgumentException("blok nie może być pusty");
        }
        return new Block(start, length, originLeft);
    }

    private Block(Id start, int length, Id originLeft) {
        this.start = start;
        this.text = null;
        this.length = length;
        this.originLeft = originLeft;
        this.deleted = true;
    }

    public Id start() {
        return start;
    }

    /**
     * @throws IllegalStateException when the content has been dropped. Deliberately loud: the
     *         only correct readers of this are paths that already know the block is visible,
     *         and a silent empty string here would delete text from the document instead.
     */
    public String text() {
        if (text == null) {
            throw new IllegalStateException(
                "content of this block was dropped by GC; check hasContent() first: " + this);
        }
        return text;
    }

    /** Whether the characters are still held, as opposed to only the identity. */
    public boolean hasContent() {
        return text != null;
    }

    /**
     * Releases the characters, keeping everything the structure depends on.
     *
     * @throws IllegalStateException when the block is still visible
     */
    void dropContent() {
        if (!deleted) {
            throw new IllegalStateException("cannot drop content of a visible block: " + this);
        }
        text = null;
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
        return length;
    }

    /** Liczba znaków widocznych w tekście — 0 dla tombstone'u. */
    public int visibleLength() {
        return deleted ? 0 : length;
    }

    public Id idAt(int offset) {
        return start.plus(offset);
    }

    /** Identyfikator ostatniego znaku bloku. */
    public Id lastId() {
        return start.plus(length - 1);
    }

    public boolean containsId(Id id) {
        return id.replicaId() == start.replicaId()
            && id.lamport() >= start.lamport()
            && id.lamport() < start.lamport() + length;
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
     *
     * <p>Works on a content-dropped block too, splitting the length alone. That case is reached
     * when an operation arrives whose origin sits inside a tombstone GC has already emptied —
     * normal, since the identity is exactly what was kept.
     */
    public Block[] splitAt(int offset) {
        if (offset <= 0 || offset >= length) {
            throw new IllegalArgumentException(
                "podział poza wnętrzem bloku: offset=" + offset + ", długość=" + length);
        }
        if (text == null) {
            return new Block[] {
                new Block(start, offset, originLeft),
                new Block(start.plus(offset), length - offset, start.plus(offset - 1))
            };
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
     *
     * <p>A content-dropped block can never be appended to, and needs no extra check for it:
     * dropping requires {@code deleted}, and appending requires the opposite on both sides.
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
        String body = text == null ? "<" + length + " dropped>" : "\"" + text + "\"";
        return (deleted ? "~" : "") + start + ":" + body + "←" + originLeft;
    }
}
