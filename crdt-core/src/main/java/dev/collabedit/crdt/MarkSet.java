package dev.collabedit.crdt;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Formatowanie tekstu jako osobna warstwa nad sekwencją znaków (CP13, podejście Peritext).
 *
 * <h2>Model</h2>
 * Znacznik nie jest atrybutem znaku, tylko <b>zakresem między dwiema kotwicami</b>
 * ({@link Anchor}). Zbiór operacji znacznikowych jest rosnący i nigdy nie jest modyfikowany
 * w miejscu — zdjęcie formatowania to kolejna operacja, a nie skasowanie poprzedniej.
 * Wynik dla dowolnego znaku wylicza się dopiero przy renderowaniu.
 *
 * <h2>Dlaczego atrybut per znak nie działa</h2>
 * Ania pogrubia zakres. Bartek, nie widząc jeszcze jej zmiany, wpisuje tekst w środek tego
 * zakresu. Przy atrybutach per znak wstawiony tekst nie ma skąd wziąć pogrubienia: w chwili
 * powstania operacja Ani do Bartka nie dotarła, a gdy dotrze, wymienia znaki, których w niej
 * nie ma. Wynik — dziura w pogrubieniu, której nikt nie zrobił.
 *
 * <p>Zakres między kotwicami tego problemu nie ma, bo kotwice przesuwają się razem z tekstem:
 * tekst wstawiony w środek zakresu <b>jest</b> w środku zakresu, niezależnie od kolejności
 * dostarczenia operacji.
 *
 * <h2>Rozstrzyganie konfliktów</h2>
 * Dla danego znaku i danego typu znacznika wygrywa operacja o najwyższym
 * {@code (lamport, replicaId)} spośród obejmujących ten znak. Jest to <b>ten sam porządek
 * totalny</b>, którego używa reguła wstawiania RGA — bez nowego mechanizmu arbitrażu
 * i bez nowego założenia o zegarze. Zbieżność wynika stąd, że porządek jest totalny
 * i identyczny na każdej replice, a zbiór operacji — ten sam.
 *
 * <h2>Złożoność — znana i nieoptymalna</h2>
 * Wyliczenie formatowania jest {@code O(m²)} przy {@code m} operacjach znacznikowych:
 * granice dzielą tekst na {@code O(m)} odcinków, a dla każdego przeglądamy wszystkie
 * operacje. Przy dokumencie z setkami znaczników to nadal ułamek milisekundy, przy
 * dziesiątkach tysięcy — zauważalne. Właściwym rozwiązaniem jest zamiatanie z kopcem
 * aktywnych zakresów ({@code O(m log m)}); niezrobione, bo wymaga pomiaru, którego nie mam.
 * Wymienione w README jako znana granica.
 */
public final class MarkSet {

    /**
     * Operacje znacznikowe, kluczowane identyfikatorem.
     *
     * <p>{@link LinkedHashMap} zamiast zwykłej mapy dla powtarzalności iteracji — wynik
     * nie zależy od niej (porządek rozstrzygania jest jawny), ale test, który zawodzi
     * raz na dziesięć przebiegów, jest gorszy od testu, który zawodzi zawsze.
     */
    private final Map<Id, Op> operations = new LinkedHashMap<>();

    /** Rozwiązany znacznik na odcinku widocznego tekstu {@code [from, to)}. */
    public record Span(int from, int to, Map<String, String> marks) {
    }

    /**
     * Dopisuje operację znacznikową.
     *
     * @return {@code true}, gdy operacja była nowością dla tej repliki
     */
    public boolean apply(Op op) {
        if (!(op instanceof Op.AddMark) && !(op instanceof Op.RemoveMark)) {
            throw new IllegalArgumentException("to nie jest operacja znacznikowa: " + op);
        }
        return operations.putIfAbsent(op.id(), op) == null;
    }

    public boolean contains(Id id) {
        return operations.containsKey(id);
    }

    public int size() {
        return operations.size();
    }

    public List<Op> all() {
        return List.copyOf(operations.values());
    }

    /**
     * Czy obie kotwice operacji da się osadzić w strukturze.
     *
     * <p>Znacznik, którego kotwica jeszcze nie dotarła, musi poczekać w buforze — dokładnie
     * tak jak wstawienie, które wyprzedziło swój origin. Zastosowanie go od razu dałoby
     * zakres zaczepiony o nieistniejące znaki, czyli formatowanie w przypadkowym miejscu.
     */
    public static boolean anchorsResolvable(Op op, BlockList blocks) {
        return switch (op) {
            case Op.AddMark mark ->
                blocks.locateId(mark.start().charId()) != null
                    && blocks.locateId(mark.end().charId()) != null;
            case Op.RemoveMark mark ->
                blocks.locateId(mark.start().charId()) != null
                    && blocks.locateId(mark.end().charId()) != null;
            default -> true;
        };
    }

    // ------------------------------------------------------------------
    // Wyliczanie formatowania
    // ------------------------------------------------------------------

    /** Operacja z wyliczonym zakresem w widocznych współrzędnych. */
    private record Resolved(Op op, String type, String value, boolean adding, int from, int to) {
    }

    /**
     * Formatowanie widocznego tekstu jako ciąg odcinków.
     *
     * <p>Odcinki są rozłączne, pokrywają tekst w kolejności i <b>nie zawierają odcinków
     * bez formatowania</b> — warstwa widoku dostaje wyłącznie to, co ma narysować.
     */
    public List<Span> spans(BlockList blocks) {
        List<Resolved> resolved = resolveAll(blocks);
        if (resolved.isEmpty()) {
            return List.of();
        }

        // Granice odcinków: początek i koniec każdego zakresu. Między dwiema kolejnymi
        // granicami formatowanie jest z definicji stałe, bo żaden zakres się tam nie zaczyna
        // ani nie kończy.
        TreeSet<Integer> boundaries = new TreeSet<>();
        for (Resolved entry : resolved) {
            boundaries.add(entry.from());
            boundaries.add(entry.to());
        }

        // Malejąco po (lamport, replicaId): pierwsza pasująca operacja danego typu wygrywa,
        // więc nie trzeba przeglądać pozostałych.
        List<Resolved> byPriority = new ArrayList<>(resolved);
        byPriority.sort(Comparator
            .comparingLong((Resolved entry) -> entry.op().id().lamport())
            .thenComparingInt(entry -> entry.op().id().replicaId())
            .reversed());

        List<Span> spans = new ArrayList<>();
        List<Integer> points = new ArrayList<>(boundaries);
        for (int i = 0; i + 1 < points.size(); i++) {
            int from = points.get(i);
            int to = points.get(i + 1);
            if (from >= to) {
                continue;
            }
            Map<String, String> marks = winnersAt(byPriority, from);
            if (!marks.isEmpty()) {
                spans.add(new Span(from, to, marks));
            }
        }
        return mergeAdjacent(spans);
    }

    /** Zwycięzcy per typ na odcinku zaczynającym się w {@code position}. */
    private static Map<String, String> winnersAt(List<Resolved> byPriority, int position) {
        Map<String, String> marks = new LinkedHashMap<>();
        java.util.Set<String> decided = new java.util.HashSet<>();
        for (Resolved entry : byPriority) {
            if (position < entry.from() || position >= entry.to()) {
                continue;
            }
            // Pierwsza napotkana operacja danego typu jest tą o najwyższym priorytecie,
            // bo lista jest posortowana malejąco. Kolejne tego typu już nie mają znaczenia —
            // niezależnie od tego, czy nadają, czy zdejmują.
            if (!decided.add(entry.type())) {
                continue;
            }
            if (entry.adding()) {
                marks.put(entry.type(), entry.value());
            }
        }
        return marks;
    }

    /**
     * Skleja sąsiadujące odcinki o identycznym formatowaniu.
     *
     * <p>Granice pochodzą od WSZYSTKICH zakresów, więc dwa pogrubienia stykające się końcami
     * dałyby dwa odcinki o tej samej treści. Warstwa widoku poradziłaby sobie z tym, ale
     * dwa sąsiednie identyczne zakresy dekoracji to dwa elementy DOM tam, gdzie wystarczy
     * jeden — a przy renderowaniu na każde naciśnięcie klawisza to jest różnica.
     */
    private static List<Span> mergeAdjacent(List<Span> spans) {
        if (spans.size() < 2) {
            return spans;
        }
        List<Span> merged = new ArrayList<>();
        Span current = spans.getFirst();
        for (int i = 1; i < spans.size(); i++) {
            Span next = spans.get(i);
            if (next.from() == current.to() && next.marks().equals(current.marks())) {
                current = new Span(current.from(), next.to(), current.marks());
            } else {
                merged.add(current);
                current = next;
            }
        }
        merged.add(current);
        return merged;
    }

    private List<Resolved> resolveAll(BlockList blocks) {
        List<Resolved> resolved = new ArrayList<>(operations.size());
        for (Op op : operations.values()) {
            Anchor start;
            Anchor end;
            String type;
            String value;
            boolean adding;
            switch (op) {
                case Op.AddMark mark -> {
                    start = mark.start();
                    end = mark.end();
                    type = mark.type();
                    value = mark.value();
                    adding = true;
                }
                case Op.RemoveMark mark -> {
                    start = mark.start();
                    end = mark.end();
                    type = mark.type();
                    value = null;
                    adding = false;
                }
                default -> throw new IllegalStateException("nieznacznikowa operacja w zbiorze");
            }

            Integer from = position(start, blocks);
            Integer to = position(end, blocks);
            if (from == null || to == null || from >= to) {
                // Kotwica poza strukturą (usunięta przez GC albo operacja wyprzedziła
                // swoją przyczynę) albo zakres pusty po skasowaniu całej zawartości.
                // Znacznik po prostu nic nie obejmuje — nie jest to błąd.
                continue;
            }
            resolved.add(new Resolved(op, type, value, adding, from, to));
        }
        return resolved;
    }

    /**
     * Pozycja kotwicy w widocznych współrzędnych.
     *
     * <p>Kotwica przyczepiona do tombstone'u nie znika — wskazuje szczelinę między widocznymi
     * znakami, które go otaczają. Dzięki temu skasowanie sformatowanego tekstu i wpisanie
     * nowego w to samo miejsce zachowuje formatowanie, zamiast je gubić.
     *
     * @return pozycja albo {@code null}, gdy znaku nie ma w strukturze
     */
    private static Integer position(Anchor anchor, BlockList blocks) {
        BlockList.Locator locator = blocks.locateId(anchor.charId());
        if (locator == null) {
            return null;
        }
        Block block = blocks.get(locator.blockIndex());
        int base = blocks.visibleOffsetOf(locator.blockIndex());
        if (block.deleted()) {
            // Tombstone nie zajmuje miejsca w widocznym tekście — obie strony kotwicy
            // wskazują tę samą szczelinę.
            return base;
        }
        return anchor.side() == Anchor.Side.BEFORE
            ? base + locator.offset()
            : base + locator.offset() + 1;
    }
}
