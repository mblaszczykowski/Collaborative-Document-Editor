package dev.collabedit.crdt;

/**
 * Uporządkowana lista bloków dokumentu, z dwoma rodzajami wyszukiwania:
 * po <b>widocznym offsecie</b> (czego potrzebuje edytor) i po <b>identyfikatorze</b>
 * (czego potrzebuje algorytm scalania).
 *
 * <p>Istnienie tego interfejsu jest celowe: {@link RgaDocument} zawiera algorytm i nie wie,
 * jak lista jest przechowywana. Dzięki temu ta sama, raz przetestowana logika działa na dwóch
 * implementacjach o różnej złożoności:
 *
 * <table>
 *   <caption>Złożoność operacji</caption>
 *   <tr><th></th><th>{@link ArrayBlockList}</th><th>{@link TreapBlockList}</th></tr>
 *   <tr><td>{@code insert}</td><td>O(n)</td><td>O(log n)</td></tr>
 *   <tr><td>{@code locateVisible}</td><td>O(n)</td><td>O(log n)</td></tr>
 *   <tr><td>{@code locateId}</td><td>O(n)</td><td>O(log n)</td></tr>
 * </table>
 *
 * <p>Property test {@code naiveEqualsIndexed} podaje obu implementacjom tę samą losową
 * sekwencję operacji i porównuje wyniki — to jest dowód, że optymalizacja nie zmieniła
 * zachowania, a nie założenie, że nie zmieniła.
 */
public interface BlockList extends Iterable<Block> {

    /** Pozycja znaku: indeks bloku i przesunięcie wewnątrz niego. */
    record Locator(int blockIndex, int offset) { }

    int size();

    Block get(int index);

    void insert(int index, Block block);

    void set(int index, Block block);

    void remove(int index);

    /**
     * Oznacza blok jako usunięty. Osobna metoda zamiast {@code get(i).markDeleted()}, bo
     * implementacja indeksowana musi przy tym zaktualizować agregaty w drzewie — mutacja
     * bloku „z boku" cicho rozspójniłaby strukturę.
     */
    void markDeleted(int index);

    /** Liczba znaków widocznych w tekście (bez tombstone'ów). */
    int visibleLength();

    /** Liczba wszystkich znaków, łącznie z tombstone'ami — miara narzutu (metryka z ADR-0005). */
    int totalLength();

    /**
     * Blok zawierający znak o podanym widocznym indeksie.
     *
     * @return pozycja, albo {@code null} gdy indeks jest poza zakresem
     */
    Locator locateVisible(int visibleIndex);

    /**
     * Blok zawierający znak o podanym identyfikatorze — również gdy znak jest tombstone'em.
     *
     * @return pozycja, albo {@code null} gdy znaku nie ma w strukturze
     */
    Locator locateId(Id id);

    /** Liczba widocznych znaków przed blokiem o podanym indeksie. */
    int visibleOffsetOf(int blockIndex);

    /** Pusta lista tego samego typu — używane przy klonowaniu dokumentu w testach. */
    BlockList newEmpty();
}
