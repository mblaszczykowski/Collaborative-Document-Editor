package dev.collabedit.app.ws;

import dev.collabedit.app.domain.DocumentService;
import dev.collabedit.crdt.Op;
import dev.collabedit.crdt.OpCodec;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * Spina warstwę domenową z transportem.
 *
 * <p>Istnieje po to, żeby {@link DocumentService} — gdzie zapisana jest kolejność kroków
 * z ADR-0004 — nie zależał od {@code SimpMessagingTemplate}. Dzięki temu ścieżkę zapisu
 * da się przetestować bez podnoszenia całego stosu WebSocketa, a zmiana transportu
 * (gdyby ADR-0002 kiedyś został zrewidowany) dotyka jednego pliku zamiast serca logiki.
 */
@Component
public class BroadcastWiring {

    private final DocumentService documents;
    private final SimpMessagingTemplate messaging;

    public BroadcastWiring(DocumentService documents, SimpMessagingTemplate messaging) {
        this.documents = documents;
        this.messaging = messaging;
    }

    /**
     * Budżet bajtów na jedną ramkę rozgłoszenia.
     *
     * <h2>Dlaczego bajty, a nie liczba operacji</h2>
     * Liczenie operacji jest niepoprawne <b>w zasadzie</b>, a nie tylko przy złej wartości:
     * operacja niesie tekst o dowolnej długości, więc wklejenie dużego fragmentu to jedna
     * operacja ważąca dziesiątki kilobajtów. Limit „300 operacji" znaczyłby wtedy ramkę
     * kilkumegabajtową. Jedyną wielkością, którą naprawdę ograniczamy, jest rozmiar ramki
     * — więc ograniczamy ją wprost.
     *
     * <h2>Skąd 32 KiB</h2>
     * ADR-0002 ustala 64 KiB jako limit pojedynczej wiadomości. Połowa tej wartości zostawia
     * zapas na koperty STOMP i JSON wokół samych operacji, których dokładnego rozmiaru
     * nie liczymy. Pojedyncza operacja większa niż budżet idzie w ramce sama — nic lepszego
     * nie da się zrobić, a limit wejściowy serwera i tak nie wpuściłby większej.
     *
     * <h2>Dlaczego dzielenie w ogóle tu jest</h2>
     * Przy rozgłaszaniu jedna-operacja-jedna-ramka (do CP10) limit był nieosiągalny i dlatego
     * dzielenia nie było. Sklejanie rozgłoszeń zmieniło to założenie — i przy 50 klientach
     * piszących naraz wsad potrafi urosnąć dowolnie. Jest to ta sama klasa błędu, którą
     * CP8 wykrył w odpowiedzi na resynchronizację (ADR-0002): ramka po prostu nie dociera,
     * bez śladu po stronie serwera.
     */
    private static final int FRAME_BUDGET_BYTES = 32 * 1024;

    /** Zapas na kopertę pojedynczego wpisu: {@code {"op":...,"serverSeq":null},}. */
    private static final int ENTRY_OVERHEAD_BYTES = 32;

    @PostConstruct
    void wire() {
        // Krok 2 z ADR-0004: rozgłoszenie po scaleniu, PRZED zapisem.
        // To ono odpowiada za to, co użytkownik widzi.
        documents.onFastPath((documentId, ops) -> {
            List<Messages.OpBroadcast> frame = new ArrayList<>();
            int bytes = 0;
            for (Op op : ops) {
                var json = OpCodec.toJson(op);
                int size = json.toString().getBytes(StandardCharsets.UTF_8).length
                    + ENTRY_OVERHEAD_BYTES;
                if (!frame.isEmpty() && bytes + size > FRAME_BUDGET_BYTES) {
                    messaging.convertAndSend("/topic/doc/" + documentId + "/ops", frame);
                    frame = new ArrayList<>();
                    bytes = 0;
                }
                frame.add(new Messages.OpBroadcast(json, null));
                bytes += size;
            }
            if (!frame.isEmpty()) {
                messaging.convertAndSend("/topic/doc/" + documentId + "/ops", frame);
            }
        });

        // Krok 4: potwierdzenie PO zapisie. Dopiero ono zwalnia operację z bufora offline
        // klienta i przesuwa jego kursor resynchronizacji.
        //
        // Bez dzielenia: potwierdzenie ma STAŁY rozmiar (trzy liczby), a wsad zapisu jest
        // ograniczony przez op-batch-max-size. Sto potwierdzeń to ~4 KB, czyli rząd wielkości
        // poniżej limitu — i w odróżnieniu od operacji nie ma tu wielkości sterowanej treścią.
        documents.onAcknowledged((documentId, stored) -> {
            List<Messages.OpAck> payload = stored.stream()
                .map(entry -> new Messages.OpAck(
                    entry.op().id().replicaId(),
                    entry.op().id().lamport(),
                    entry.serverSeq()))
                .toList();
            messaging.convertAndSend("/topic/doc/" + documentId + "/acks", payload);
        });
    }
}
