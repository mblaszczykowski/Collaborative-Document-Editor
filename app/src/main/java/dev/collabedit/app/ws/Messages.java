package dev.collabedit.app.ws;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;

/**
 * Protokół komunikatów STOMP.
 *
 * <p>Kanały:
 * <pre>
 *   klient → serwer   /app/doc/{id}/op         {@link ClientOp}
 *                     /app/doc/{id}/sync       {@link SyncRequest}
 *                     /app/doc/{id}/presence   {@link ClientPresence}
 *
 *   serwer → klienci  /topic/doc/{id}/ops      {@link OpBroadcast}
 *                     /topic/doc/{id}/acks     {@link OpAck}
 *                     /topic/doc/{id}/presence {@link PresenceBroadcast}
 *   serwer → klient   /user/queue/sync         {@link SyncResponse}
 *                     /user/queue/errors       {@link ErrorMessage}
 * </pre>
 *
 * <h2>Dlaczego rozgłoszenie i potwierdzenie to DWA komunikaty</h2>
 * Kolejność kroków na serwerze wynika z ADR-0004: scalenie → rozgłoszenie do własnych
 * klientów → zapis → publikacja do Redisa. Rozgłoszenie wyprzedza zapis, bo to ono odpowiada
 * za to, co użytkownik <b>widzi</b> — cudzy tekst ma się pojawić natychmiast, a nie po
 * potwierdzeniu z bazy.
 *
 * <p>Potwierdzenie musi jednak przyjść <b>po</b> zapisie, bo dopiero wtedy wolno klientowi
 * zwolnić operację ze swojego bufora offline. Gdyby zwalniał ją po rozgłoszeniu, pad instancji
 * między rozgłoszeniem a zapisem oznaczałby operację, którą inni już widzą, a której nikt
 * nie potrafi odtworzyć. To jest wprost failure mode opisany w README.
 *
 * <p>Stąd dwa komunikaty zamiast jednego wzbogaconego. Cena: dodatkowa ramka rzędu 30 bajtów
 * na operację. Zysk: propagacja nie czeka na dysk, a mimo to nic nie ginie.
 *
 * <h2>Dlaczego potwierdzenia idą na temat, a nie do konkretnego klienta</h2>
 * Potwierdzenie niesie {@code serverSeq}, który jest kursorem resynchronizacji. Rozgłaszając
 * je wszystkim, każdy klient wie, dokąd doszedł log — również ten, który akurat nic nie pisał.
 * Dzięki temu po rozłączeniu prosi o brakujące operacje od właściwego miejsca, zamiast
 * pobierać całą historię.
 */
public final class Messages {

    private Messages() {
    }

    // ------------------------------------------------------------------
    // klient → serwer
    // ------------------------------------------------------------------

    /** Operacja CRDT w formacie {@code OpCodec} (ADR-0002: pola jednoliterowe). */
    public record ClientOp(JsonNode op) {
    }

    /**
     * Prośba o brakujące operacje po ponownym połączeniu.
     *
     * @param sinceServerSeq ostatni numer, który klient na pewno ma; 0 oznacza „daj wszystko"
     * @param version        version vector klienta — służy do wyliczenia stabilności
     *                       przyczynowej dla GC (ADR-0005), nie do wyboru operacji do wysłania
     */
    public record SyncRequest(long sinceServerSeq, Map<Integer, Long> version) {
    }

    /**
     * Pozycja kursora i zaznaczenia.
     *
     * <p>Pozycje są <b>identyfikatorami znaków CRDT</b>, nie offsetami (ADR-0006). Offset
     * rozjeżdża się, gdy ktoś wstawi tekst przed kursorem — identyfikator przesuwa się
     * razem z tekstem, bo jest przyczepiony do znaku, a nie do miejsca.
     */
    public record ClientPresence(
        Integer cursorReplicaId, Long cursorLamport,
        Integer anchorReplicaId, Long anchorLamport,
        boolean typing
    ) {
    }

    // ------------------------------------------------------------------
    // serwer → klienci
    // ------------------------------------------------------------------

    /** Rozgłoszenie operacji. Bez {@code serverSeq} — ten przychodzi w {@link OpAck}. */
    public record OpBroadcast(JsonNode op, String userId) {
    }

    /** Potwierdzenie trwałego zapisu. {@code r}/{@code l} to identyfikator operacji. */
    public record OpAck(int r, long l, long serverSeq) {
    }

    public record SyncResponse(List<JsonNode> ops, long headServerSeq) {
    }

    /**
     * Odpowiedź na dołączenie: identyfikator repliki nadany tej sesji.
     *
     * Klient NIE MOŻE go sobie wylosować. Identyfikatory replik muszą być globalnie
     * unikalne, bo kolizja sprawia, że algorytm uzna dwa różne znaki za ten sam i po cichu
     * zgubi tekst jednego z autorów — błąd, po którym nie zostaje w strukturze żaden ślad.
     * Dlatego nadaje je jeden licznik w Redisie.
     */
    public record SessionInfo(int replicaId, String userId) {
    }

    public record PresenceBroadcast(
        int replicaId,
        String userId,
        String displayName,
        String color,
        Integer cursorReplicaId, Long cursorLamport,
        Integer anchorReplicaId, Long anchorLamport,
        boolean typing,
        boolean online
    ) {
    }

    /**
     * Zmiana obecności — pełna lista albo pojedynczy wpis.
     *
     * <h2>Dlaczego to rozróżnienie w ogóle istnieje</h2>
     * Ruch kursora jest zdarzeniem <b>najczęstszym w całym systemie</b>: powstaje przy każdym
     * naciśnięciu klawisza i przy każdym ruchu strzałkami (auto-powtarzanie klawisza to
     * ~30 zdarzeń na sekundę). Odsyłanie w odpowiedzi PEŁNEJ listy uczestników do WSZYSTKICH
     * daje ruch rosnący z kwadratem liczby osób, w którym 99% treści nie uległo zmianie.
     *
     * <p>Dołączenie i odejście są rzadkie i wymagają pełnej listy, bo odejście trzeba wyrazić
     * przez <b>nieobecność</b> wpisu — delta nie potrafi powiedzieć „ten człowiek zniknął",
     * chyba że dołożylibyśmy trzeci rodzaj komunikatu. Przy tej częstotliwości pełna lista
     * jest tańsza niż dodatkowy typ w protokole.
     *
     * @param full    {@code true} → zastąp całą listę; {@code false} → scal po {@code replicaId}
     * @param entries uczestnicy: wszyscy przy {@code full}, jeden przy delcie
     */
    public record PresenceUpdate(boolean full, List<PresenceBroadcast> entries) {
    }

    /**
     * Błąd wymagający reakcji klienta.
     *
     * <p>{@code code} {@code RESYNC_REQUIRED} to sytuacja z ADR-0005: klient był offline
     * dłużej niż okno GC i jego operacje wskazują na tombstone'y, których już nie ma.
     * Serwer <b>odrzuca je jawnie</b>, zamiast doklejać tekst w przypadkowe miejsce albo
     * cicho go gubić — obie te alternatywy są gorsze niż uczciwe „nie potrafię tego scalić".
     */
    public record ErrorMessage(String code, String message) {
    }
}
