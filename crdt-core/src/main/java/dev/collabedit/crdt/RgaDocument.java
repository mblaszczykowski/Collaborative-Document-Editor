package dev.collabedit.crdt;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Replika dokumentu tekstowego jako CRDT typu RGA.
 *
 * <p>Kluczowa własność, dla której cała ta konstrukcja istnieje: <b>zastosowanie tego samego
 * zbioru operacji w dowolnej kolejności i z dowolnymi powtórzeniami daje ten sam tekst.</b>
 * Nie wymaga to centralnego arbitra ani uporządkowanego transportu — i to jest powód, dla
 * którego architektura z ADR-0004 (każda instancja backendu jako równorzędna replika, Redis
 * jako zawodny broadcast) jest bezpieczna, a nie tylko „zwykle działa".
 *
 * <p>Ta klasa zawiera <b>wyłącznie algorytm</b>. Nie wie nic o sieci, bazie danych ani
 * o Springu — patrz ADR-0001 i konfiguracja modułu {@code crdt-core}.
 *
 * <h2>Trzy rzeczy, które łatwo zrobić źle</h2>
 * <ol>
 *   <li><b>Zegar Lamporta.</b> Przy odbiorze operacji zegar musi zostać podbity ponad jej
 *       znacznik ({@link #observeClock}). Bez tego reguła wstawiania RGA przestaje być
 *       poprawna — kontrprzykład w {@code docs/crdt-design.md} sekcja 1.</li>
 *   <li><b>Wykrywanie duplikatów.</b> Nie przez version vector (zegar Lamporta jest nieciągły),
 *       tylko przez obecność znaku w strukturze. Usunięcia są idempotentne, więc ich duplikatów
 *       nie sprawdzamy wcale.</li>
 *   <li><b>Operacje niegotowe.</b> Operacja, której origin jeszcze nie dotarł, trafia do bufora
 *       i jest ponawiana po każdym udanym zastosowaniu. Rdzeń musi znieść dowolną kolejność
 *       dostawy, bo dokładnie tego wymaga property test {@code convergence}.</li>
 * </ol>
 */
public final class RgaDocument {

    private final int replicaId;
    private final BlockList blocks;

    /** Zegar Lamporta — wartość, którą dostanie następna operacja lokalna. */
    private long clock;

    /** Najwyższy zastosowany lamport per replika. Do GC i resynchronizacji, nie do dedup. */
    private final Map<Integer, Long> maxSeen = new HashMap<>();

    /** Operacje, których nie da się jeszcze zastosować (brakuje originu lub celu usunięcia). */
    private final List<Op> pending = new ArrayList<>();

    public RgaDocument(int replicaId) {
        this(replicaId, new ArrayBlockList());
    }

    public RgaDocument(int replicaId, BlockList blocks) {
        this.replicaId = replicaId;
        this.blocks = blocks;
    }

    // ------------------------------------------------------------------
    // Operacje lokalne
    // ------------------------------------------------------------------

    /**
     * Wstawia tekst na podanej pozycji w <b>widocznym</b> tekście i zwraca operację
     * do rozesłania do pozostałych replik.
     */
    public Op.Insert insert(int visibleIndex, String text) {
        int length = visibleLength();
        if (visibleIndex < 0 || visibleIndex > length) {
            throw new IndexOutOfBoundsException(
                "pozycja " + visibleIndex + " poza tekstem o długości " + length);
        }
        // Origin to widoczny poprzednik punktu wstawienia. Gdy przed pozycją stoją
        // tombstone'y, nowy tekst ląduje przed nimi — zachowanie standardowe dla RGA.
        Id originLeft = visibleIndex == 0 ? null : idAtVisible(visibleIndex - 1);

        Op.Insert op = new Op.Insert(new Id(replicaId, clock), originLeft, text);
        clock += op.span();
        integrate(op);
        observeVersion(op);
        return op;
    }

    /**
     * Usuwa {@code count} znaków od podanej pozycji w widocznym tekście i zwraca operację
     * do rozesłania.
     */
    public Op.Delete delete(int visibleIndex, int count) {
        if (count <= 0) {
            throw new IllegalArgumentException("liczba znaków do usunięcia musi być dodatnia");
        }
        int length = visibleLength();
        if (visibleIndex < 0 || visibleIndex + count > length) {
            throw new IndexOutOfBoundsException(
                "zakres [" + visibleIndex + ", " + (visibleIndex + count)
                    + ") poza tekstem o długości " + length);
        }

        List<Op.Range> targets = new ArrayList<>();
        int remaining = count;
        while (remaining > 0) {
            // Po oznaczeniu bloku tombstone'em przestaje on być widoczny, więc visibleIndex
            // wskazuje już na kolejny znak do usunięcia — dlatego nie jest zwiększany.
            BlockList.Locator loc = blocks.locateVisible(visibleIndex);
            int index = loc.blockIndex();
            if (loc.offset() > 0) {
                splitBlock(index, loc.offset());
                index++;
            }
            Block target = blocks.get(index);
            if (target.length() > remaining) {
                splitBlock(index, remaining);
                target = blocks.get(index);
            }
            blocks.markDeleted(index);
            targets.add(new Op.Range(target.start(), target.length()));
            remaining -= target.length();
        }

        Op.Delete op = new Op.Delete(new Id(replicaId, clock), targets);
        clock += op.span();
        observeVersion(op);
        return op;
    }

    // ------------------------------------------------------------------
    // Operacje zdalne
    // ------------------------------------------------------------------

    /** Co się stało z zastosowaną operacją. */
    public enum ApplyResult {
        /** Operacja wniosła nową zmianę do dokumentu. */
        APPLIED,
        /** Operację już wcześniej widzieliśmy — nic się nie zmieniło. */
        DUPLICATE,
        /** Brakuje jej przyczyny (originu albo celu usunięcia); czeka w buforze. */
        BUFFERED;

        /** Czy operacja jest dla tej repliki nowością — czyli czy warto ją zapisać i rozesłać. */
        public boolean isNews() {
            return this != DUPLICATE;
        }
    }

    /**
     * Stosuje operację pochodzącą z innej repliki.
     *
     * <p>Bezpieczne przy dowolnej kolejności i dowolnym powtórzeniu. Operacja, której nie da
     * się jeszcze zastosować, trafia do bufora i zostanie ponowiona automatycznie.
     *
     * <p>Rozróżnienie {@link ApplyResult#APPLIED} od {@link ApplyResult#DUPLICATE} nie jest
     * potrzebne samemu algorytmowi — z jego punktu widzenia to jest to samo, bo scalanie jest
     * idempotentne. Jest potrzebne <b>warstwie wyżej</b>: duplikatu nie ma sensu zapisywać
     * do logu ani rozsyłać po klastrze. Bez tego rozróżnienia jedyną alternatywą byłoby
     * porównywanie tekstu przed i po, czyli kopiowanie całego dokumentu na każde naciśnięcie
     * klawisza.
     */
    public ApplyResult apply(Op op) {
        // Zegar podbijamy nawet dla operacji zbuforowanej: reguła Lamporta dotyczy odbioru,
        // nie zastosowania. Version vector aktualizujemy dopiero po faktycznym zastosowaniu,
        // bo oznacza on "mam to zintegrowane", a nie "widziałem w sieci".
        observeClock(op);
        if (isAlreadyKnown(op)) {
            return ApplyResult.DUPLICATE;
        }
        if (tryApply(op)) {
            observeVersion(op);
            drainPending();
            return ApplyResult.APPLIED;
        }
        pending.add(op);
        return ApplyResult.BUFFERED;
    }

    /**
     * Czy tę konkretną operację już widzieliśmy.
     *
     * <p>Dla wstawienia: czy znak o tym identyfikatorze jest w strukturze. Dla usunięcia:
     * czy wszystkie objęte nim znaki są już tombstone'ami. Ta druga odpowiedź bywa fałszywie
     * pozytywna — jeśli ktoś inny usunął dokładnie ten sam zakres, uznamy operację za znaną
     * mimo że jest nowa. Skutek jest wyłącznie taki, że nie zapiszemy jej do logu; stan
     * dokumentu pozostaje poprawny, bo usunięcie jest idempotentne. Świadomy kompromis:
     * dokładne rozróżnienie wymagałoby pamiętania identyfikatorów wszystkich usunięć.
     */
    private boolean isAlreadyKnown(Op op) {
        return switch (op) {
            case Op.Insert insert -> blocks.locateId(insert.id()) != null;
            case Op.Delete delete -> isEntirelyDeleted(delete);
        };
    }

    private boolean isEntirelyDeleted(Op.Delete op) {
        for (Op.Range range : op.targets()) {
            long cursor = range.start().lamport();
            long end = cursor + range.length();
            while (cursor < end) {
                BlockList.Locator loc = blocks.locateId(new Id(range.start().replicaId(), cursor));
                if (loc == null) {
                    return false;
                }
                Block block = blocks.get(loc.blockIndex());
                if (!block.deleted()) {
                    return false;
                }
                cursor += block.length() - loc.offset();
            }
        }
        return true;
    }

    public void applyAll(Iterable<Op> ops) {
        for (Op op : ops) {
            apply(op);
        }
    }

    private boolean tryApply(Op op) {
        return switch (op) {
            case Op.Insert insert -> tryApplyInsert(insert);
            case Op.Delete delete -> tryApplyDelete(delete);
        };
    }

    private boolean tryApplyInsert(Op.Insert op) {
        // Duplikat: znak o tym identyfikatorze już jest w strukturze. Dokładne i darmowe —
        // struktura i tak indeksuje po identyfikatorach (docs/crdt-design.md sekcja 2).
        if (blocks.locateId(op.id()) != null) {
            return true;
        }
        if (op.originLeft() != null && blocks.locateId(op.originLeft()) == null) {
            return false;
        }
        integrate(op);
        return true;
    }

    private boolean tryApplyDelete(Op.Delete op) {
        // Wszystko albo nic: częściowe zastosowanie usunięcia zostawiłoby operację w buforze
        // i przy ponowieniu oznaczyłoby część zakresów drugi raz. Byłoby to nieszkodliwe
        // (idempotencja), ale utrudniałoby rozumowanie o stanie.
        for (Op.Range range : op.targets()) {
            if (!isRangeFullyPresent(range)) {
                return false;
            }
        }
        for (Op.Range range : op.targets()) {
            markRangeDeleted(range);
        }
        return true;
    }

    private boolean isRangeFullyPresent(Op.Range range) {
        long cursor = range.start().lamport();
        long end = cursor + range.length();
        while (cursor < end) {
            BlockList.Locator loc = blocks.locateId(new Id(range.start().replicaId(), cursor));
            if (loc == null) {
                return false;
            }
            Block block = blocks.get(loc.blockIndex());
            // Przeskakujemy do końca bloku — zakres może obejmować kilka bloków, również
            // takich, które powstały z podziału albo z osobnych operacji o ciągłych lamportach.
            cursor += block.length() - loc.offset();
        }
        return true;
    }

    private void markRangeDeleted(Op.Range range) {
        long cursor = range.start().lamport();
        long end = cursor + range.length();
        while (cursor < end) {
            BlockList.Locator loc = blocks.locateId(new Id(range.start().replicaId(), cursor));
            int index = loc.blockIndex();
            if (loc.offset() > 0) {
                splitBlock(index, loc.offset());
                index++;
            }
            Block block = blocks.get(index);
            long coverable = Math.min(block.length(), end - cursor);
            if (coverable < block.length()) {
                splitBlock(index, (int) coverable);
                block = blocks.get(index);
            }
            blocks.markDeleted(index);
            cursor += block.length();
        }
    }

    // ------------------------------------------------------------------
    // Serce algorytmu
    // ------------------------------------------------------------------

    /**
     * Wstawia nowy blok w miejsce wyznaczone regułą RGA.
     *
     * <p>Reguła: znajdź origin, przejdź w prawo tak długo, jak napotykane bloki mają wyższy
     * znacznik czasu niż wstawiany, i wstaw w pierwszym miejscu, gdzie napotkany blok ma niższy.
     *
     * <p>Dlaczego to jest poprawne — dwa argumenty, oba opierające się na tym, że
     * {@code lamport} jest zegarem Lamporta:
     * <ul>
     *   <li>Element w poddrzewie rodzeństwa S powstał przyczynowo po S, więc ma wyższy znacznik
     *       niż S. Jeśli więc pomijamy S, pomijamy też całe jego poddrzewo — rodzeństwo nigdy
     *       nie zostaje rozerwane.</li>
     *   <li>Pierwszy element za poddrzewem originu to rodzeństwo originu (albo rodzeństwo jego
     *       przodka), a takie ma znacznik <i>niższy</i> niż origin — a wstawiany element ma
     *       znacznik <i>wyższy</i> niż origin, bo jego autor origin widział. Skan zatrzyma się
     *       tam samoczynnie i nie wyjdzie poza poddrzewo originu.</li>
     * </ul>
     */
    private void integrate(Op.Insert op) {
        int position;
        if (op.originLeft() == null) {
            position = 0;
        } else {
            BlockList.Locator loc = blocks.locateId(op.originLeft());
            int index = loc.blockIndex();
            Block originBlock = blocks.get(index);
            // Origin musi być ostatnim znakiem swojego bloku, żeby "zaraz za originem"
            // było granicą bloków. Podział jest operacją czysto techniczną — nie zmienia
            // struktury logicznej, bo prawa część zostaje dzieckiem ostatniego znaku lewej.
            if (loc.offset() < originBlock.length() - 1) {
                splitBlock(index, loc.offset() + 1);
            }
            position = index + 1;
        }

        Id newId = op.id();
        while (position < blocks.size() && blocks.get(position).start().compareTo(newId) > 0) {
            position++;
        }

        Block block = new Block(newId, op.text(), op.originLeft(), false);

        // Szybka ścieżka zwykłego pisania: dopisz do poprzedniego bloku zamiast tworzyć nowy.
        // Bez tego run-length nie dawałby nic, bo każdy klawisz to osobna operacja.
        if (position > 0) {
            Block previous = blocks.get(position - 1);
            if (previous.canAppend(block)) {
                blocks.set(position - 1, previous.append(block.text()));
                return;
            }
        }
        blocks.insert(position, block);
    }

    private void splitBlock(int index, int offset) {
        Block[] parts = blocks.get(index).splitAt(offset);
        blocks.set(index, parts[0]);
        blocks.insert(index + 1, parts[1]);
    }

    private void drainPending() {
        boolean progress = true;
        while (progress && !pending.isEmpty()) {
            progress = false;
            Iterator<Op> iterator = pending.iterator();
            while (iterator.hasNext()) {
                Op op = iterator.next();
                if (tryApply(op)) {
                    iterator.remove();
                    observeVersion(op);
                    progress = true;
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Garbage collection tombstone'ów (ADR-0005)
    // ------------------------------------------------------------------

    /**
     * Usuwa fizycznie tombstone'y, które są przyczynowo stabilne względem {@code stable}
     * i nie są originem żadnego istniejącego bloku.
     *
     * <p>Oba warunki są konieczne. Stabilność przyczynowa gwarantuje, że żadna <i>przyszła</i>
     * operacja nie może się o ten znak zaczepić; brak referencji gwarantuje, że nie robi tego
     * żadna operacja <i>już zastosowana</i>. Pominięcie drugiego warunku rozspójniłoby
     * strukturę natychmiast.
     *
     * @return liczba fizycznie usuniętych znaków
     */
    public int gc(VersionVector stable) {
        Set<Id> referenced = new HashSet<>();
        for (Block block : blocks) {
            if (block.originLeft() != null) {
                referenced.add(block.originLeft());
            }
        }

        int removed = 0;
        for (int i = blocks.size() - 1; i >= 0; i--) {
            Block block = blocks.get(i);
            if (!block.deleted()) {
                continue;
            }
            if (block.lastId().lamport() > stable.get(block.start().replicaId())) {
                continue;
            }
            if (isAnyCharReferenced(block, referenced)) {
                continue;
            }
            blocks.remove(i);
            removed += block.length();
        }
        return removed;
    }

    private static boolean isAnyCharReferenced(Block block, Set<Id> referenced) {
        for (int offset = 0; offset < block.length(); offset++) {
            if (referenced.contains(block.idAt(offset))) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Snapshoty (ADR-0003)
    // ------------------------------------------------------------------

    /** Migawka pełnego stanu do zapisania jako cache w bazie. */
    public DocumentSnapshot snapshot() {
        List<Block> copies = new ArrayList<>(blocks.size());
        for (Block block : blocks) {
            copies.add(new Block(block.start(), block.text(), block.originLeft(), block.deleted()));
        }
        return new DocumentSnapshot(DocumentSnapshot.CURRENT_FORMAT_VERSION, clock, maxSeen, copies);
    }

    /**
     * Odtwarza replikę z migawki.
     *
     * <p>Bloki są wstawiane po kolei bez uruchamiania algorytmu integracji — migawka
     * zapisuje je już w porządku dokumentu. To jest cała oszczędność, dla której snapshoty
     * istnieją: odtworzenie jest liniowe zamiast kosztować pełne przetworzenie historii.
     */
    public static RgaDocument restore(int replicaId, BlockList storage, DocumentSnapshot snapshot) {
        RgaDocument document = new RgaDocument(replicaId, storage);
        for (Block block : snapshot.blocks()) {
            storage.insert(storage.size(),
                new Block(block.start(), block.text(), block.originLeft(), block.deleted()));
        }
        document.clock = snapshot.clock();
        document.maxSeen.putAll(snapshot.maxSeen());
        return document;
    }

    // ------------------------------------------------------------------
    // Odczyt stanu
    // ------------------------------------------------------------------

    public String text() {
        StringBuilder builder = new StringBuilder(blocks.visibleLength());
        for (Block block : blocks) {
            if (!block.deleted()) {
                builder.append(block.text());
            }
        }
        return builder.toString();
    }

    public int visibleLength() {
        return blocks.visibleLength();
    }

    /** Liczba znaków będących tombstone'ami — licznik metryki {@code doc_tombstone_ratio}. */
    public int tombstoneCount() {
        return blocks.totalLength() - blocks.visibleLength();
    }

    /** Liczba bloków — miara skuteczności kompresji run-length. */
    public int blockCount() {
        return blocks.size();
    }

    public int pendingCount() {
        return pending.size();
    }

    public int replicaId() {
        return replicaId;
    }

    public long clock() {
        return clock;
    }

    public VersionVector version() {
        return VersionVector.of(maxSeen);
    }

    public BlockList blocks() {
        return blocks;
    }

    /** Identyfikator znaku o podanym indeksie w widocznym tekście — pozycja kursora (ADR-0006). */
    public Id idAtVisible(int visibleIndex) {
        BlockList.Locator loc = blocks.locateVisible(visibleIndex);
        if (loc == null) {
            throw new IndexOutOfBoundsException("brak widocznego znaku na pozycji " + visibleIndex);
        }
        return blocks.get(loc.blockIndex()).idAt(loc.offset());
    }

    // ------------------------------------------------------------------

    private void observeClock(Op op) {
        clock = Math.max(clock, op.maxLamport() + 1);
    }

    private void observeVersion(Op op) {
        maxSeen.merge(op.id().replicaId(), op.maxLamport(), Math::max);
    }

    @Override
    public String toString() {
        return "RgaDocument(replika=" + replicaId + ", tekst=\"" + text() + "\", bloki="
            + blocks.size() + ", tombstony=" + tombstoneCount() + ", oczekujące=" + pending.size() + ")";
    }
}
