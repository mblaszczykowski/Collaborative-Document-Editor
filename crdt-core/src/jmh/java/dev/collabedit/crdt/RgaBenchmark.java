package dev.collabedit.crdt;

import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Pomiar różnicy między {@link ArrayBlockList} (O(n)) a {@link TreapBlockList} (O(log n)).
 *
 * <p>Ten benchmark istnieje po to, żeby w README stała liczba, a nie przymiotnik. Mierzy
 * trzy operacje, które w prawdziwym użyciu dzieją się na każde naciśnięcie klawisza:
 * <ol>
 *   <li>zamianę pozycji kursora na identyfikator znaku (edytor → CRDT),</li>
 *   <li>odnalezienie znaku po identyfikatorze (przychodząca operacja zdalna → CRDT),</li>
 *   <li>samo wstawienie.</li>
 * </ol>
 *
 * <p><b>Metodyka i jej ograniczenia — czytać przed cytowaniem wyników:</b>
 * <ul>
 *   <li>Dokument jest budowany <b>losowymi wstawieniami ośmioznakowych fragmentów</b>, a nie
 *       pisaniem ciągiem. Pisanie ciągiem kompresuje się do jednego bloku (patrz property
 *       {@code sequentialTypingCompressesIntoFewBlocks}) i obie implementacje wypadłyby
 *       identycznie — pomiar nie mówiłby nic. Ten rozkład jest bliższy dokumentowi
 *       redagowanemu przez wiele osób.</li>
 *   <li>Benchmark {@code insertAtRandomPosition} <b>powiększa dokument w trakcie iteracji</b>
 *       (o rzędu kilku procent rozmiaru). Mierzy więc „wstawienie do dokumentu o rozmiarze
 *       około {@code documentSize}", a nie dokładnie tego rozmiaru. Odbudowywanie dokumentu
 *       przed każdym wywołaniem kosztowałoby więcej niż sam pomiar.</li>
 *   <li>Wyniki zależą od maszyny. Specyfikacja sprzętu jest podawana w README razem
 *       z liczbami — liczba bez maszyny jest bezużyteczna.</li>
 * </ul>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
public class RgaBenchmark {

    @Param({"1000", "10000", "100000"})
    public int documentSize;

    @Param({"array", "treap"})
    public String implementation;

    private RgaDocument document;
    private Random random;
    private Id[] sampleIds;

    private BlockList newBlockList() {
        return "array".equals(implementation) ? new ArrayBlockList() : new TreapBlockList();
    }

    @Setup(Level.Trial)
    public void buildDocument() {
        // Stałe ziarno: dwie implementacje muszą dostać identyczny dokument, inaczej
        // porównywalibyśmy różne struktury, a nie różne algorytmy.
        random = new Random(20260814L);
        document = new RgaDocument(1, newBlockList());

        String chunk = "abcdefgh";
        while (document.visibleLength() < documentSize) {
            int position = document.visibleLength() == 0 ? 0 : random.nextInt(document.visibleLength() + 1);
            document.insert(position, chunk);
        }

        // Próbki identyfikatorów rozrzucone po całym dokumencie — żeby pomiar wyszukiwania
        // po identyfikatorze nie trafiał w kółko w ten sam, gorący fragment drzewa.
        sampleIds = new Id[1024];
        for (int i = 0; i < sampleIds.length; i++) {
            sampleIds[i] = document.idAtVisible(random.nextInt(document.visibleLength()));
        }
    }

    /** Edytor mówi „kursor na pozycji N" — CRDT musi zamienić to na identyfikator znaku. */
    @Benchmark
    public void locateByVisiblePosition(Blackhole blackhole) {
        blackhole.consume(document.idAtVisible(random.nextInt(document.visibleLength())));
    }

    /** Przychodzi operacja zdalna zaczepiona o znak — CRDT musi go odnaleźć. */
    @Benchmark
    public void locateByIdentifier(Blackhole blackhole) {
        blackhole.consume(document.blocks().locateId(sampleIds[random.nextInt(sampleIds.length)]));
    }

    /** Pełne wstawienie: wyszukanie originu, ewentualny podział bloku, integracja. */
    @Benchmark
    public void insertAtRandomPosition(Blackhole blackhole) {
        blackhole.consume(document.insert(random.nextInt(document.visibleLength() + 1), "x"));
    }

    /** Odczyt całego tekstu — koszt renderowania dokumentu od zera. */
    @Benchmark
    public void readFullText(Blackhole blackhole) {
        blackhole.consume(document.text());
    }
}
