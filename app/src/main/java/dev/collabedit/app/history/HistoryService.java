package dev.collabedit.app.history;

import dev.collabedit.app.persist.OperationStore;
import dev.collabedit.app.persist.SnapshotStore;
import dev.collabedit.app.persist.StoredOperation;
import dev.collabedit.crdt.Op;
import dev.collabedit.crdt.RgaDocument;
import dev.collabedit.crdt.TreapBlockList;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Historia zmian i cofanie się w czasie (ADR-0003).
 *
 * <p>Cała ta funkcja jest darmowym produktem ubocznym decyzji z ADR-0003: skoro log operacji
 * jest źródłem prawdy i jest wyłącznie dopisywany, to <b>historia już istnieje</b> — trzeba ją
 * tylko odczytać. Nie ma tu osobnego mechanizmu wersjonowania, osobnej tabeli ani osobnego
 * zapisu przy każdej zmianie.
 *
 * <p>Cena jest po stronie odczytu: „stan na moment T" wymaga snapshotu plus odtworzenia ogona
 * operacji. Przy przewijaniu osi czasu krok po kroku ten koszt mnoży się przez liczbę kroków —
 * do zmierzenia w CP8.
 */
@Service
public class HistoryService {

    /**
     * Okno grupowania operacji w jeden wpis historii.
     *
     * <p>Log ma ziarnistość pojedynczej operacji, czyli w praktyce kilku znaków. Oś czasu
     * z wpisem na każde naciśnięcie klawisza byłaby bezużyteczna, więc kolejne operacje tego
     * samego autora sklejamy w jeden wpis. Dwie minuty to typowa długość jednej „myśli" przy
     * pisaniu — wartość dobrana z intuicji, nie z danych, i tak jest opisana w README.
     */
    private static final Duration GROUPING_WINDOW = Duration.ofMinutes(2);

    private final OperationStore operations;
    private final SnapshotStore snapshots;
    private final JdbcTemplate jdbc;

    public HistoryService(OperationStore operations, SnapshotStore snapshots, JdbcTemplate jdbc) {
        this.operations = operations;
        this.snapshots = snapshots;
        this.jdbc = jdbc;
    }

    /**
     * Wpis osi czasu: ciąg operacji jednego autora w jednym oknie czasowym.
     *
     * @param throughServerSeq numer, do którego trzeba odtworzyć log, żeby zobaczyć ten stan
     */
    public record Version(
        long throughServerSeq,
        String userId,
        String displayName,
        String color,
        Instant at,
        int operationCount,
        int charactersInserted,
        int charactersDeleted
    ) {
    }

    /** Oś czasu dokumentu, od najnowszych. */
    public List<Version> timeline(UUID documentId) {
        List<Row> rows = jdbc.query(
            """
            SELECT o.server_seq, o.op_type, o.payload, o.created_at,
                   COALESCE(o.user_id, '?') AS user_id,
                   COALESCE(u.display_name, 'Nieznany') AS display_name,
                   COALESCE(u.color, '#999999') AS color
            FROM operations o
            LEFT JOIN users u ON u.id = o.user_id
            WHERE o.doc_id = ?
            ORDER BY o.server_seq
            """,
            (rs, rowNum) -> new Row(
                rs.getLong("server_seq"),
                rs.getString("op_type"),
                rs.getString("payload"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getString("user_id"),
                rs.getString("display_name"),
                rs.getString("color")),
            documentId);

        List<Version> timeline = new ArrayList<>();
        Group group = null;
        for (Row row : rows) {
            if (group == null || !group.belongsTogether(row)) {
                if (group != null) {
                    timeline.add(group.toVersion());
                }
                group = new Group(row);
            }
            group.absorb(row);
        }
        if (group != null) {
            timeline.add(group.toVersion());
        }

        return timeline.reversed();
    }

    /**
     * Tekst dokumentu w chwili, gdy log sięgał podanego numeru.
     *
     * <p>Snapshot jest tu wyłącznie skrótem: gdyby go nie było, odtworzenie z całego logu
     * dałoby ten sam wynik. Ta równoważność jest testowana wprost, bo bez testu cache
     * po cichu stałby się źródłem prawdy.
     */
    public String textAt(UUID documentId, long throughServerSeq) {
        var snapshot = snapshots.findLatestUpTo(documentId, throughServerSeq);

        RgaDocument document;
        long from;
        if (snapshot.isPresent()) {
            document = RgaDocument.restore(0, new TreapBlockList(), snapshot.get().snapshot());
            from = snapshot.get().upToServerSeq();
        } else {
            document = new RgaDocument(0, new TreapBlockList());
            from = 0;
        }

        for (StoredOperation stored : operations.loadRange(documentId, from, throughServerSeq)) {
            document.apply(stored.op());
        }
        return document.text();
    }

    /**
     * Operacje potrzebne do przywrócenia stanu z podanego momentu.
     *
     * <p><b>Przywrócenie jest NOWĄ ZMIANĄ, nie cofnięciem logu</b> (ADR-0003 pkt 6).
     * Obcięcie logu zniszczyłoby trzy rzeczy naraz: audytowalność (kto i kiedy przywrócił),
     * spójność podłączonych klientów (ich repliki nadal miałyby usunięte operacje) oraz
     * niezmiennik „tabela wyłącznie dopisywana", na którym opiera się idempotencja zapisu.
     *
     * <p>Operacje są generowane na replice, która ma <b>aktualny</b> stan dokumentu, i sprowadzają
     * ją do stanu docelowego. Dla podłączonych klientów wygląda to jak zwykła edycja — bo nią jest.
     *
     * @param replica replika o aktualnym stanie, na której generujemy zmianę
     */
    public List<Op> restoreOperations(RgaDocument replica, String targetText) {
        String current = replica.text();
        if (current.equals(targetText)) {
            return List.of();
        }

        int prefix = 0;
        int maxPrefix = Math.min(current.length(), targetText.length());
        while (prefix < maxPrefix && current.charAt(prefix) == targetText.charAt(prefix)) {
            prefix++;
        }

        int suffix = 0;
        int maxSuffix = Math.min(current.length() - prefix, targetText.length() - prefix);
        while (suffix < maxSuffix
            && current.charAt(current.length() - 1 - suffix)
            == targetText.charAt(targetText.length() - 1 - suffix)) {
            suffix++;
        }

        // Wspólny przedrostek i przyrostek zamiast pełnego diffa: przywrócenie zwykle cofa
        // jeden spójny fragment, a operacja obejmująca zbyt szeroki zakres jest poprawna,
        // tylko mniej oszczędna. Pełny diff (Myers) dałby mniejsze operacje kosztem
        // złożoności, której tu nie widać w wyniku.
        List<Op> generated = new ArrayList<>();
        int removeFrom = prefix;
        int removeTo = current.length() - suffix;
        if (removeTo > removeFrom) {
            generated.add(replica.delete(removeFrom, removeTo - removeFrom));
        }
        String insert = targetText.substring(prefix, targetText.length() - suffix);
        if (!insert.isEmpty()) {
            generated.add(replica.insert(prefix, insert));
        }
        return generated;
    }

    // ------------------------------------------------------------------

    private record Row(long serverSeq, String opType, String payload, Instant at,
                       String userId, String displayName, String color) {
    }

    private static final class Group {
        private final String userId;
        private final String displayName;
        private final String color;
        private final Instant startedAt;
        private Instant lastAt;
        private long throughServerSeq;
        private int operationCount;
        private int inserted;
        private int deleted;

        Group(Row first) {
            this.userId = first.userId();
            this.displayName = first.displayName();
            this.color = first.color();
            this.startedAt = first.at();
            this.lastAt = first.at();
        }

        boolean belongsTogether(Row row) {
            return userId.equals(row.userId())
                && Duration.between(lastAt, row.at()).compareTo(GROUPING_WINDOW) <= 0;
        }

        void absorb(Row row) {
            lastAt = row.at();
            throughServerSeq = row.serverSeq();
            operationCount++;
            if ("i".equals(row.opType())) {
                inserted += textLengthOf(row.payload());
            } else {
                deleted += deletedLengthOf(row.payload());
            }
        }

        Version toVersion() {
            return new Version(throughServerSeq, userId, displayName, color, startedAt,
                operationCount, inserted, deleted);
        }

        /**
         * Liczba znaków w operacji odczytana wprost z JSON-a, bez pełnej deserializacji.
         *
         * <p>Oś czasu dokumentu z długą historią czyta tysiące wierszy tylko po to, żeby
         * policzyć znaki. Budowanie z każdego z nich obiektu operacji byłoby marnotrawstwem
         * na ścieżce, która i tak nie potrzebuje struktury — a jedynie długości.
         */
        private static int textLengthOf(String payload) {
            try {
                var node = dev.collabedit.crdt.OpCodec.mapper().readTree(payload).get("s");
                return node == null ? 0 : node.asText().length();
            } catch (Exception e) {
                return 0;
            }
        }

        private static int deletedLengthOf(String payload) {
            try {
                var ranges = dev.collabedit.crdt.OpCodec.mapper().readTree(payload).get("g");
                if (ranges == null) {
                    return 0;
                }
                int total = 0;
                for (var range : ranges) {
                    total += range.get(2).asInt();
                }
                return total;
            } catch (Exception e) {
                return 0;
            }
        }
    }
}
