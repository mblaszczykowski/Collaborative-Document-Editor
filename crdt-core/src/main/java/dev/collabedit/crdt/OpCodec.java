package dev.collabedit.crdt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;

/**
 * Serializacja operacji do JSON — format przewodowy dla STOMP (ADR-0002) i format zapisu
 * w kolumnie {@code payload} logu operacji (ADR-0003).
 *
 * <p>Kodek jest pisany ręcznie, a nie generowany adnotacjami Jacksona, z trzech powodów:
 * <ol>
 *   <li><b>Rozmiar.</b> ADR-0002 zakłada zmierzenie w CP8, jaki udział w ruchu ma narzut
 *       protokołu. Nazwy pól są więc jednoliterowe — przy operacji niosącej jeden znak
 *       {@code "originLeft"} kosztuje więcej niż sama treść.</li>
 *   <li><b>Stabilność.</b> Ten format musi odczytać również implementacja w TypeScripcie.
 *       Format wynikający z domyślnego mapowania rekordów zmieniałby się przy każdej zmianie
 *       nazwy pola w Javie — czyli po cichu rozspójniałby obie strony.</li>
 *   <li><b>Jawność.</b> Format przewodowy jest decyzją, a nie efektem ubocznym refaktoryzacji.</li>
 * </ol>
 *
 * <h2>Format</h2>
 * <pre>
 * wstawienie: {"t":"i","r":1,"l":7,"or":2,"ol":3,"s":"abc"}
 *             r/l  = identyfikator operacji (replika / lamport)
 *             or/ol = origin (pomijane, gdy wstawienie jest na początku dokumentu)
 *             s     = tekst
 * usunięcie:  {"t":"d","r":1,"l":9,"g":[[2,3,5]]}
 *             g = zakresy [replika, początkowy lamport, długość]
 * </pre>
 */
public final class OpCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private OpCodec() {
    }

    public static ObjectNode toJson(Op op) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("r", op.id().replicaId());
        node.put("l", op.id().lamport());
        switch (op) {
            case Op.Insert insert -> {
                node.put("t", "i");
                if (insert.originLeft() != null) {
                    node.put("or", insert.originLeft().replicaId());
                    node.put("ol", insert.originLeft().lamport());
                }
                node.put("s", insert.text());
            }
            case Op.Delete delete -> {
                node.put("t", "d");
                ArrayNode ranges = node.putArray("g");
                for (Op.Range range : delete.targets()) {
                    ArrayNode entry = ranges.addArray();
                    entry.add(range.start().replicaId());
                    entry.add(range.start().lamport());
                    entry.add(range.length());
                }
            }
        }
        return node;
    }

    public static Op fromJson(JsonNode node) {
        Id id = new Id(node.get("r").asInt(), node.get("l").asLong());
        String type = node.get("t").asText();
        return switch (type) {
            case "i" -> {
                Id originLeft = node.has("or")
                    ? new Id(node.get("or").asInt(), node.get("ol").asLong())
                    : null;
                yield new Op.Insert(id, originLeft, node.get("s").asText());
            }
            case "d" -> {
                List<Op.Range> targets = new ArrayList<>();
                for (JsonNode entry : node.get("g")) {
                    targets.add(new Op.Range(
                        new Id(entry.get(0).asInt(), entry.get(1).asLong()),
                        entry.get(2).asInt()));
                }
                yield new Op.Delete(id, targets);
            }
            default -> throw new IllegalArgumentException("nieznany typ operacji: " + type);
        };
    }

    public static String encode(Op op) {
        return toJson(op).toString();
    }

    public static Op decode(String json) {
        try {
            return fromJson(MAPPER.readTree(json));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("nie da się zdekodować operacji: " + json, e);
        }
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }
}
