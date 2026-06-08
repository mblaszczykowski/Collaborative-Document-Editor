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
 * <p>Zawiera cztery rzeczy, bo cztery są potrzebne do wiernego odtworzenia repliki:
 * <ul>
 *   <li>{@code blocks} — struktura dokumentu wraz z tombstone'ami (te są istotne: mogą być
 *       originem operacji, która dopiero nadejdzie),</li>
 *   <li>{@code clock} — zegar Lamporta, bez którego kolejne operacje lokalne miałyby
 *       znaczniki kolidujące z historią,</li>
 *   <li>{@code maxSeen} — version vector, potrzebny do stabilności przyczynowej (ADR-0005)
 *       i do odpowiedzi „co już mam" przy ponownym połączeniu,</li>
 *   <li>{@code marks} — warstwa formatowania (CP13). Bez niej restart instancji gubi cały
 *       wygląd dokumentu: tekst odtwarza się z bloków, a formatowanie znika bez śladu.
 *       Nie da się go wyliczyć z bloków, bo znaczniki są osobnym zbiorem operacji.</li>
 * </ul>
 */
public record DocumentSnapshot(
    int formatVersion,
    long clock,
    Map<Integer, Long> maxSeen,
    List<Block> blocks,
    List<Op> marks
) {

    /**
     * Bieżąca wersja formatu. Podnosić przy KAŻDEJ zmianie struktury {@link Block} albo
     * kodowania — snapshoty zapisane starym formatem zostaną wtedy pominięte, a stan
     * odtworzony z logu.
     */
    public static final int CURRENT_FORMAT_VERSION = 2;

    public DocumentSnapshot {
        maxSeen = Map.copyOf(maxSeen);
        blocks = List.copyOf(blocks);
        marks = List.copyOf(marks);
    }

    /**
     * Podniesienie wersji formatu w CP13 jest DARMOWE i to nie jest przypadek.
     *
     * <p>ADR-0003 rozstrzygnął, że snapshot o niezgodnej wersji jest ignorowany, a nie
     * migrowany — więc dołożenie warstwy znaczników nie wymaga ani kodu migracji, ani
     * okna serwisowego, ani rozstrzygania, co zrobić ze snapshotami w starym formacie.
     * Zostaną pominięte, stan odtworzy się z logu operacji, a następny snapshot powstanie
     * już w nowym formacie. Jedynym kosztem jest jednorazowo dłuższe pierwsze otwarcie
     * każdego dokumentu.
     *
     * <p>Ta decyzja została podjęta w CP5, a spłaca się w CP13.
     */
    public static final String FORMAT_VERSION_NOTE = "wersja 2: dodana warstwa znaczników (CP13)";

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
