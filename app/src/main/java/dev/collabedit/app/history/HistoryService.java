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

    /**
     * The document's timeline, newest first.
     *
     * <h2>The grouping happens in the database, and it did not use to</h2>
     * This method used to read <b>every operation row of the document, payload included</b>, with
     * no limit and no pagination, and group them in Java. The payload is the largest column in
     * the table, so opening the history panel on a document with 100 000 operations sent the
     * entire operation log across the wire in order to produce a few dozen summary lines.
     *
     * <p>The previous version half-saw this: it read the character counts out of the JSON lazily
     * rather than deserialising an operation per row, with a comment explaining that building
     * thousands of objects "would be wasteful on a path that only needs lengths". It optimised
     * the parsing and paid for the transfer, which is the larger of the two costs.
     *
     * <p>Two changes together fix it. The counts are now columns, written when the operation is
     * (V5), so nothing needs the payload at all; and the sessionisation runs as a window function,
     * so what crosses the wire is <b>one row per timeline entry</b> rather than one per keystroke.
     *
     * <h2>The grouping rule is unchanged</h2>
     * A new entry starts when the author changes <b>or</b> when more than {@link #GROUPING_WINDOW}
     * passes since the <i>previous operation</i> — not since the start of the group. That is what
     * the Java version did, and {@code LAG} over {@code server_seq} expresses exactly the same
     * thing, which is why this could move without changing any test.
     */
    public List<Version> timeline(UUID documentId) {
        return jdbc.query(
            """
            WITH ops AS (
                SELECT o.server_seq,
                       o.created_at,
                       COALESCE(o.user_id, '?') AS user_id,
                       o.chars_inserted,
                       o.chars_deleted
                  FROM operations o
                 WHERE o.doc_id = ?
            ),
            boundaries AS (
                SELECT *,
                       CASE
                         WHEN LAG(user_id) OVER ordered IS DISTINCT FROM user_id
                           OR created_at - LAG(created_at) OVER ordered > ?::interval
                         THEN 1 ELSE 0
                       END AS starts_group
                  FROM ops
                WINDOW ordered AS (ORDER BY server_seq)
            ),
            grouped AS (
                SELECT *,
                       SUM(starts_group) OVER (ORDER BY server_seq) AS group_id
                  FROM boundaries
            ),
            sessions AS (
                SELECT group_id,
                       user_id,
                       MAX(server_seq)   AS through_server_seq,
                       MIN(created_at)   AS started_at,
                       COUNT(*)          AS operation_count,
                       SUM(chars_inserted) AS inserted,
                       SUM(chars_deleted)  AS deleted
                  FROM grouped
                 GROUP BY group_id, user_id
            )
            SELECT s.through_server_seq,
                   s.user_id,
                   COALESCE(u.display_name, 'Nieznany') AS display_name,
                   COALESCE(u.color, '#999999')         AS color,
                   s.started_at,
                   s.operation_count,
                   s.inserted,
                   s.deleted
              FROM sessions s
              LEFT JOIN users u ON u.id = s.user_id
             ORDER BY s.through_server_seq DESC
            """,
            (rs, rowNum) -> new Version(
                rs.getLong("through_server_seq"),
                rs.getString("user_id"),
                rs.getString("display_name"),
                rs.getString("color"),
                rs.getTimestamp("started_at").toInstant(),
                rs.getInt("operation_count"),
                rs.getInt("inserted"),
                rs.getInt("deleted")),
            documentId,
            GROUPING_WINDOW.toSeconds() + " seconds");
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

}
