package dev.collabedit.crdt;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;

/**
 * Dowód, że optymalizacja niczego nie zmieniła.
 *
 * <p>{@link TreapBlockList} zastępuje skanowanie liniowe drzewem z agregatami — czyli zmienia
 * najbardziej newralgiczną część struktury danych. Zamiast ufać, że przepisanie było wierne,
 * podajemy obu implementacjom <b>tę samą</b> losową sekwencję operacji i porównujemy nie tylko
 * wynikowy tekst, ale też każdą odpowiedź na zapytanie pozycyjne, po każdym kroku.
 *
 * <p>Ta para testów jest tym, co odróżnia „zoptymalizowałem" od „zoptymalizowałem i wiem,
 * że nadal działa".
 */
class BlockListEquivalenceProperties {

    @Provide
    Arbitrary<List<EditScript.Cmd>> editScripts() {
        return EditScript.scripts();
    }

    /**
     * <b>Równoważność na poziomie dokumentu.</b> Ten sam skrypt i ta sama kolejność dostawy
     * na obu implementacjach dają identyczny tekst i identyczny ciąg bloków.
     *
     * <p><b>Failure mode:</b> błąd w agregatach treapa — najczęściej brak aktualizacji sumy
     * widocznych znaków po oznaczeniu bloku tombstone'em. Objawia się tym, że wyszukiwanie
     * po pozycji zwraca zły znak, ale <i>tylko czasem</i> i tylko w dokumentach z tombstone'ami.
     */
    @Property
    void naiveAndIndexedProduceIdenticalDocuments(
        @ForAll("editScripts") List<EditScript.Cmd> script,
        @ForAll @IntRange(min = 2, max = 4) int replicaCount,
        @ForAll @LongRange(min = 0, max = Long.MAX_VALUE) long deliverySeed
    ) {
        ReplicaSet naive = new ReplicaSet(replicaCount, ArrayBlockList::new);
        EditScript.runInterleaved(naive, script, new Random(deliverySeed), 2);
        naive.deliverAllShuffled(new Random(deliverySeed));

        ReplicaSet indexed = new ReplicaSet(replicaCount, TreapBlockList::new);
        EditScript.runInterleaved(indexed, script, new Random(deliverySeed), 2);
        indexed.deliverAllShuffled(new Random(deliverySeed));

        assertThat(indexed.texts()).isEqualTo(naive.texts());

        for (int i = 0; i < replicaCount; i++) {
            assertThat(describeBlocks(indexed.get(i)))
                .as("replika %d ma inną strukturę bloków niż wersja naiwna", i)
                .isEqualTo(describeBlocks(naive.get(i)));
        }
    }

    /**
     * <b>Równoważność odpowiedzi na zapytania.</b> Po każdym kroku skryptu obie implementacje
     * muszą zwracać to samo dla <i>każdej</i> pozycji widocznej i <i>każdego</i> identyfikatora
     * obecnego w dokumencie.
     *
     * <p><b>Failure mode:</b> {@code rankOf} liczące pozycję po wskaźnikach do rodzica pomyliło
     * się po rotacji. Tekst pozostaje wtedy poprawny (bo wynika z przejścia in-order), więc
     * poprzedni test tego nie złapie — ale kursor użytkownika ląduje w złym miejscu.
     */
    @Property
    void bothImplementationsAnswerQueriesIdentically(
        @ForAll("editScripts") List<EditScript.Cmd> script
    ) {
        RgaDocument naive = new RgaDocument(1, new ArrayBlockList());
        RgaDocument indexed = new RgaDocument(1, new TreapBlockList());

        for (EditScript.Cmd cmd : script) {
            int length = naive.visibleLength();
            if (cmd.insert() || length == 0) {
                int position = cmd.positionSeed() % (length + 1);
                naive.insert(position, cmd.text());
                indexed.insert(position, cmd.text());
            } else {
                int position = cmd.positionSeed() % length;
                int count = Math.min(cmd.lengthSeed(), length - position);
                naive.delete(position, count);
                indexed.delete(position, count);
            }

            assertThat(indexed.text()).isEqualTo(naive.text());
            assertThat(indexed.visibleLength()).isEqualTo(naive.visibleLength());
            assertThat(indexed.tombstoneCount()).isEqualTo(naive.tombstoneCount());

            for (int position = 0; position < naive.visibleLength(); position++) {
                assertThat(indexed.idAtVisible(position))
                    .as("różny identyfikator na pozycji %d", position)
                    .isEqualTo(naive.idAtVisible(position));
            }

            for (Id id : allIds(naive)) {
                assertThat(indexed.blocks().locateId(id) != null)
                    .as("identyfikator %s znaleziony tylko przez jedną implementację", id)
                    .isTrue();
            }
        }
    }

    private static List<String> describeBlocks(RgaDocument document) {
        List<String> described = new ArrayList<>();
        for (Block block : document.blocks()) {
            described.add(block.toString());
        }
        return described;
    }

    private static List<Id> allIds(RgaDocument document) {
        List<Id> ids = new ArrayList<>();
        for (Block block : document.blocks()) {
            for (int offset = 0; offset < block.length(); offset++) {
                ids.add(block.idAt(offset));
            }
        }
        return ids;
    }
}
