package dev.collabedit.crdt;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Serializacja snapshotu (ADR-0003).
 *
 * <p>Snapshot jest cache'em, więc jego uszkodzenie nie powinno gubić danych — ale
 * <b>niemożność jego zapisania</b> to już realny problem: bez snapshotów każde otwarcie
 * dokumentu wymaga odtworzenia całego logu operacji.
 */
class SnapshotCodecTest {

    @Test
    @DisplayName("kodowanie w obie strony zachowuje strukturę, zegar i version vector")
    void roundTrip() {
        RgaDocument document = new RgaDocument(7);
        document.insert(0, "Inkline");
        document.insert(7, " dziala");
        document.delete(0, 3);

        DocumentSnapshot decoded = SnapshotCodec.decode(SnapshotCodec.encode(document.snapshot()));

        assertThat(decoded).isNotNull();
        RgaDocument restored = RgaDocument.restore(7, new TreapBlockList(), decoded);
        assertThat(restored.text()).isEqualTo(document.text());
        assertThat(restored.clock()).isEqualTo(document.clock());
        assertThat(restored.version()).isEqualTo(document.version());
        // Tombstone'y MUSZĄ przetrwać: mogą być punktem zaczepienia operacji, która
        // jeszcze nie dotarła. Snapshot bez nich rozspójniłby replikę po odtworzeniu.
        assertThat(restored.tombstoneCount()).isEqualTo(document.tombstoneCount());
    }

    @Test
    @DisplayName("REGRESJA: blok większy niż 64 KB daje się zapisać")
    void handlesBlocksLargerThan64Kilobytes() {
        // DataOutputStream.writeUTF koduje długość na dwóch bajtach i przy tekście powyżej
        // 64 KB rzuca „encoded string too long". Blok run-length po scaleniu ciągłego
        // pisania spokojnie ten rozmiar przekracza.
        //
        // Błąd był niewidoczny dla wszystkich testów poprawności, bo żaden nie tworzył
        // tak dużego bloku. Ujawnił się dopiero w harnessie obciążeniowym na dokumencie
        // o 100 tys. znaków — i to nie jako pad, tylko jako wpis w logu, przy jednoczesnym
        // cichym zaprzestaniu zapisywania snapshotów.
        RgaDocument document = new RgaDocument(1);
        document.insert(0, "x".repeat(100_000));

        assertThat(document.blockCount())
            .as("test nie tworzy jednego dużego bloku, więc nie sprawdza tego, co miał")
            .isEqualTo(1);

        DocumentSnapshot decoded = SnapshotCodec.decode(SnapshotCodec.encode(document.snapshot()));

        assertThat(decoded).isNotNull();
        assertThat(RgaDocument.restore(1, new TreapBlockList(), decoded).text())
            .hasSize(100_000);
    }

    @Test
    @DisplayName("znaki spoza ASCII przeżywają kodowanie")
    void handlesNonAsciiText() {
        RgaDocument document = new RgaDocument(1);
        document.insert(0, "zażółć gęślą jaźń — 日本語 — 🙂");

        DocumentSnapshot decoded = SnapshotCodec.decode(SnapshotCodec.encode(document.snapshot()));

        assertThat(RgaDocument.restore(1, new TreapBlockList(), decoded).text())
            .isEqualTo(document.text());
    }

    @Test
    @DisplayName("snapshot w nieobsługiwanym formacie jest ignorowany, a nie wysadza odczytu")
    void unknownFormatIsIgnored() {
        // ADR-0003: przy niezgodności wersji formatu snapshot ma zostać POMINIĘTY, a stan
        // odtworzony z logu. Rzucenie wyjątkiem uniemożliwiłoby otwarcie dokumentu po
        // każdej zmianie formatu serializacji.
        byte[] garbage = {0, 1, 2, 3, 4, 5};
        assertThat(SnapshotCodec.decode(garbage)).isNull();
    }
}
