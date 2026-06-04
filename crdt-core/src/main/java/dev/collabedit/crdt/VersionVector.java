package dev.collabedit.crdt;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Najwyższa wartość zegara Lamporta widziana od każdej repliki.
 *
 * <p><b>Do czego to NIE służy:</b> do wykrywania duplikatów operacji. Zegar Lamporta jest
 * nieciągły w obrębie repliki (skacze przy odbiorze operacji zdalnych), więc „widziałem od A
 * wszystko do 48" nie odróżnia dziury po zgubionej operacji od dziury po skoku zegara.
 * Duplikaty wstawień wykrywamy przez sprawdzenie, czy znak o danym identyfikatorze już jest
 * w strukturze; duplikaty usunięć są nieszkodliwe, bo ustawienie tombstone'u jest idempotentne.
 * Rozwinięcie w {@code docs/crdt-design.md}, sekcja 2.
 *
 * <p><b>Do czego służy:</b>
 * <ul>
 *   <li>stabilność przyczynowa na potrzeby GC tombstone'ów (ADR-0005) — {@link #meet},</li>
 *   <li>wymiana „co mam" przy ponownym połączeniu (CP6),</li>
 *   <li>diagnostyka: która replika blokuje GC.</li>
 * </ul>
 *
 * <p>Struktura niemutowalna. Przy kilkudziesięciu replikach na dokument kopiowanie mapy przy
 * każdej operacji byłoby marnotrawstwem, więc mutowalny wariant roboczy żyje wewnątrz
 * {@link RgaDocument}, a ta klasa reprezentuje migawkę.
 */
public final class VersionVector {

    private static final VersionVector EMPTY = new VersionVector(Map.of());

    private final Map<Integer, Long> maxSeen;

    private VersionVector(Map<Integer, Long> maxSeen) {
        this.maxSeen = maxSeen;
    }

    public static VersionVector empty() {
        return EMPTY;
    }

    public static VersionVector of(Map<Integer, Long> maxSeen) {
        return maxSeen.isEmpty() ? EMPTY : new VersionVector(Map.copyOf(maxSeen));
    }

    /** Najwyższy widziany lamport od danej repliki; {@code -1} gdy nic od niej nie widzieliśmy. */
    public long get(int replicaId) {
        return maxSeen.getOrDefault(replicaId, -1L);
    }

    public Set<Integer> replicas() {
        return Collections.unmodifiableSet(maxSeen.keySet());
    }

    public boolean isEmpty() {
        return maxSeen.isEmpty();
    }

    public VersionVector with(int replicaId, long lamport) {
        if (get(replicaId) >= lamport) {
            return this;
        }
        Map<Integer, Long> next = new HashMap<>(maxSeen);
        next.put(replicaId, lamport);
        return new VersionVector(Map.copyOf(next));
    }

    /**
     * Element wspólny (po współrzędnych minimum) — punkt, do którego <b>wszystkie</b> podane
     * repliki na pewno widziały historię.
     *
     * <p>To jest {@code stableVersion} z ADR-0005: operacje poniżej tej granicy są przyczynowo
     * stabilne, więc ich tombstone'y można fizycznie usunąć.
     *
     * <p>Uwaga na przypadek brzegowy: jeśli którakolwiek replika nie widziała <i>nic</i> od
     * repliki R, to minimum dla R wynosi {@code -1} i nic z R nie jest stabilne. Jest to
     * zamierzone — nowo podłączona replika nie może odblokować GC tylko dlatego, że jej
     * version vector jest pusty.
     */
    public static VersionVector meet(Iterable<VersionVector> vectors) {
        Map<Integer, Long> result = null;
        for (VersionVector v : vectors) {
            if (result == null) {
                result = new HashMap<>(v.maxSeen);
                continue;
            }
            result.keySet().retainAll(v.maxSeen.keySet());
            result.replaceAll((replica, lamport) -> Math.min(lamport, v.maxSeen.get(replica)));
        }
        return result == null || result.isEmpty() ? EMPTY : new VersionVector(Map.copyOf(result));
    }

    /**
     * Replika, która najbardziej opóźnia {@code stableVersion} względem podanego stanu
     * docelowego — czyli odpowiedź na pytanie „dlaczego GC stoi" (metryka z ADR-0005).
     * {@code null}, gdy nic nie zostaje w tyle.
     */
    public static Integer laggingReplica(VersionVector stable, VersionVector target) {
        Integer worst = null;
        long worstGap = 0;
        for (int replica : target.replicas()) {
            long gap = target.get(replica) - stable.get(replica);
            if (gap > worstGap) {
                worstGap = gap;
                worst = replica;
            }
        }
        return worst;
    }

    /** Kopia jako zwykła mapa — do serializacji i asercji w testach. */
    public Map<Integer, Long> asMap() {
        return new TreeMap<>(maxSeen);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof VersionVector other && maxSeen.equals(other.maxSeen);
    }

    @Override
    public int hashCode() {
        return maxSeen.hashCode();
    }

    @Override
    public String toString() {
        return new TreeMap<>(maxSeen).toString();
    }
}
