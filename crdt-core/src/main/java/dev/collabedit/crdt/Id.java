package dev.collabedit.crdt;

/**
 * Identyfikator pojedynczego znaku w dokumencie.
 *
 * <p>{@code lamport} jest wartością <b>zegara Lamporta</b>, nie licznikiem sekwencyjnym repliki.
 * To rozróżnienie jest wymogiem poprawności, a nie stylistyką — szczegóły z kontrprzykładem
 * w {@code docs/crdt-design.md}, sekcja 1. W skrócie: reguła wstawiania w RGA („idź w prawo,
 * dopóki napotykasz elementy o wyższym znaczniku") jest poprawna wyłącznie wtedy, gdy element
 * powstały przyczynowo później ma gwarantowanie wyższy znacznik. Licznik sekwencyjny per replika
 * tej własności nie ma.
 *
 * <p>Praktyczna konsekwencja: {@code lamport} <b>nie jest ciągły</b> w obrębie repliki — skacze
 * przy odbiorze operacji zdalnych. Dlatego version vector nie służy u nas do wykrywania
 * duplikatów (patrz {@link VersionVector}).
 *
 * <p>Porządek naturalny: najpierw {@code lamport}, potem {@code replicaId} jako rozstrzygnięcie
 * remisu. Remis jest częsty — dwie repliki działające współbieżnie na tym samym stanie wygenerują
 * ten sam {@code lamport}. {@code replicaId} czyni porządek totalnym bez potrzeby arbitra,
 * co jest całą istotą wyboru z ADR-0001.
 */
public record Id(int replicaId, long lamport) implements Comparable<Id> {

    public Id {
        if (lamport < 0) {
            throw new IllegalArgumentException("lamport nie może być ujemny: " + lamport);
        }
    }

    /** Identyfikator znaku przesuniętego o {@code offset} w tym samym bloku run-length. */
    public Id plus(int offset) {
        return new Id(replicaId, lamport + offset);
    }

    /**
     * Czy ten identyfikator i {@code other} pochodzą z tej samej repliki i są kolejnymi
     * wartościami zegara. Używane przy scalaniu bloków (run-length) — patrz
     * {@code docs/crdt-design.md}, sekcja 3.
     */
    public boolean isDirectSuccessorOf(Id other) {
        return other != null && other.replicaId == replicaId && other.lamport + 1 == lamport;
    }

    @Override
    public int compareTo(Id other) {
        int byLamport = Long.compare(lamport, other.lamport);
        return byLamport != 0 ? byLamport : Integer.compare(replicaId, other.replicaId);
    }

    @Override
    public String toString() {
        return replicaId + "@" + lamport;
    }
}
