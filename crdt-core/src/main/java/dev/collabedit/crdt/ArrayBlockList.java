package dev.collabedit.crdt;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Naiwna implementacja {@link BlockList} — zwykła {@link ArrayList} i skanowanie liniowe.
 *
 * <p>Powstała <b>pierwsza</b> i celowo. Jest oczywiście poprawna, więc służy jako wzorzec,
 * względem którego weryfikujemy implementację zoptymalizowaną ({@link TreapBlockList}).
 * Kolejność „najpierw naiwne, potem pomiar, dopiero potem optymalizacja" jest widoczna
 * w historii commitów, bo jest uczciwsza niż od razu wrzucona sprytna struktura.
 *
 * <p>Wszystkie operacje wyszukiwania są O(n), a wstawienie bloku dodatkowo przesuwa tablicę.
 * Przy dokumencie o 100 tys. znaków oznacza to zauważalne opóźnienie na każde naciśnięcie
 * klawisza — co pokazuje benchmark JMH w {@code RgaBenchmark}.
 */
public final class ArrayBlockList implements BlockList {

    private final List<Block> blocks = new ArrayList<>();

    @Override
    public int size() {
        return blocks.size();
    }

    @Override
    public Block get(int index) {
        return blocks.get(index);
    }

    @Override
    public void insert(int index, Block block) {
        blocks.add(index, block);
    }

    @Override
    public void set(int index, Block block) {
        blocks.set(index, block);
    }

    @Override
    public void remove(int index) {
        blocks.remove(index);
    }

    @Override
    public void markDeleted(int index) {
        blocks.get(index).markDeleted();
    }

    @Override
    public int visibleLength() {
        int total = 0;
        for (Block b : blocks) {
            total += b.visibleLength();
        }
        return total;
    }

    @Override
    public int totalLength() {
        int total = 0;
        for (Block b : blocks) {
            total += b.length();
        }
        return total;
    }

    @Override
    public Locator locateVisible(int visibleIndex) {
        if (visibleIndex < 0) {
            return null;
        }
        int seen = 0;
        for (int i = 0; i < blocks.size(); i++) {
            Block b = blocks.get(i);
            int visible = b.visibleLength();
            if (visible > 0 && visibleIndex < seen + visible) {
                return new Locator(i, visibleIndex - seen);
            }
            seen += visible;
        }
        return null;
    }

    @Override
    public Locator locateId(Id id) {
        for (int i = 0; i < blocks.size(); i++) {
            Block b = blocks.get(i);
            if (b.containsId(id)) {
                return new Locator(i, b.offsetOf(id));
            }
        }
        return null;
    }

    @Override
    public int visibleOffsetOf(int blockIndex) {
        int seen = 0;
        for (int i = 0; i < blockIndex; i++) {
            seen += blocks.get(i).visibleLength();
        }
        return seen;
    }

    @Override
    public BlockList newEmpty() {
        return new ArrayBlockList();
    }

    @Override
    public Iterator<Block> iterator() {
        return blocks.iterator();
    }
}
