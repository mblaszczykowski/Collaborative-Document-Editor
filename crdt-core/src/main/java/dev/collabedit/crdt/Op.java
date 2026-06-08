package dev.collabedit.crdt;

import java.util.List;

/**
 * Operacja CRDT — jednostka zmiany przesyłana między replikami i zapisywana w logu operacji
 * (ADR-0003, gdzie {@code (doc_id, replica_id, lamport)} jest kluczem głównym, więc każda
 * operacja musi mieć własny identyfikator).
 *
 * <p>Operacje są <b>przemienne</b> i <b>idempotentne</b>: dowolna kolejność zastosowania
 * daje ten sam wynik, a powtórne zastosowanie niczego nie zmienia. To jest własność, którą
 * dowodzi property test {@code convergence} — i jedyny powód, dla którego architektura
 * z ADR-0004 (każda instancja backendu jako równorzędna replika, zawodny broadcast)
 * jest bezpieczna.
 */
public sealed interface Op permits Op.Insert, Op.Delete, Op.AddMark, Op.RemoveMark {

    /** Identyfikator operacji. Dla wstawienia jest to zarazem identyfikator pierwszego znaku. */
    Id id();

    /**
     * Ile wartości zegara Lamporta zajmuje ta operacja. Wstawienie {@code n} znaków zajmuje
     * {@code n} (znak {@code i} ma identyfikator {@code id.plus(i)}), usunięcie zajmuje 1.
     */
    int span();

    /** Najwyższy lamport zajęty przez tę operację — potrzebny do podbicia zegara przy odbiorze. */
    default long maxLamport() {
        return id().lamport() + span() - 1;
    }

    /**
     * Wstawienie ciągu znaków bezpośrednio po znaku {@code originLeft}.
     *
     * <p>Zapis run-length: {@code text} o długości {@code n} to jeden obiekt i jedna operacja,
     * a nie {@code n} operacji. Znak o indeksie {@code i} ma identyfikator {@code id.plus(i)},
     * a jego lewym originem jest znak {@code i-1}. Dzięki temu zwykłe pisanie — najczęstszy
     * wzorzec użycia — nie generuje obiektu na znak.
     *
     * @param originLeft znak, po którym wstawiamy; {@code null} oznacza początek dokumentu
     */
    record Insert(Id id, Id originLeft, String text) implements Op {

        public Insert {
            if (text == null || text.isEmpty()) {
                throw new IllegalArgumentException("wstawienie pustego tekstu nie ma sensu");
            }
        }

        @Override
        public int span() {
            return text.length();
        }
    }

    /**
     * Usunięcie zakresów znaków (oznaczenie tombstone'ami).
     *
     * <p>Zakresy, a nie pojedyncze identyfikatory, bo usunięcie zaznaczonego fragmentu tekstu
     * typowo obejmuje ciągły przedział identyfikatorów z jednej repliki — ale może też przeciąć
     * kilka bloków od różnych replik, stąd lista.
     *
     * <p>Usunięcie jest idempotentne, więc świadomie <b>nie</b> wykrywamy jego duplikatów:
     * ponowne ustawienie tombstone'u jest operacją pustą.
     */
    record Delete(Id id, List<Range> targets) implements Op {

        public Delete {
            if (targets == null || targets.isEmpty()) {
                throw new IllegalArgumentException("usunięcie bez celów nie ma sensu");
            }
            targets = List.copyOf(targets);
        }

        @Override
        public int span() {
            return 1;
        }
    }

    /**
     * Nadanie formatowania zakresowi tekstu (CP13).
     *
     * <h2>Dlaczego to jest OSOBNA operacja, a nie atrybut znaku</h2>
     * Formatowanie jako atrybut znaku wygląda na prostsze i jest błędne. Rozważmy: Ania
     * pogrubia zakres, a Bartek — nie widząc jeszcze jej zmiany — wpisuje tekst w środek
     * tego zakresu. Przy atrybutach per znak wstawiony tekst nie ma skąd wziąć pogrubienia:
     * w chwili powstania operacja Ani jeszcze do Bartka nie dotarła, a gdy dotrze, dotyczy
     * znaków, których w niej nie ma. Wynik: dziura w pogrubieniu, której nikt nie zrobił.
     *
     * <p>Znacznik zapisany jako <b>zakres między kotwicami</b> nie ma tego problemu, bo
     * kotwice przesuwają się razem z tekstem i tekst wstawiony w środek zakresu jest
     * w środku zakresu — niezależnie od kolejności dostarczenia.
     *
     * <h2>Rozstrzyganie konfliktów</h2>
     * Dla danego znaku i danego typu znacznika wygrywa operacja o najwyższym
     * {@code (lamport, replicaId)} spośród tych, które ten znak obejmują. Jest to ten sam
     * porządek totalny, którego używa reguła wstawiania RGA — bez nowego mechanizmu
     * arbitrażu i bez nowego założenia o zegarze.
     *
     * @param type  rodzaj formatowania, np. {@code bold}, {@code italic}, {@code link}
     * @param value wartość dla typów, które jej potrzebują (adres linku); {@code null} dla reszty
     */
    record AddMark(Id id, String type, String value, Anchor start, Anchor end) implements Op {

        public AddMark {
            if (type == null || type.isBlank()) {
                throw new IllegalArgumentException("znacznik musi mieć typ");
            }
            if (start == null || end == null) {
                throw new IllegalArgumentException("znacznik musi mieć obie kotwice");
            }
        }

        @Override
        public int span() {
            return 1;
        }
    }

    /**
     * Zdjęcie formatowania z zakresu (CP13).
     *
     * <h2>Dlaczego zakres, a nie referencja do konkretnego AddMark</h2>
     * Bo zdejmowanie formatowania nie jest cofaniem cudzej operacji. Dwoje ludzi może
     * pogrubić zachodzące na siebie zakresy, a trzeci odgrubić ich część wspólną —
     * i nie ma wtedy jednej operacji, którą dałoby się wskazać jako „tę do cofnięcia".
     *
     * <p>Zdjęcie jest więc równorzędną operacją zakresową, rozstrzyganą tym samym
     * porządkiem co nadanie: wygrywa ta o wyższym {@code (lamport, replicaId)}.
     */
    record RemoveMark(Id id, String type, Anchor start, Anchor end) implements Op {

        public RemoveMark {
            if (type == null || type.isBlank()) {
                throw new IllegalArgumentException("znacznik musi mieć typ");
            }
            if (start == null || end == null) {
                throw new IllegalArgumentException("znacznik musi mieć obie kotwice");
            }
        }

        @Override
        public int span() {
            return 1;
        }
    }

    /** Ciągły zakres identyfikatorów jednej repliki: {@code [start, start+length)}. */
    record Range(Id start, int length) {

        public Range {
            if (length <= 0) {
                throw new IllegalArgumentException("długość zakresu musi być dodatnia: " + length);
            }
        }

        public boolean contains(Id id) {
            return id.replicaId() == start.replicaId()
                && id.lamport() >= start.lamport()
                && id.lamport() < start.lamport() + length;
        }

        public Id endExclusive() {
            return start.plus(length);
        }
    }
}
