package dev.collabedit.crdt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;

/**
 * Serialising operations to JSON — the wire format for STOMP (ADR-0002) and the storage format for
 * the {@code payload} column of the operation log (ADR-0003).
 *
 * <p>The codec is written by hand rather than generated from Jackson annotations, for three
 * reasons:
 * <ol>
 *   <li><b>Size.</b> ADR-0002 plans to measure in CP8 how much of the traffic is protocol
 *       overhead. The field names are therefore single letters — on an operation carrying one
 *       character, {@code "originLeft"} costs more than the content itself.</li>
 *   <li><b>Stability.</b> This format has to be read by the TypeScript implementation as well. A
 *       format derived from default record mapping would change with every field rename in Java —
 *       that is, it would silently desynchronise the two sides.</li>
 *   <li><b>Explicitness.</b> The wire format is a decision, not a side effect of refactoring.</li>
 * </ol>
 *
 * <h2>Format</h2>
 * <pre>
 * insert:   {"t":"i","r":1,"l":7,"or":2,"ol":3,"s":"abc"}
 *           r/l   = operation identifier (replica / lamport)
 *           or/ol = origin (omitted when the insertion is at the start of the document)
 *           s     = text
 * delete:   {"t":"d","r":1,"l":9,"g":[[2,3,5]]}
 *           g = ranges [replica, starting lamport, length]
 * mark+:    {"t":"m","r":1,"l":11,"k":"bold","v":null,"a":[2,3,0],"b":[2,7,1]}
 * mark−:    {"t":"x","r":1,"l":13,"k":"bold","a":[2,3,0],"b":[2,7,1]}
 *           k    = mark type
 *           v    = value (a link's address); omitted when unused
 *           a/b  = anchors [replica, lamport, side], where side 0=before, 1=after
 * </pre>
 *
 * <h2>Extending the format in CP13</h2>
 * The discriminators {@code m} and {@code x} were added without changing the existing ones. The
 * format is <b>extended, not versioned</b>: an insert operation written before CP13 and after it
 * looks identical, so an operation log from before rich text reads with no migration at all. The
 * {@code operations.op_type} column is a {@code CHAR(1)} and the body is {@code JSONB} — the V1
 * schema carries this unchanged, which is down to the ADR-0003 decision rather than to luck.
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
            case Op.AddMark mark -> {
                node.put("t", "m");
                node.put("k", mark.type());
                if (mark.value() != null) {
                    node.put("v", mark.value());
                }
                writeAnchor(node, "a", mark.start());
                writeAnchor(node, "b", mark.end());
            }
            case Op.RemoveMark mark -> {
                node.put("t", "x");
                node.put("k", mark.type());
                writeAnchor(node, "a", mark.start());
                writeAnchor(node, "b", mark.end());
            }
        }
        return node;
    }

    /** An anchor as {@code [replica, lamport, side]} — side 0 = before, 1 = after. */
    private static void writeAnchor(ObjectNode node, String field, Anchor anchor) {
        ArrayNode array = node.putArray(field);
        array.add(anchor.charId().replicaId());
        array.add(anchor.charId().lamport());
        array.add(anchor.side() == Anchor.Side.BEFORE ? 0 : 1);
    }

    private static Anchor readAnchor(JsonNode node, String field) {
        JsonNode array = node.get(field);
        return new Anchor(
            new Id(array.get(0).asInt(), array.get(1).asLong()),
            array.get(2).asInt() == 0 ? Anchor.Side.BEFORE : Anchor.Side.AFTER);
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
            case "m" -> new Op.AddMark(
                id,
                node.get("k").asText(),
                node.has("v") ? node.get("v").asText() : null,
                readAnchor(node, "a"),
                readAnchor(node, "b"));
            case "x" -> new Op.RemoveMark(
                id,
                node.get("k").asText(),
                readAnchor(node, "a"),
                readAnchor(node, "b"));
            default -> throw new IllegalArgumentException("unknown operation type: " + type);
        };
    }

    public static String encode(Op op) {
        return toJson(op).toString();
    }

    public static Op decode(String json) {
        try {
            return fromJson(MAPPER.readTree(json));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("cannot decode operation: " + json, e);
        }
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }
}
