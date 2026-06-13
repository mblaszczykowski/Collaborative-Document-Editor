package dev.collabedit.crdt;

import java.util.Set;

/**
 * Zachowanie znaczników przy pisaniu na granicy zakresu (CP13).
 *
 * <h2>Dlaczego to jest własność TYPU, a nie operacji</h2>
 * „Czy pogrubienie rośnie, gdy dopisuję na jego końcu" nie jest decyzją, którą użytkownik
 * podejmuje przy każdym pogrubieniu — jest oczekiwaniem związanym z rodzajem formatowania.
 * Pogrubienie rośnie, link nie. Gdyby to była własność operacji, dwa pogrubienia w tym samym
 * dokumencie mogłyby zachowywać się różnie, a użytkownik nie miałby jak przewidzieć którego.
 *
 * <p>Konsekwencja techniczna: znacznik nie musi nieść dodatkowego pola, bo zachowanie
 * wynika z jego typu. Format przewodowy zostaje o jedno pole mniejszy przy operacji,
 * której i tak jest dużo.
 *
 * <h2>Skąd te konkretne wartości</h2>
 * Z tego, jak zachowują się edytory, do których ludzie są przyzwyczajeni. Pisanie na końcu
 * pogrubionego słowa kontynuuje pogrubienie; pisanie na końcu linku nie rozszerza adresu,
 * bo tekst dopisany za linkiem prawie nigdy nie jest jego częścią. Kod źródłowy
 * ({@code code}) nie rośnie z tego samego powodu co link — jest zamkniętą jednostką.
 */
public final class MarkTypes {

    private MarkTypes() {
    }

    public static final String BOLD = "bold";
    public static final String ITALIC = "italic";
    public static final String UNDERLINE = "underline";
    public static final String STRIKETHROUGH = "strike";
    public static final String CODE = "code";
    public static final String LINK = "link";

    /**
     * Kotwica komentarza (CP14).
     *
     * <p>Jest tu, a nie w warstwie komentarzy, bo z punktu widzenia CRDT to jest zwykły
     * znacznik — cała różnica polega na tym, że jego wartością jest identyfikator wątku.
     * Stała istnieje po to, żeby ten łańcuch znaków nie był wpisywany z ręki w autoryzacji
     * ramek i w warstwie komentarzy, gdzie literówka nie wywołałaby błędu, tylko cichą
     * zmianę uprawnień.
     */
    public static final String COMMENT = "comment";

    /**
     * Typy, których zakres <b>rośnie</b> przy pisaniu tuż za nim.
     *
     * <p>Nieznany typ NIE rośnie. Jest to wybór ostrożny: znacznik, który rozszerza się
     * niespodziewanie, zmienia wygląd tekstu, którego użytkownik nie formatował — a znacznik,
     * który się nie rozszerza, wymaga najwyżej powtórzenia gestu.
     */
    private static final Set<String> EXPANDING = Set.of(BOLD, ITALIC, UNDERLINE, STRIKETHROUGH);

    public static boolean expandsAtEnd(String type) {
        return EXPANDING.contains(type);
    }
}
