package dev.collabedit.app.auth;

/**
 * Rola użytkownika wobec dokumentu (CP12).
 *
 * <p>Role są <b>uporządkowane</b> i to uporządkowanie jest częścią modelu, a nie wygodą
 * implementacji: każda kolejna może wszystko to, co poprzednia. Dzięki temu sprawdzenie
 * uprawnienia jest porównaniem, a nie tablicą wyjątków — a tablica wyjątków jest miejscem,
 * w którym powstają dziury.
 *
 * <p><b>Czego celowo NIE ma:</b> roli „administrator" obejmującej wszystkie dokumenty.
 * Nie ma tu żadnego uprawnienia, które nie wynikałoby z relacji użytkownika do konkretnego
 * dokumentu albo workspace'u — dzięki temu odpowiedź na pytanie „kto ma dostęp do tego
 * dokumentu" jest zawsze pełna i lokalna.
 */
public enum Role {

    /** Czyta dokument i widzi kursory innych. Nie zmienia niczego. */
    VIEWER,

    /**
     * Jw. plus komentarze (CP14).
     *
     * <p>Ta rola jest powodem, dla którego autoryzacja musi patrzeć na <b>typ operacji</b>,
     * a nie tylko na dokument: komentujący wysyła operacje CRDT tym samym kanałem co edytor,
     * więc rozróżnienie „wolno pisać" od „wolno komentować" nie istnieje na poziomie
     * destynacji STOMP.
     */
    COMMENTER,

    /** Zmienia treść i formatowanie. */
    EDITOR,

    /** Jw. plus zarządzanie dostępem i widocznością dokumentu. */
    OWNER;

    public boolean atLeast(Role required) {
        return ordinal() >= required.ordinal();
    }

    public boolean canRead() {
        return true;
    }

    public boolean canWriteText() {
        return atLeast(EDITOR);
    }

    public boolean canComment() {
        return atLeast(COMMENTER);
    }

    public boolean canManageAccess() {
        return this == OWNER;
    }

    /**
     * Odczyt roli z bazy.
     *
     * <p>Nieznana wartość daje {@link #VIEWER}, czyli <b>najmniejsze</b> uprawnienie —
     * a nie wyjątek i nie największe. Rola dopisana w przyszłej migracji, o której ta wersja
     * kodu nie wie, ma w najgorszym razie ograniczyć użytkownika, a nie wpuścić go dalej,
     * niż powinien.
     */
    public static Role parse(String value) {
        if (value == null) {
            return VIEWER;
        }
        return switch (value.toLowerCase()) {
            case "owner" -> OWNER;
            case "editor" -> EDITOR;
            case "commenter" -> COMMENTER;
            default -> VIEWER;
        };
    }

    public String wire() {
        return name().toLowerCase();
    }
}
