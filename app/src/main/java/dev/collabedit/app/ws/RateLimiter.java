package dev.collabedit.app.ws;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ograniczenie tempa ramek przychodzących, osobno dla każdej sesji (CP12).
 *
 * <h2>Przed czym to chroni — i przed czym nie</h2>
 * Klient wysyłający operacje w pętli obciąża replikę w pamięci, log operacji i rozgłoszenie
 * do wszystkich pozostałych uczestników. Jeden taki klient potrafi więc zepsuć dokument
 * wszystkim, i nie musi być złośliwy — wystarczy pętla w kodzie, który ktoś napisał
 * na własnym kliencie.
 *
 * <p><b>To nie jest zabezpieczenie przed rozproszonym atakiem.</b> Limit jest per sesja,
 * a sesje da się mnożyć. Chroni przed jedną zepsutą albo złośliwą kartą przeglądarki,
 * czyli przed przypadkiem realnym; przed atakiem z wielu źródeł broni się warstwa przed
 * aplikacją, nie aplikacja.
 *
 * <h2>Dlaczego dwa osobne wiadra</h2>
 * Operacje i obecność mają zupełnie inny profil. Operacja powstaje najwyżej tak szybko,
 * jak człowiek pisze (kilkanaście na sekundę w szczycie), a obecność — przy trzymanej
 * strzałce — kilkadziesiąt. Jeden wspólny limit musiałby być na tyle wysoki, żeby zmieścić
 * obecność, przez co przestałby ograniczać operacje, czyli tę ścieżkę, która realnie kosztuje.
 *
 * <h2>Token bucket, nie okno</h2>
 * Okno stałe („N na sekundę") przepuszcza 2N ramek na granicy dwóch okien i odrzuca ruch,
 * który się w nim nie zmieścił, nawet jeśli poprzednia sekunda była pusta. Wiadro żetonów
 * dopuszcza krótkie zrywy (wklejenie tekstu, powrót po rozłączeniu z buforem operacji)
 * i ogranicza dopiero tempo ŚREDNIE — czyli dokładnie to, co chcemy ograniczyć.
 */
public class RateLimiter {

    /**
     * Wiadro żetonów uzupełniane w sposób ciągły.
     *
     * <p>Nie jest synchronizowane. Ramki jednej sesji przychodzą kanałem wejściowym,
     * który może mieć wiele wątków, więc teoretycznie możliwy jest wyścig na dwóch
     * jednoczesnych ramkach tej samej sesji. Skutkiem byłoby przepuszczenie jednej ramki
     * ponad limit — a to jest bez znaczenia dla mechanizmu, który ogranicza tempo średnie.
     * Synchronizacja kosztowałaby tu więcej niż wnosi.
     */
    private static final class Bucket {
        private final double capacity;
        private final double refillPerMs;
        private double tokens;
        private long lastRefillMillis;

        Bucket(double capacity, double perSecond, long nowMillis) {
            this.capacity = capacity;
            this.refillPerMs = perSecond / 1000.0;
            this.tokens = capacity;
            this.lastRefillMillis = nowMillis;
        }

        boolean tryConsume(long nowMillis) {
            tokens = Math.min(capacity, tokens + (nowMillis - lastRefillMillis) * refillPerMs);
            lastRefillMillis = nowMillis;
            if (tokens < 1.0) {
                return false;
            }
            tokens -= 1.0;
            return true;
        }
    }

    /**
     * Operacje: 60 na sekundę, zryw do 200.
     *
     * <p>60/s jest wyraźnie powyżej ludzkiego tempa pisania (rekordziści osiągają ~15 znaków
     * na sekundę, a każdy znak to jedna operacja), więc nie dotknie nikogo piszącego.
     * Zryw 200 mieści wklejenie akapitu i retransmisję bufora po powrocie z trybu offline —
     * dwie sytuacje, w których legalny klient wysyła dużo naraz.
     */
    private static final double OPS_PER_SECOND = 60;
    private static final double OPS_BURST = 200;

    /**
     * Obecność: 20 na sekundę, zryw do 40.
     *
     * <p>Klient ogranicza się sam do jednego raportu na 100 ms (patrz {@code Editor.tsx}),
     * czyli 10/s. Limit dwukrotnie wyższy zostawia zapas na jitter i nie karze klienta
     * za to, że ramki przyszły nierówno — ale zatrzymuje klienta, który tego ograniczenia
     * nie ma, bo nie jest naszą przeglądarką.
     */
    private static final double PRESENCE_PER_SECOND = 20;
    private static final double PRESENCE_BURST = 40;

    private final Map<String, Bucket> operations = new ConcurrentHashMap<>();
    private final Map<String, Bucket> presence = new ConcurrentHashMap<>();

    public boolean allowOperation(String sessionId, long nowMillis) {
        return operations
            .computeIfAbsent(sessionId, id -> new Bucket(OPS_BURST, OPS_PER_SECOND, nowMillis))
            .tryConsume(nowMillis);
    }

    public boolean allowPresence(String sessionId, long nowMillis) {
        return presence
            .computeIfAbsent(sessionId, id -> new Bucket(PRESENCE_BURST, PRESENCE_PER_SECOND, nowMillis))
            .tryConsume(nowMillis);
    }

    /** Sprzątanie po rozłączeniu — bez tego mapy rosną z liczbą sesji od startu procesu. */
    public void forget(String sessionId) {
        operations.remove(sessionId);
        presence.remove(sessionId);
    }

    public int trackedSessions() {
        return operations.size() + presence.size();
    }
}
