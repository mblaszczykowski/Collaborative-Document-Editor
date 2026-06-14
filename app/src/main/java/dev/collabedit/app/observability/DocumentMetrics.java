package dev.collabedit.app.observability;

import dev.collabedit.app.domain.DocumentRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * Metryki systemu (CP7).
 *
 * <p>Dobór metryk wynika z pytań, na które trzeba umieć odpowiedzieć o <b>tym konkretnym</b>
 * systemie, a nie z listy „co zwykle się mierzy":
 *
 * <table>
 *   <caption>Metryka → pytanie, na które odpowiada</caption>
 *   <tr><th>Metryka</th><th>Pytanie</th></tr>
 *   <tr><td>{@code crdt_merge_duration}</td>
 *       <td>Czy scalanie zaczyna być wąskim gardłem przy rosnącym dokumencie?
 *           To jest miejsce, w którym różnica O(n) vs O(log n) staje się widoczna.</td></tr>
 *   <tr><td>{@code op_server_latency}</td>
 *       <td>Ile czasu operacja spędza po stronie serwera: przyjęcie → rozesłanie.
 *           Jedyna latencja mierzona jednym zegarem, więc jedyna, której liczbę
 *           można podać bez zastrzeżeń.</td></tr>
 *   <tr><td>{@code doc_tombstone_ratio}</td>
 *       <td>Czy GC w ogóle działa. Wartość rosnąca monotonicznie oznacza,
 *           że stabilność przyczynowa stoi — np. przez jednego zawieszonego klienta
 *           (ADR-0005).</td></tr>
 *   <tr><td>{@code cluster_gap_repairs}</td>
 *       <td>Jak często Redis gubi wiadomości. Wartość zero przez długi czas znaczy,
 *           że mechanizm naprawczy z ADR-0004 nie jest testowany w praktyce —
 *           czyli że nie wiadomo, czy działa.</td></tr>
 *   <tr><td>{@code ws_sessions_closed_total{reason}}</td>
 *       <td>Dlaczego kończą się sesje. Wartość {@code slow_client_buffer_overflow} rosnąca
 *           wraz z liczbą klientów jest wprost odpowiedzią na otwarte pytanie z README
 *           („Punkt degradacji"): klient nie nadąża z odbiorem, serwer zrywa mu sesję,
 *           a on wraca reconnectem — więc z zewnątrz nie widać nic poza rozjazdem.</td></tr>
 *   <tr><td>{@code crdt_pending_operations}</td>
 *       <td>Ile operacji czeka na swoją przyczynę. Wartość utrzymująca się powyżej zera
 *           oznacza trwale brakującą operację, czyli uszkodzenie — a nie przejściowy
 *           efekt kolejności w sieci.</td></tr>
 * </table>
 *
 * <p><b>Czego tu świadomie NIE ma:</b> metryki „latencja propagacji end-to-end". Wymagałaby
 * porównania czasu z dwóch różnych zegarów (przeglądarka nadawcy i przeglądarka odbiorcy),
 * a różnica ich ustawień bywa większa niż mierzona wielkość. Ta liczba jest mierzona
 * wyłącznie w harnessie benchmarkowym (CP8), gdzie obie strony dzielą jeden zegar
 * monotoniczny — i tylko tam jest podawana.
 */
@Component
public class DocumentMetrics {

    /** Trzymany, bo liczniki z tagiem powstają na żądanie — patrz {@link #recordSessionClose}. */
    private final MeterRegistry registry;

    private final Timer mergeDuration;
    private final Timer serverLatency;
    private final Timer persistDuration;
    private final Counter concurrentMerges;
    private final Counter gapRepairs;
    private final Counter gcReclaimedCharacters;
    private final Counter rejectedStaleOperations;
    private final Counter reconnects;

    private final AtomicInteger activeSessions = new AtomicInteger();

    public DocumentMetrics(MeterRegistry registry, DocumentRegistry documents) {
        this.registry = registry;
        this.mergeDuration = Timer.builder("crdt.merge.duration")
            .description("Czas scalenia jednej operacji do repliki w pamięci")
            // Percentyle liczone po stronie aplikacji, a nie z histogramu w Prometheusie.
            // Powód: chcemy móc podać p99 dla pojedynczej instancji bez sumowania kubełków,
            // a przy okazji zachować histogram do agregacji między instancjami.
            .publishPercentiles(0.5, 0.95, 0.99)
            .publishPercentileHistogram()
            .register(registry);

        this.serverLatency = Timer.builder("op.server.latency")
            .description("Przyjęcie operacji → rozesłanie do klientów tej instancji")
            .publishPercentiles(0.5, 0.95, 0.99)
            .publishPercentileHistogram()
            .register(registry);

        this.persistDuration = Timer.builder("op.persist.duration")
            .description("Czas zapisu wsadu operacji do logu")
            .publishPercentiles(0.5, 0.95, 0.99)
            .register(registry);

        this.concurrentMerges = Counter.builder("crdt.merges.total")
            .description("Operacje scalone do replik tej instancji")
            .register(registry);

        this.gapRepairs = Counter.builder("cluster.gap.repairs.total")
            .description("Naprawy luk w numeracji — czyli wiadomości zgubione przez Redis (ADR-0004)")
            .register(registry);

        // Nazwa zmieniona w CP18 z ".removed." na ".reclaimed.", bo licznik przestał znaczyć
        // to, co znaczył. GC nie tylko USUWA bloki — od CP18 blok, którego usunąć nie wolno
        // (origin, kotwica znacznika), PORZUCA TREŚĆ i zostaje w strukturze. Pamięć wraca
        // w obu przypadkach, ale „usunięte" opisuje już tylko jeden z nich.
        //
        // Zmiana nazwy metryki zrywa ciągłość szeregu historycznego i wymaga poprawki
        // w dashboardzie (ops/grafana/dashboards/collabedit.json). Świadomie: licznik, którego
        // nazwa mówi co innego niż wartość, jest gorszy niż przerwa w wykresie.
        this.gcReclaimedCharacters = Counter.builder("crdt.gc.reclaimed.characters.total")
            .description("Znaki, których pamięć odzyskał GC — przez usunięcie bloku albo "
                + "porzucenie jego treści (ADR-0005, CP18)")
            .register(registry);

        this.rejectedStaleOperations = Counter.builder("op.rejected.stale.total")
            .description("Operacje odrzucone, bo GC usunął ich punkt zaczepienia (ADR-0005)")
            .register(registry);

        this.reconnects = Counter.builder("ws.sessions.opened.total")
            .description("Nawiązane sesje WebSocket")
            .register(registry);

        Gauge.builder("ws.sessions.active", activeSessions, AtomicInteger::get)
            .description("Aktywne sesje WebSocket na tej instancji")
            .register(registry);

        Gauge.builder("doc.loaded.count", documents, registry2 -> registry2.loaded().size())
            .description("Dokumenty trzymane w pamięci tej instancji")
            .register(registry);

        // Udział tombstone'ów liczony po wszystkich dokumentach w pamięci. Wysoka wartość
        // utrzymująca się w czasie to sygnał, że GC nie działa — a nie że użytkownicy
        // dużo kasują.
        Gauge.builder("doc.tombstone.ratio", documents, DocumentMetrics::tombstoneRatio)
            .description("Tombstone'y / wszystkie znaki, po dokumentach w pamięci")
            .register(registry);

        Gauge.builder("crdt.pending.operations", documents, DocumentMetrics::pendingOperations)
            .description("Operacje czekające na swoją przyczynę — utrzymująca się wartość > 0 to uszkodzenie")
            .register(registry);
    }

    private static double tombstoneRatio(DocumentRegistry registry) {
        long visible = 0;
        long tombstones = 0;
        for (var replica : registry.loaded()) {
            visible += replica.withLock(document -> document.visibleLength());
            tombstones += replica.withLock(document -> document.tombstoneCount());
        }
        long total = visible + tombstones;
        return total == 0 ? 0.0 : (double) tombstones / total;
    }

    private static double pendingOperations(DocumentRegistry registry) {
        long pending = 0;
        for (var replica : registry.loaded()) {
            pending += replica.withLock(document -> document.pendingCount());
        }
        return pending;
    }

    public Timer.Sample startTimer() {
        return Timer.start();
    }

    public void recordMerge(Timer.Sample sample) {
        sample.stop(mergeDuration);
        concurrentMerges.increment();
    }

    public void recordServerLatency(Timer.Sample sample) {
        sample.stop(serverLatency);
    }

    public void recordPersist(Timer.Sample sample) {
        sample.stop(persistDuration);
    }

    public void recordGapRepair(int operationsRecovered) {
        gapRepairs.increment();
        if (operationsRecovered > 0) {
            concurrentMerges.increment(operationsRecovered);
        }
    }

    public void recordGc(int charactersReclaimed) {
        gcReclaimedCharacters.increment(charactersReclaimed);
    }

    public void recordStaleRejection() {
        rejectedStaleOperations.increment();
    }

    public void sessionOpened() {
        activeSessions.incrementAndGet();
        reconnects.increment();
    }

    public void sessionClosed() {
        activeSessions.decrementAndGet();
    }

    /**
     * Odnotowuje POWÓD zamknięcia sesji. Nie rusza licznika aktywnych sesji — ten schodzi
     * w {@link #sessionClosed()}, wołanym ze ścieżki presence. Dwa zdarzenia opisują to samo
     * rozłączenie i zdublowane odejmowanie zjechałoby z licznikiem poniżej zera.
     *
     * <p>Licznik jest tworzony na żądanie, bo tag {@code reason} jest częścią jego tożsamości.
     * Micrometer zwraca ten sam obiekt dla tej samej pary nazwa+tagi, więc nie powstaje
     * nowy licznik na każde rozłączenie.
     */
    public void recordSessionClose(String reason) {
        Counter.builder("ws.sessions.closed.total")
            .description("Zakończone sesje WebSocket wg powodu zamknięcia")
            .tag("reason", reason)
            .register(registry)
            .increment();
    }
}
