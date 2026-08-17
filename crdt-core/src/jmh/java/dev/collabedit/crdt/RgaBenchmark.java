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
        random = new Random(20260814L);
        document = new RgaDocument(1, newBlockList());

        String chunk = "abcdefgh";
        while (document.visibleLength() < documentSize) {
            int position = document.visibleLength() == 0 ? 0 : random.nextInt(document.visibleLength() + 1);
            document.insert(position, chunk);
        }

        sampleIds = new Id[1024];
        for (int i = 0; i < sampleIds.length; i++) {
            sampleIds[i] = document.idAtVisible(random.nextInt(document.visibleLength()));
        }
    }

    @Benchmark
    public void locateByVisiblePosition(Blackhole blackhole) {
        blackhole.consume(document.idAtVisible(random.nextInt(document.visibleLength())));
    }

    @Benchmark
    public void locateByIdentifier(Blackhole blackhole) {
        blackhole.consume(document.blockList().locateId(sampleIds[random.nextInt(sampleIds.length)]));
    }

    @Benchmark
    public void insertAtRandomPosition(Blackhole blackhole) {
        blackhole.consume(document.insert(random.nextInt(document.visibleLength() + 1), "x"));
    }

    @Benchmark
    public void readFullText(Blackhole blackhole) {
        blackhole.consume(document.text());
    }
}
