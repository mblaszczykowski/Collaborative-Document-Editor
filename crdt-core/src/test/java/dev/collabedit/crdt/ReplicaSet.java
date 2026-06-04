package dev.collabedit.crdt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Symulator zbioru replik edytujących ten sam dokument — narzędzie testowe, nie kod produkcyjny.
 *
 * <p>Modeluje najgorszy możliwy przypadek dla algorytmu: repliki edytują <b>całkowicie
 * niezależnie</b> (maksymalna współbieżność), a dopiero potem wymieniają się operacjami
 * w <b>losowej kolejności</b>. Prawdziwa sieć jest łagodniejsza; jeśli algorytm przechodzi tu,
 * przechodzi wszędzie.
 *
 * <p>Świadomie nie ma tu żadnego uporządkowania dostawy ani deduplikacji po stronie
 * transportu — całą pracę ma wykonać {@link RgaDocument}. To jest sens tego testu.
 */
final class ReplicaSet {

    private final List<RgaDocument> replicas = new ArrayList<>();
    /** Operacje wraz z indeksem repliki, która je wygenerowała. */
    private final List<Emitted> emitted = new ArrayList<>();
    /** Indeksy operacji już dostarczonych do danej repliki — żeby nie dublować dostaw. */
    private final List<Set<Integer>> delivered = new ArrayList<>();

    record Emitted(int sourceReplica, Op op) { }

    ReplicaSet(int count, Supplier<BlockList> storage) {
        for (int i = 0; i < count; i++) {
            // Identyfikatory replik od 1: 0 jest łatwe do pomylenia z "brak repliki"
            // przy debugowaniu logów.
            replicas.add(new RgaDocument(i + 1, storage.get()));
            delivered.add(new HashSet<>());
        }
    }

    int size() {
        return replicas.size();
    }

    RgaDocument get(int index) {
        return replicas.get(index);
    }

    /** Wstawienie lokalne na replice; operacja trafia do puli do późniejszego rozesłania. */
    void insert(int replicaIndex, int position, String text) {
        emitted.add(new Emitted(replicaIndex, replicas.get(replicaIndex).insert(position, text)));
    }

    /** Usunięcie lokalne na replice; operacja trafia do puli do późniejszego rozesłania. */
    void delete(int replicaIndex, int position, int count) {
        emitted.add(new Emitted(replicaIndex, replicas.get(replicaIndex).delete(position, count)));
    }

    /**
     * Dostarcza każdej replice wszystkie cudze operacje w losowej kolejności.
     *
     * <p>Każda replika dostaje <b>inną</b> permutację — gdyby wszystkie dostawały tę samą,
     * test sprawdzałby dużo słabszą własność (determinizm przy ustalonej kolejności zamiast
     * przemienności).
     */
    void deliverAllShuffled(Random random) {
        for (int target = 0; target < replicas.size(); target++) {
            List<Op> inbox = new ArrayList<>();
            for (int i = 0; i < emitted.size(); i++) {
                if (emitted.get(i).sourceReplica() != target && delivered.get(target).add(i)) {
                    inbox.add(emitted.get(i).op());
                }
            }
            Collections.shuffle(inbox, random);
            replicas.get(target).applyAll(inbox);
        }
    }

    /**
     * Dostarcza każdej replice losowy <b>podzbiór</b> jeszcze niedostarczonych operacji.
     *
     * <p>Istnieje, bo bez tego cały zestaw testów miał dziurę: przy schemacie „najpierw
     * wszystkie edycje lokalnie, potem cała wymiana" replika nigdy nie generuje operacji
     * <i>po</i> odebraniu cudzej. A dokładnie wtedy ma znaczenie reguła podbijania zegara
     * Lamporta ({@code docs/crdt-design.md} sekcja 1) — bez tej metody usunięcie podbicia
     * zegara nie powodowało padu żadnego testu. Wykryte przez celowe wprowadzenie mutacji
     * do kodu produkcyjnego i sprawdzenie, czy testy ją złapią; nie złapały.
     */
    void deliverSome(Random random, int maxPerReplica) {
        for (int target = 0; target < replicas.size(); target++) {
            List<Integer> candidates = new ArrayList<>();
            for (int i = 0; i < emitted.size(); i++) {
                if (emitted.get(i).sourceReplica() != target && !delivered.get(target).contains(i)) {
                    candidates.add(i);
                }
            }
            Collections.shuffle(candidates, random);
            List<Op> inbox = new ArrayList<>();
            for (int i = 0; i < Math.min(maxPerReplica, candidates.size()); i++) {
                delivered.get(target).add(candidates.get(i));
                inbox.add(emitted.get(candidates.get(i)).op());
            }
            replicas.get(target).applyAll(inbox);
        }
    }

    /** Jak {@link #deliverAllShuffled}, ale każda operacja jest dostarczana dwukrotnie. */
    void deliverAllShuffledTwice(Random random) {
        for (int target = 0; target < replicas.size(); target++) {
            List<Op> inbox = new ArrayList<>();
            for (int i = 0; i < emitted.size(); i++) {
                if (emitted.get(i).sourceReplica() != target) {
                    delivered.get(target).add(i);
                    inbox.add(emitted.get(i).op());
                    inbox.add(emitted.get(i).op());
                }
            }
            Collections.shuffle(inbox, random);
            replicas.get(target).applyAll(inbox);
        }
    }

    List<Emitted> emitted() {
        return List.copyOf(emitted);
    }

    /** Teksty wszystkich replik — do asercji zbieżności. */
    List<String> texts() {
        return replicas.stream().map(RgaDocument::text).toList();
    }

    boolean allConverged() {
        List<String> texts = texts();
        return texts.stream().allMatch(t -> t.equals(texts.getFirst()));
    }

    /** Czy w którejkolwiek replice zostały operacje niezastosowane — to zawsze jest błąd. */
    boolean hasPendingOps() {
        return replicas.stream().anyMatch(r -> r.pendingCount() > 0);
    }

    String diagnostics() {
        StringBuilder sb = new StringBuilder();
        for (RgaDocument r : replicas) {
            sb.append(r).append('\n');
        }
        return sb.toString();
    }
}
