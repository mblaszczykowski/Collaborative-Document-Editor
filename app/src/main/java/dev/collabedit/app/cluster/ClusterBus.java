package dev.collabedit.app.cluster;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.collabedit.crdt.OpCodec;
import dev.collabedit.app.persist.StoredOperation;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Rozsyłanie operacji między instancjami backendu przez Redis pub/sub.
 *
 * <p><b>To jest ścieżka szybka i ZAWODNA</b> — świadomie (ADR-0004). Redis pub/sub daje
 * dostarczenie co najwyżej raz: restart Redisa, przepełnienie bufora klienta albo chwilowa
 * partycja gubią wiadomość bez błędu i bez śladu. Konfiguracja w {@code docker-compose.yml}
 * celowo wyłącza persystencję Redisa i ustawia {@code maxmemory-policy noeviction}, żeby
 * ten scenariusz dało się wywołać w teście chaosu (CP8), zamiast udawać, że nie istnieje.
 *
 * <p>Bezpieczeństwo nie bierze się stąd, że wiadomości nie giną, tylko stąd, że
 * <b>zgubienie wiadomości nie jest problemem poprawności</b>:
 * <ul>
 *   <li>autorytatywną kopią jest log operacji w Postgresie, zapisany <i>przed</i> publikacją,</li>
 *   <li>replika, która wykryje lukę w {@code server_seq}, dociąga brakujący zakres z bazy,</li>
 *   <li>okresowy przegląd łapie przypadek, którego wykrywanie luk złapać nie może:
 *       utratę <i>ostatniej</i> operacji, po której nie przyszła już żadna następna.</li>
 * </ul>
 *
 * <p>Naprawa nie musi być ani szybka, ani uporządkowana — scalanie CRDT jest przemienne
 * i idempotentne, więc operacja dociągnięta 30 sekund po fakcie daje ten sam wynik, co
 * dostarczona natychmiast. To jest dokładnie ta własność, dla której wybrano CRDT
 * w ADR-0001, i tutaj przestaje być teoretyczna.
 */
@Component
public class ClusterBus implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(ClusterBus.class);

    /** Wiadomości od samego siebie trzeba odsiać — pub/sub nie robi tego za nas. */
    private final String instanceId = UUID.randomUUID().toString();

    private final StringRedisTemplate redis;
    private volatile Consumer<ClusterMessage> handler = message -> { };

    /**
     * Czy ta instancja w ogóle interesuje się danym dokumentem.
     *
     * <p>Sprawdzane <b>przed</b> odczytaniem treści wiadomości. Subskrypcja jest wzorcowa
     * (jeden wzorzec na wszystkie dokumenty — patrz {@code RedisConfig}), więc każda instancja
     * dostaje ruch WSZYSTKICH dokumentów w klastrze. Przy jednym dokumencie nie miało to
     * znaczenia; przy tysiącu oznacza parsowanie JSON-a całego ruchu klastra na każdej
     * instancji, żeby w większości przypadków wyrzucić wynik.
     *
     * <p>Identyfikator dokumentu jest już w NAZWIE KANAŁU, więc odsiew nic nie kosztuje —
     * pod warunkiem, że zrobi się go przed parsowaniem. To jest cała zmiana.
     */
    private volatile java.util.function.Predicate<UUID> interested = id -> true;

    public ClusterBus(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public String instanceId() {
        return instanceId;
    }

    public void onMessage(Consumer<ClusterMessage> handler) {
        this.handler = handler;
    }

    /** Ustawia filtr dokumentów, którymi ta instancja się interesuje. Patrz {@link #interested}. */
    public void interestedIn(java.util.function.Predicate<UUID> predicate) {
        this.interested = predicate;
    }

    /**
     * Wyciąga identyfikator dokumentu z nazwy kanału {@code collabedit:doc:{uuid}:ops}.
     *
     * @return {@code null}, gdy nazwa nie pasuje do wzorca — wtedy wiadomość pomijamy,
     *         bo nie wiadomo, czego dotyczy
     */
    static UUID documentIdOf(String channel) {
        int start = "collabedit:doc:".length();
        int end = channel.lastIndexOf(":ops");
        if (!channel.startsWith("collabedit:doc:") || end <= start) {
            return null;
        }
        try {
            return UUID.fromString(channel.substring(start, end));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Wiadomość klastrowa: operacje jednego dokumentu wraz z nadanymi numerami. */
    public record ClusterMessage(UUID documentId, List<StoredOperation> operations) {
    }

    public static String channel(UUID documentId) {
        return "collabedit:doc:" + documentId + ":ops";
    }

    public static final String CHANNEL_PATTERN = "collabedit:doc:*:ops";

    /**
     * Publikuje operacje, które właśnie zostały <b>zapisane</b>.
     *
     * <p>Kolejność „zapis przed publikacją" jest istotna: instancja, która odbierze operację
     * z Redisa, natychmiast zapisuje jej {@code server_seq} jako osiągnięty. Gdyby publikacja
     * wyprzedzała zapis, a zapis następnie się nie powiódł, inne instancje uznałyby za
     * osiągnięty numer, którego w bazie nie ma — i przestałyby go kiedykolwiek dociągnąć.
     */
    public void publish(UUID documentId, List<StoredOperation> operations) {
        if (operations.isEmpty()) {
            return;
        }
        ObjectNode payload = OpCodec.mapper().createObjectNode();
        payload.put("from", instanceId);
        payload.put("doc", documentId.toString());
        ArrayNode array = payload.putArray("ops");
        for (StoredOperation stored : operations) {
            ObjectNode entry = OpCodec.mapper().createObjectNode();
            entry.set("op", OpCodec.toJson(stored.op()));
            entry.put("seq", stored.serverSeq());
            entry.put("user", stored.userId());
            array.add(entry);
        }
        try {
            redis.convertAndSend(channel(documentId), payload.toString());
        } catch (RuntimeException e) {
            // Świadomie połykamy błąd: publikacja jest ścieżką najlepszego wysiłku.
            // Niedostępny Redis oznacza wolniejszą propagację między instancjami, a nie
            // utratę danych — te są już w logu operacji. Naprawi to przegląd anti-entropy.
            log.warn("Publikacja do Redisa nieudana dla dokumentu {} — naprawi to anti-entropy: {}",
                documentId, e.toString());
        }
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        // Odsiew PRZED parsowaniem treści — identyfikator dokumentu jest w nazwie kanału,
        // więc nie ma powodu czytać ładunku wiadomości, która i tak zostanie odrzucona.
        // Przy tysiącu dokumentów w klastrze to jest różnica między parsowaniem całego
        // ruchu na każdej instancji a parsowaniem własnego.
        UUID channelDocument = documentIdOf(new String(message.getChannel(), StandardCharsets.UTF_8));
        if (channelDocument == null || !interested.test(channelDocument)) {
            return;
        }
        try {
            JsonNode payload = OpCodec.mapper().readTree(message.getBody());
            if (instanceId.equals(payload.path("from").asText())) {
                return;
            }
            UUID documentId = UUID.fromString(payload.get("doc").asText());
            List<StoredOperation> operations = new ArrayList<>();
            for (JsonNode entry : payload.get("ops")) {
                operations.add(new StoredOperation(
                    OpCodec.fromJson(entry.get("op")),
                    entry.get("seq").asLong(),
                    entry.path("user").asText(null),
                    java.time.Instant.now()));
            }
            handler.accept(new ClusterMessage(documentId, operations));
        } catch (Exception e) {
            log.warn("Nieczytelna wiadomość klastrowa — pomijam: {}", e.toString());
        }
    }
}
