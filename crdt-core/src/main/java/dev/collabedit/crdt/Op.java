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
public sealed interface Op permits Op.Insert, Op.Delete {

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
