package dev.collabedit.crdt;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Random;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;

/**
 * Najważniejsze testy w całym projekcie.
 *
 * <p>Każdy z nich odpowiada na konkretne pytanie „jaki realny failure mode to wyłapuje" —
 * żaden nie istnieje dla pokrycia. Tabela w README.
 *
 * <p>Testy chodzą domyślnie ze <b>stałym ziarnem</b> (determinizm — kontrprzykład da się
 * odtworzyć jednym poleceniem), a w nocnym buildzie z losowym ziarnem i 10× większą liczbą
 * prób ({@code -Pjqwik.thorough}). Znaleziony nocą kontrprzykład trafia do repo jako zwykły
 * test regresyjny.
 */
class RgaConvergenceProperties {

    @Provide
    Arbitrary<List<EditScript.Cmd>> editScripts() {
        return EditScript.scripts();
    }

    /**
     * <b>Zbieżność.</b> Repliki edytują całkowicie niezależnie, potem wymieniają się operacjami,
     * każda w innej losowej kolejności. Wszystkie muszą skończyć z identycznym tekstem.
     *
     * <p><b>Failure mode:</b> brak przemienności scalania — czyli ciche gubienie lub
     * duplikowanie zmian, gdy dwie osoby piszą jednocześnie. To jest ten błąd, dla którego
     * uniknięcia cały projekt istnieje, i jedyny, którego nie da się wyłapać ręcznym klikaniem.
     */
    @Property
    void convergence(
        @ForAll("editScripts") List<EditScript.Cmd> script,
        @ForAll @IntRange(min = 2, max = 5) int replicaCount,
        @ForAll @LongRange(min = 0, max = Long.MAX_VALUE) long deliverySeed
    ) {
        ReplicaSet replicas = new ReplicaSet(replicaCount, ArrayBlockList::new);
        EditScript.runIndependently(replicas, script);
        replicas.deliverAllShuffled(new Random(deliverySeed));

        assertThat(replicas.texts())
            .as("repliki nie zbiegły się:%n%s", replicas.diagnostics())
            .allMatch(text -> text.equals(replicas.texts().getFirst()));
    }

    /**
     * <b>Zbieżność przy przeplocie edycji i dostawy.</b> Repliki wymieniają się częścią
     * operacji w trakcie pisania, więc kolejne edycje powstają już na stanie uwzględniającym
     * cudze zmiany.
     *
     * <p><b>Failure mode:</b> błędy ujawniające się wyłącznie wtedy, gdy replika generuje
     * operację na stanie już scalonym z cudzymi zmianami — czyli w normalnej pracy, a nie
     * w sztucznym układzie „wszyscy piszą w izolacji, potem synchronizacja".
     *
     * <p><b>Ten test istnieje, bo go zabrakło.</b> Pierwotny zestaw zawierał wyłącznie
     * scenariusz bez przeplotu, w którym replika nigdy nie odbiera operacji przed
     * wygenerowaniem własnej — więc cała ścieżka podbijania zegara Lamporta
     * ({@code docs/crdt-design.md} sekcja 1) nie była wykonywana ani razu. Wykryte przez
     * celowe wprowadzenie mutacji do kodu produkcyjnego i sprawdzenie, czy testy ją złapią.
     */
    @Property
    void convergenceWithInterleavedDelivery(
        @ForAll("editScripts") List<EditScript.Cmd> script,
        @ForAll @IntRange(min = 2, max = 5) int replicaCount,
        @ForAll @LongRange(min = 0, max = Long.MAX_VALUE) long deliverySeed
    ) {
        ReplicaSet replicas = new ReplicaSet(replicaCount, ArrayBlockList::new);
        Random random = new Random(deliverySeed);
        EditScript.runInterleaved(replicas, script, random, 2);
        replicas.deliverAllShuffled(random);

        assertThat(replicas.allConverged())
            .as("repliki nie zbiegły się przy przeplocie edycji i dostawy:%n%s", replicas.diagnostics())
            .isTrue();
        assertThat(replicas.hasPendingOps())
            .as("operacje utknęły w buforze:%n%s", replicas.diagnostics())
            .isFalse();
    }

    /**
     * <b>Brak osieroconych operacji.</b> Po dostarczeniu wszystkiego bufor operacji
     * oczekujących musi być pusty w każdej replice.
     *
     * <p><b>Failure mode:</b> operacja utknęła w buforze na zawsze, bo jej warunek gotowości
     * został sformułowany zbyt ostro. Tekst wyglądałby wtedy na zbieżny (wszystkie repliki
     * zgodnie <i>nie mają</i> tej zmiany), więc sam test zbieżności by tego nie wykrył.
     */
    @Property
    void noOperationStaysPending(
        @ForAll("editScripts") List<EditScript.Cmd> script,
        @ForAll @IntRange(min = 2, max = 5) int replicaCount,
        @ForAll @LongRange(min = 0, max = Long.MAX_VALUE) long deliverySeed
    ) {
        ReplicaSet replicas = new ReplicaSet(replicaCount, ArrayBlockList::new);
        EditScript.runIndependently(replicas, script);
        replicas.deliverAllShuffled(new Random(deliverySeed));

        assertThat(replicas.hasPendingOps())
            .as("operacje utknęły w buforze:%n%s", replicas.diagnostics())
            .isFalse();
    }

    /**
     * <b>Idempotencja.</b> Dostarczenie każdej operacji dwukrotnie daje ten sam wynik,
     * co dostarczenie jednokrotne.
     *
     * <p><b>Failure mode:</b> retransmisja po ponownym połączeniu (CP6) albo powtórzona
     * wiadomość z Redisa (ADR-0004) duplikuje tekst. Bez tej własności cała architektura
     * z zawodnym broadcastem jest niebezpieczna.
     */
    @Property
    void idempotence(
        @ForAll("editScripts") List<EditScript.Cmd> script,
        @ForAll @IntRange(min = 2, max = 4) int replicaCount,
        @ForAll @LongRange(min = 0, max = Long.MAX_VALUE) long deliverySeed
    ) {
        ReplicaSet once = new ReplicaSet(replicaCount, ArrayBlockList::new);
        EditScript.runIndependently(once, script);
        once.deliverAllShuffled(new Random(deliverySeed));

        ReplicaSet twice = new ReplicaSet(replicaCount, ArrayBlockList::new);
        EditScript.runIndependently(twice, script);
        twice.deliverAllShuffledTwice(new Random(deliverySeed));

        assertThat(twice.texts().getFirst())
            .as("podwójne dostarczenie zmieniło wynik")
            .isEqualTo(once.texts().getFirst());
        assertThat(twice.allConverged()).isTrue();
    }

    /**
     * <b>Kolejność dostawy nie ma znaczenia.</b> Ten sam zbiór operacji dostarczony w dwóch
     * różnych losowych kolejnościach daje ten sam tekst.
     *
     * <p><b>Failure mode:</b> algorytm jest deterministyczny, ale zależny od kolejności —
     * czyli dwie instancje backendu (ADR-0004) rozjeżdżają się mimo posiadania kompletu
     * operacji. To jest podstępne, bo taki system działa poprawnie w testach z jednym
     * serwerem i psuje się dopiero przy skalowaniu.
     */
    @Property
    void deliveryOrderDoesNotMatter(
        @ForAll("editScripts") List<EditScript.Cmd> script,
        @ForAll @LongRange(min = 0, max = Long.MAX_VALUE) long seedA,
        @ForAll @LongRange(min = 0, max = Long.MAX_VALUE) long seedB
    ) {
        ReplicaSet runA = new ReplicaSet(3, ArrayBlockList::new);
        EditScript.runIndependently(runA, script);
        runA.deliverAllShuffled(new Random(seedA));

        ReplicaSet runB = new ReplicaSet(3, ArrayBlockList::new);
        EditScript.runIndependently(runB, script);
        runB.deliverAllShuffled(new Random(seedB));

        assertThat(runB.texts().getFirst()).isEqualTo(runA.texts().getFirst());
    }

    /**
     * <b>Zgodność z modelem odniesienia.</b> Na pojedynczej replice CRDT musi zachowywać się
     * dokładnie jak {@link StringBuilder} — sprawdzane po każdej operacji, nie tylko na końcu.
     *
     * <p><b>Failure mode:</b> błąd w mapowaniu pozycji widocznej na identyfikator, w podziale
     * bloków albo w kompresji run-length. Takie błędy potrafią być niewidoczne w teście
     * zbieżności, bo wszystkie repliki mylą się tak samo — zbiegają się do wspólnego,
     * ale <i>błędnego</i> tekstu.
     */
    @Property
    void singleReplicaBehavesLikeStringBuilder(@ForAll("editScripts") List<EditScript.Cmd> script) {
        RgaDocument document = new RgaDocument(1);
        StringBuilder reference = new StringBuilder();

        for (EditScript.Cmd cmd : script) {
            int length = reference.length();
            if (cmd.insert() || length == 0) {
                int position = cmd.positionSeed() % (length + 1);
                document.insert(position, cmd.text());
                reference.insert(position, cmd.text());
            } else {
                int position = cmd.positionSeed() % length;
                int count = Math.min(cmd.lengthSeed(), length - position);
                document.delete(position, count);
                reference.delete(position, position + count);
            }
            assertThat(document.text()).isEqualTo(reference.toString());
        }
    }

    /**
     * <b>Niezmiennik linearyzacji RGA.</b> W każdej replice, dla każdego bloku, wszystko
     * co leży między nim a jego originem musi mieć wyższy znacznik czasu.
     *
     * <p><b>Failure mode:</b> błąd, w którym <i>wszystkie</i> repliki mylą się tak samo.
     * Testy zbieżności porównują repliki między sobą, więc taki błąd przez nie przechodzi —
     * repliki zgodnie mają tę samą, niepoprawną strukturę. Ten test patrzy na strukturę
     * samą w sobie i nie ma jak zostać oszukany przez wspólny błąd.
     */
    @Property
    void rgaLinearizationInvariantHolds(
        @ForAll("editScripts") List<EditScript.Cmd> script,
        @ForAll @IntRange(min = 2, max = 4) int replicaCount,
        @ForAll @LongRange(min = 0, max = Long.MAX_VALUE) long deliverySeed
    ) {
        ReplicaSet replicas = new ReplicaSet(replicaCount, ArrayBlockList::new);
        Random random = new Random(deliverySeed);
        EditScript.runInterleaved(replicas, script, random, 2);
        replicas.deliverAllShuffled(random);

        for (int i = 0; i < replicas.size(); i++) {
            assertThat(RgaInvariants.violations(replicas.get(i)))
                .as("replika %d ma niepoprawną strukturę", i)
                .isEmpty();
        }
    }

    /**
     * <b>Kompresja run-length faktycznie działa.</b> Po zwykłym pisaniu ciągiem dokument
     * nie może rozpaść się na bloki jednoznakowe.
     *
     * <p><b>Failure mode:</b> warunek scalania bloków jest zbyt ostry i nigdy nie zachodzi.
     * Poprawność nie cierpi, ale znika cała korzyść z reprezentacji blokowej — dokument
     * na 100 tys. znaków to znowu 100 tys. obiektów. Błąd niewidoczny w testach poprawności,
     * a kosztowny; dlatego jest osobny test, a nie tylko benchmark.
     */
    @Property
    void sequentialTypingCompressesIntoFewBlocks(
        @ForAll @IntRange(min = 20, max = 200) int characters
    ) {
        RgaDocument document = new RgaDocument(1);
        for (int i = 0; i < characters; i++) {
            document.insert(i, "x");
        }
        assertThat(document.text()).hasSize(characters);
        assertThat(document.blockCount())
            .as("pisanie ciągiem %d znaków dało %d bloków zamiast 1", characters, document.blockCount())
            .isEqualTo(1);
        assertThat(RgaInvariants.fragmentation(document)).isEmpty();
    }

    /**
     * <b>GC zachowuje tekst.</b> Usunięcie przyczynowo stabilnych tombstone'ów nie może
     * zmienić widocznego tekstu ani rozspójnić struktury.
     *
     * <p><b>Failure mode:</b> GC kasuje węzeł, który jest originem innego znaku — struktura
     * rozpada się natychmiast, ale objawia się to dopiero przy następnym wstawieniu w to
     * miejsce. Patrz ADR-0005.
     */
    @Property
    void gcPreservesText(
        @ForAll("editScripts") List<EditScript.Cmd> script,
        @ForAll @IntRange(min = 2, max = 4) int replicaCount,
        @ForAll @LongRange(min = 0, max = Long.MAX_VALUE) long deliverySeed
    ) {
        ReplicaSet replicas = new ReplicaSet(replicaCount, ArrayBlockList::new);
        EditScript.runIndependently(replicas, script);
        replicas.deliverAllShuffled(new Random(deliverySeed));

        // Wszystkie repliki widziały już wszystko, więc stableVersion to po prostu
        // element wspólny ich version vectorów — cała historia jest przyczynowo stabilna.
        VersionVector stable = VersionVector.meet(
            java.util.stream.IntStream.range(0, replicas.size())
                .mapToObj(i -> replicas.get(i).version())
                .toList());

        for (int i = 0; i < replicas.size(); i++) {
            RgaDocument document = replicas.get(i);
            String before = document.text();
            document.gc(stable);
            assertThat(document.text())
                .as("GC zmienił tekst w replice %d", i)
                .isEqualTo(before);
        }

        // Po GC struktura musi nadal przyjmować nowe edycje — to właśnie tu wychodzi
        // skasowanie węzła, który był czyimś originem.
        RgaDocument first = replicas.get(0);
        first.insert(first.visibleLength(), "x");
        assertThat(first.text()).endsWith("x");
    }
}
