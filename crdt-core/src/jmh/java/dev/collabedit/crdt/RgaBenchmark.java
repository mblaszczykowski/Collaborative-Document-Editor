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
 * Measuring the difference between {@link ArrayBlockList} (O(n)) and {@link TreapBlockList}
 * (O(log n)).
 *
 * <p>This benchmark exists so that the README carries a number rather than an adjective. It
 * measures three operations that happen on every keystroke in real use:
 * <ol>
 *   <li>turning a cursor position into a character identifier (editor → CRDT),</li>
 *   <li>finding a character by identifier (an incoming remote operation → CRDT),</li>
 *   <li>the insertion itself.</li>
 * </ol>
 *
 * <p><b>The methodology and its limits — read before quoting the results:</b>
 * <ul>
 *   <li>The document is built by <b>random insertions of eight-character chunks</b> rather than by
 *       continuous typing. Continuous typing compresses into a single block (see the property
 *       {@code sequentialTypingCompressesIntoFewBlocks}) and both implementations would come out
 *       identical — the measurement would say nothing. This distribution is closer to a document
 *       edited by several people.</li>
 *   <li>The {@code insertAtRandomPosition} benchmark <b>grows the document during the
 *       iteration</b> (by a few percent of its size). It therefore measures "an insertion into a
 *       document of roughly {@code documentSize}", not exactly that size. Rebuilding the document
 *       before every invocation would cost more than the measurement itself.</li>
 *   <li>The results depend on the machine. The hardware specification is given in the README next
 *       to the numbers — a number without its machine is useless.</li>
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
        // A fixed seed: both implementations have to receive an identical document, otherwise we
        // would be comparing different structures rather than different algorithms.
        random = new Random(20260814L);
        document = new RgaDocument(1, newBlockList());

        String chunk = "abcdefgh";
        while (document.visibleLength() < documentSize) {
            int position = document.visibleLength() == 0 ? 0 : random.nextInt(document.visibleLength() + 1);
            document.insert(position, chunk);
        }

        // Identifier samples scattered across the whole document — so that the lookup-by-
        // identifier measurement does not keep hitting the same hot fragment of the tree.
        sampleIds = new Id[1024];
        for (int i = 0; i < sampleIds.length; i++) {
            sampleIds[i] = document.idAtVisible(random.nextInt(document.visibleLength()));
        }
    }

    /** The editor says "cursor at position N" — the CRDT has to turn that into a character identifier. */
    @Benchmark
    public void locateByVisiblePosition(Blackhole blackhole) {
        blackhole.consume(document.idAtVisible(random.nextInt(document.visibleLength())));
    }

    /** A remote operation arrives anchored to a character — the CRDT has to find it. */
    @Benchmark
    public void locateByIdentifier(Blackhole blackhole) {
        blackhole.consume(document.blockList().locateId(sampleIds[random.nextInt(sampleIds.length)]));
    }

    /** A full insertion: finding the origin, splitting a block if needed, integrating. */
    @Benchmark
    public void insertAtRandomPosition(Blackhole blackhole) {
        blackhole.consume(document.insert(random.nextInt(document.visibleLength() + 1), "x"));
    }

    /** Reading the whole text — the cost of rendering the document from scratch. */
    @Benchmark
    public void readFullText(Blackhole blackhole) {
        blackhole.consume(document.text());
    }
}
