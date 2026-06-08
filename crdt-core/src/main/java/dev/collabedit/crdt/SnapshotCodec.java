package dev.collabedit.crdt;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Kodowanie {@link DocumentSnapshot} do postaci binarnej.
 *
 * <p>Binarnie, a nie JSON-em, bo snapshot dużego dokumentu z długą historią to najcięższy
 * pojedynczy obiekt w systemie, a jego rozmiar wprost przekłada się na czas otwarcia dokumentu.
 * JSON kosztowałby tu kilkukrotnie więcej przy zerowej korzyści — snapshotu nikt nie czyta
 * oczami, w przeciwieństwie do operacji w logu, gdzie {@link OpCodec} celowo używa JSON-a.
 *
 * <p>Warstwa GZIP na wierzchu: tekst dokumentu kompresuje się bardzo dobrze, a koszt
 * procesora jest pomijalny wobec ruchu na dysku i w sieci.
 *
 * <h2>Format (wersja 1)</h2>
 * <pre>
 *   int    formatVersion
 *   long   clock
 *   int    liczba wpisów version vectora
 *     int  replicaId, long maxLamport
 *   int    liczba bloków
 *     int  replicaId, long startLamport
 *     bool ma origin, [int replicaId, long lamport]
 *     bool deleted
 *     UTF  tekst
 * </pre>
 *
 * <p>Bloki zapisane są <b>w kolejności dokumentu</b>, więc odtworzenie nie wymaga ponownego
 * uruchamiania algorytmu integracji — wystarczy je wczytać po kolei. To jest cała oszczędność
 * czasu, dla której snapshoty istnieją.
 */
public final class SnapshotCodec {

    private SnapshotCodec() {
    }

    /**
     * Zapis tekstu bloku jako długość + bajty UTF-8.
     *
     * <p>NIE {@code writeUTF}: ta metoda koduje długość na dwóch bajtach i wywala się przy
     * tekście dłuższym niż 64 KB, komunikatem „encoded string too long". Blok run-length
     * po scaleniu ciągłego pisania spokojnie przekracza ten rozmiar — wykryte przy pierwszym
     * uruchomieniu harnessu obciążeniowego na dokumencie o 100 tys. znaków, gdzie snapshoty
     * przestały się zapisywać, a jedynym objawem był wpis w logu.
     *
     * <p>Błąd był niewidoczny dla wszystkich wcześniejszych testów, bo żaden nie tworzył
     * pojedynczego bloku większego niż kilkadziesiąt znaków.
     */
    private static void writeLongString(DataOutputStream out, String text) throws IOException {
        byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String readLongString(DataInputStream in) throws IOException {
        byte[] bytes = new byte[in.readInt()];
        in.readFully(bytes);
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    public static byte[] encode(DocumentSnapshot snapshot) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(bytes))) {
            out.writeInt(snapshot.formatVersion());
            out.writeLong(snapshot.clock());

            out.writeInt(snapshot.maxSeen().size());
            for (Map.Entry<Integer, Long> entry : snapshot.maxSeen().entrySet()) {
                out.writeInt(entry.getKey());
                out.writeLong(entry.getValue());
            }

            out.writeInt(snapshot.blocks().size());
            for (Block block : snapshot.blocks()) {
                out.writeInt(block.start().replicaId());
                out.writeLong(block.start().lamport());
                boolean hasOrigin = block.originLeft() != null;
                out.writeBoolean(hasOrigin);
                if (hasOrigin) {
                    out.writeInt(block.originLeft().replicaId());
                    out.writeLong(block.originLeft().lamport());
                }
                out.writeBoolean(block.deleted());
                writeLongString(out, block.text());
            }

            // Znaczniki (CP13) zapisywane JAKO OPERACJE, w formacie przewodowym OpCodec,
            // a nie jako rozwiązane odcinki formatowania. Odcinki są wynikiem, nie stanem:
            // zapisanie ich oznaczałoby, że po odtworzeniu z snapshotu replika nie potrafi
            // już poprawnie scalić znacznika, który przyjdzie później — bo nie ma z czym
            // rozstrzygnąć konfliktu.
            out.writeInt(snapshot.marks().size());
            for (Op mark : snapshot.marks()) {
                writeLongString(out, OpCodec.encode(mark));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("nie da się zakodować snapshotu", e);
        }
        return bytes.toByteArray();
    }

    /**
     * @return odczytany snapshot, albo {@code null} gdy danych nie da się odczytać.
     *         Zwracanie {@code null} zamiast rzucania wyjątku jest celowe: snapshot to cache,
     *         a nieczytelny cache ma prowadzić do odtworzenia z logu, a nie do awarii.
     */
    public static DocumentSnapshot decode(byte[] data) {
        try (DataInputStream in = new DataInputStream(
            new GZIPInputStream(new ByteArrayInputStream(data)))) {

            int formatVersion = in.readInt();
            if (formatVersion != DocumentSnapshot.CURRENT_FORMAT_VERSION) {
                return null;
            }
            long clock = in.readLong();

            int versionEntries = in.readInt();
            Map<Integer, Long> maxSeen = new HashMap<>(versionEntries);
            for (int i = 0; i < versionEntries; i++) {
                maxSeen.put(in.readInt(), in.readLong());
            }

            int blockCount = in.readInt();
            List<Block> blocks = new ArrayList<>(blockCount);
            for (int i = 0; i < blockCount; i++) {
                Id start = new Id(in.readInt(), in.readLong());
                Id originLeft = in.readBoolean() ? new Id(in.readInt(), in.readLong()) : null;
                boolean deleted = in.readBoolean();
                blocks.add(new Block(start, readLongString(in), originLeft, deleted));
            }
            int markCount = in.readInt();
            List<Op> marks = new ArrayList<>(markCount);
            for (int i = 0; i < markCount; i++) {
                marks.add(OpCodec.decode(readLongString(in)));
            }
            return new DocumentSnapshot(formatVersion, clock, maxSeen, blocks, marks);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
