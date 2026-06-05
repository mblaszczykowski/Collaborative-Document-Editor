package dev.collabedit.crdt;

import java.util.List;
import java.util.Map;

/**
 * Migawka pełnego stanu repliki — to, co trafia do kolumny {@code snapshots.state} (ADR-0003).
 *
 * <p>Snapshot jest <b>cache'em, nie źródłem prawdy</b>. Wolno go skasować w dowolnym momencie;
 * system odtworzy stan z logu operacji, wolniej, ale poprawnie. Z tego wynika najważniejsza
 * decyzja dotycząca formatu: przy niezgodności {@link #formatVersion} snapshot jest
 * <b>ignorowany</b>, a nie migrowany. Migracja cache'a to koszt bez korzyści.
 *
 * <p>Zawiera trzy rzeczy, bo trzy są potrzebne do wiernego odtworzenia repliki:
 * <ul>
 *   <li>{@code blocks} — struktura dokumentu wraz z tombstone'ami (te są istotne: mogą być
 *       originem operacji, która dopiero nadejdzie),</li>
 *   <li>{@code clock} — zegar Lamporta, bez którego kolejne operacje lokalne miałyby
 *       znaczniki kolidujące z historią,</li>
 *   <li>{@code maxSeen} — version vector, potrzebny do stabilności przyczynowej (ADR-0005)
 *       i do odpowiedzi „co już mam" przy ponownym połączeniu.</li>
 * </ul>
 */
public record DocumentSnapshot(
    int formatVersion,
    long clock,
    Map<Integer, Long> maxSeen,
    List<Block> blocks
) {

    /**
     * Bieżąca wersja formatu. Podnosić przy KAŻDEJ zmianie struktury {@link Block} albo
     * kodowania — snapshoty zapisane starym formatem zostaną wtedy pominięte, a stan
     * odtworzony z logu.
     */
    public static final int CURRENT_FORMAT_VERSION = 1;

    public DocumentSnapshot {
        maxSeen = Map.copyOf(maxSeen);
        blocks = List.copyOf(blocks);
    }

    public boolean isReadable() {
        return formatVersion == CURRENT_FORMAT_VERSION;
    }

    public int textLength() {
        return blocks.stream().mapToInt(Block::visibleLength).sum();
    }

    public int tombstoneCount() {
        return blocks.stream().mapToInt(b -> b.length() - b.visibleLength()).sum();
    }
}
