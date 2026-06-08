package dev.collabedit.app.persist;

import dev.collabedit.crdt.Id;
import dev.collabedit.crdt.Op;
import dev.collabedit.crdt.OpCodec;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Log operacji — <b>źródło prawdy</b> całego systemu (ADR-0003).
 *
 * <p>Tabela jest wyłącznie dopisywana. Nie ma tu metody aktualizującej ani usuwającej,
 * i to nie jest przeoczenie: przywrócenie starej wersji dokumentu zapisujemy jako nowe
 * operacje, bo obcięcie logu zniszczyłoby audytowalność i rozspójniło klientów, którzy
 * są właśnie podłączeni.
 */
@Repository
public class OperationStore {

    private final JdbcTemplate jdbc;

    public OperationStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Dopisuje wsad operacji, nadając im kolejne wartości {@code server_seq}.
     *
     * <h2>Dlaczego blokada wiersza dokumentu</h2>
     * ADR-0004 opiera wykrywanie zgubionych wiadomości Redisa na <b>ciągłości</b>
     * {@code server_seq} — dziura w numeracji oznacza „brakuje mi operacji". Sekwencja
     * Postgresa tej własności nie daje (zostawia dziury przy wycofanej transakcji), więc
     * licznik żyje w kolumnie {@code documents.next_server_seq} i jest przydzielany pod
     * blokadą wiersza.
     *
     * <h2>Dlaczego duplikaty są odsiewane PRZED przydziałem numerów</h2>
     * Gdyby polegać na {@code ON CONFLICT DO NOTHING}, część wsadu zostałaby odrzucona
     * <i>po</i> zarezerwowaniu dla niej numerów — i zostawiłaby dokładnie taką dziurę,
     * jakiej ADR-0004 zabrania. Odsiew działa poprawnie mimo współbieżności, bo blokada
     * wiersza dokumentu serializuje wszystkich piszących do tego dokumentu, również
     * z innych instancji.
     *
     * <p>Kosztem jest serializacja zapisów <b>w obrębie jednego dokumentu</b>. Jest ona
     * akceptowalna, bo zapisy i tak są wsadowane co 50 ms — pod blokadę wchodzi się raz
     * na wsad, a nie raz na naciśnięcie klawisza.
     *
     * <p><b>Zweryfikowane w CP10:</b> przy 50 klientach piszących jednocześnie w jeden
     * dokument blokada nie jest wąskim gardłem — przepustowość przyjmowania operacji sięga
     * 116 tys./s, a zbieżność osiągana jest w 239 ms przy dokumencie 100 tys. znaków.
     * Od CP10 wsad ma też górną granicę rozmiaru ({@code op-batch-max-size}), więc czas
     * trzymania blokady jest ograniczony niezależnie od tempa pisania.
     *
     * <p>Czego pomiar NIE obejmuje: klienci i serwer działają w jednym procesie, więc
     * przy realnym RTT łącza wsady byłyby większe, a blokada trzymana dłużej. Granica
     * jest więc <b>nie niższa</b> niż zmierzona, ale niekoniecznie równa.
     *
     * @return operacje faktycznie dopisane, wraz z nadanymi im numerami; pusta lista gdy
     *         cały wsad okazał się duplikatem
     */
    @Transactional
    public List<StoredOperation> append(UUID documentId, List<PendingOperation> operations) {
        Long nextSeq = jdbc.queryForObject(
            "SELECT next_server_seq FROM documents WHERE id = ? FOR UPDATE",
            Long.class, documentId);
        if (nextSeq == null) {
            throw new IllegalArgumentException("nie ma dokumentu " + documentId);
        }

        Set<String> alreadyStored = existingKeys(documentId, operations);

        List<Object[]> rows = new ArrayList<>(operations.size());
        List<StoredOperation> appended = new ArrayList<>(operations.size());
        Instant now = Instant.now();
        long seq = nextSeq;
        for (PendingOperation pending : operations) {
            Op op = pending.op();
            if (!alreadyStored.add(key(op))) {
                continue;
            }
            rows.add(new Object[] {
                documentId,
                op.id().replicaId(),
                op.id().lamport(),
                seq,
                opTypeCode(op),
                OpCodec.encode(op),
                pending.userId()
            });
            appended.add(new StoredOperation(op, seq, pending.userId(), now));
            seq++;
        }

        if (rows.isEmpty()) {
            return List.of();
        }

        jdbc.batchUpdate(
            "INSERT INTO operations (doc_id, replica_id, lamport, server_seq, op_type, payload, user_id) "
                + "VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)",
            rows);
        jdbc.update("UPDATE documents SET next_server_seq = ?, updated_at = now() WHERE id = ?",
            seq, documentId);

        return appended;
    }

    /**
     * Zapytanie o <b>stałej treści</b> — identyfikatory z wsadu wjeżdżają jako dwie tablice.
     *
     * <p>Poprzednia wersja skleja­ła listę krotek {@code IN ((?,?),(?,?),…)} StringBuilderem.
     * Działała i była bezpieczna (do tekstu trafiały wyłącznie znaki zapytania, wartości szły
     * parametrami), ale miała trzy wady, z których dwie są mierzalne:
     *
     * <ol>
     *   <li><b>Inne zapytanie na każdy rozmiar wsadu.</b> Wsad ma od jednej do stu operacji,
     *       więc powstawało do stu różnych tekstów SQL — każdy planowany od nowa, bez szansy
     *       na ponowne użycie planu.</li>
     *   <li><b>Gorszy plan.</b> Postgres budował {@code BitmapOr} z osobną gałęzią na każdą
     *       krotkę. Wersja z {@code unnest} dostaje {@code Index Only Scan} po kluczu głównym:
     *       jedno wyszukanie na parę, bez sięgania do sterty.</li>
     *   <li><b>Sklejany SQL jako wzorzec.</b> To było jedyne miejsce w projekcie, gdzie
     *       zapytanie powstawało przez konkatenację — a taki wzorzec kopiuje się później
     *       tam, gdzie bezpieczny już nie jest.</li>
     * </ol>
     *
     * <p>Zmierzone na dokumencie z 50 tys. operacji, przy wsadzie 100 operacji:
     * planowanie <b>3,27 ms → 1,03 ms</b>, wykonanie <b>1,54 ms → 0,30 ms</b>.
     */
    private static final String EXISTING_KEYS_SQL =
        "SELECT replica_id, lamport FROM operations "
            + "WHERE doc_id = ? AND (replica_id, lamport) IN (SELECT * FROM unnest(?, ?))";

    /** Klucze operacji z wsadu, które są już w bazie — odsiew duplikatów jednym zapytaniem. */
    private Set<String> existingKeys(UUID documentId, List<PendingOperation> operations) {
        if (operations.isEmpty()) {
            return new HashSet<>();
        }
        Integer[] replicas = new Integer[operations.size()];
        Long[] lamports = new Long[operations.size()];
        for (int i = 0; i < operations.size(); i++) {
            Id id = operations.get(i).op().id();
            replicas[i] = id.replicaId();
            lamports[i] = id.lamport();
        }

        List<String> found = jdbc.query(
            connection -> {
                PreparedStatement statement = connection.prepareStatement(EXISTING_KEYS_SQL);
                statement.setObject(1, documentId);
                statement.setArray(2, connection.createArrayOf("int4", replicas));
                statement.setArray(3, connection.createArrayOf("int8", lamports));
                return statement;
            },
            (rs, rowNum) -> rs.getInt("replica_id") + ":" + rs.getLong("lamport"));
        return new HashSet<>(found);
    }

    /**
     * Jednoznakowy kod typu operacji w kolumnie {@code operations.op_type}.
     *
     * <p><b>Wyczerpujący {@code switch}, nie ternarne {@code instanceof}.</b> Poprzednia
     * wersja brzmiała {@code op instanceof Op.Insert ? "i" : "d"} i była poprawna dokładnie
     * tak długo, jak długo {@link Op} miał dwa warianty. {@code Op} jest interfejsem
     * zapieczętowanym, więc kompilator wskaże wszystkie wyczerpujące {@code switch}-e
     * przy dodaniu wariantu — ale ternarnego {@code instanceof} nie wskaże, bo ten
     * kompiluje się dalej i po cichu zapisuje nowy typ operacji JAKO USUNIĘCIE.
     *
     * <p>Byłaby to korupcja źródła prawdy widoczna dopiero przy odtwarzaniu stanu z logu,
     * czyli po awarii — w najgorszym możliwym momencie.
     */
    private static String opTypeCode(Op op) {
        return switch (op) {
            case Op.Insert ignored -> "i";
            case Op.Delete ignored -> "d";
        };
    }

    private static String key(Op op) {
        return op.id().replicaId() + ":" + op.id().lamport();
    }

    /**
     * Operacje o {@code server_seq} większym niż podany — kursor resynchronizacji klienta
     * po ponownym połączeniu (CP6) oraz naprawy repliki po zgubionej wiadomości Redisa
     * (ADR-0004).
     */
    public List<StoredOperation> loadAfter(UUID documentId, long afterServerSeq) {
        return jdbc.query(
            "SELECT payload, server_seq, user_id, created_at FROM operations "
                + "WHERE doc_id = ? AND server_seq > ? ORDER BY server_seq",
            (rs, rowNum) -> new StoredOperation(
                OpCodec.decode(rs.getString("payload")),
                rs.getLong("server_seq"),
                rs.getString("user_id"),
                rs.getTimestamp("created_at").toInstant()),
            documentId, afterServerSeq);
    }

    /**
     * Operacje z przedziału {@code (from, to]} — potrzebne przy odtwarzaniu stanu
     * „na moment T" ponad snapshotem (ADR-0003, CP5).
     */
    public List<StoredOperation> loadRange(UUID documentId, long afterServerSeq, long throughServerSeq) {
        return jdbc.query(
            "SELECT payload, server_seq, user_id, created_at FROM operations "
                + "WHERE doc_id = ? AND server_seq > ? AND server_seq <= ? ORDER BY server_seq",
            (rs, rowNum) -> new StoredOperation(
                OpCodec.decode(rs.getString("payload")),
                rs.getLong("server_seq"),
                rs.getString("user_id"),
                rs.getTimestamp("created_at").toInstant()),
            documentId, afterServerSeq, throughServerSeq);
    }

    /** Najwyższy zapisany {@code server_seq}; 0 gdy dokument nie ma jeszcze operacji. */
    public long maxServerSeq(UUID documentId) {
        Long value = jdbc.queryForObject(
            "SELECT COALESCE(MAX(server_seq), 0) FROM operations WHERE doc_id = ?",
            Long.class, documentId);
        return value == null ? 0L : value;
    }

    /** Najwyższy {@code server_seq} zapisany nie później niż w podanym momencie (CP5). */
    public long maxServerSeqAt(UUID documentId, java.time.Instant moment) {
        Long value = jdbc.queryForObject(
            "SELECT COALESCE(MAX(server_seq), 0) FROM operations WHERE doc_id = ? AND created_at <= ?",
            Long.class, documentId, Timestamp.from(moment));
        return value == null ? 0L : value;
    }
}
