package dev.collabedit.crdt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;

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

    private static void writeAnchor(ObjectNode node, String field, Anchor anchor) {
        ArrayNode array = node.putArray(field);
        array.add(anchor.charId().replicaId());
        array.add(anchor.charId().lamport());
        array.add(anchor.side() == Anchor.Side.BEFORE ? 0 : 1);
    }

    private static Anchor readAnchor(JsonNode node, String field) {
        JsonNode array = required(node, field);
        if (!array.isArray() || array.size() != 3) {
            throw new IllegalArgumentException(
                "anchor '" + field + "' is not a three-element array: " + node);
        }
        JsonNode side = array.get(2);
        if (!side.isIntegralNumber() || !side.canConvertToInt()
            || (side.intValue() != 0 && side.intValue() != 1)) {
            throw new IllegalArgumentException(
                "an anchor side must be 0 (before) or 1 (after): " + array);
        }
        return new Anchor(
            new Id(requireReplicaId(array.get(0)), requireLamport(array.get(1))),
            side.intValue() == 0 ? Anchor.Side.BEFORE : Anchor.Side.AFTER);
    }

    static final long MAX_LAMPORT = 1L << 50;

    private static long requireLamport(long lamport) {
        if (lamport < 0 || lamport > MAX_LAMPORT) {
            throw new IllegalArgumentException(
                "lamport outside the accepted range [0, " + MAX_LAMPORT + "]: " + lamport);
        }
        return lamport;
    }

    private static long requireIntegral(JsonNode value, String what) {
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new IllegalArgumentException(what + " is not a whole number: " + value);
        }
        return value.longValue();
    }

    private static int requireReplicaId(JsonNode value) {
        long replicaId = requireIntegral(value, "replica identifier");
        if (!value.canConvertToInt()) {
            throw new IllegalArgumentException("replica identifier is not an int: " + replicaId);
        }
        return (int) replicaId;
    }

    private static long requireLamport(JsonNode value) {
        return requireLamport(requireIntegral(value, "lamport"));
    }

    private static String requireString(JsonNode node, String field) {
        JsonNode value = required(node, field);
        if (!value.isTextual()) {
            throw new IllegalArgumentException(
                "the field '" + field + "' must be a string: " + node);
        }
        return value.textValue();
    }

    private static String requireInsertText(JsonNode node) {
        String text = requireString(node, "s");
        if (text.isEmpty()) {
            throw new IllegalArgumentException("an insert must carry text: " + node);
        }
        return text;
    }

    private static String requireMarkType(JsonNode node) {
        String type = requireString(node, "k");
        if (type.isBlank()) {
            throw new IllegalArgumentException("a mark must have a type: " + node);
        }
        return type;
    }

    private static Id readId(JsonNode node, String replicaField, String lamportField) {
        return new Id(
            requireReplicaId(required(node, replicaField)),
            requireLamport(required(node, lamportField)));
    }

    public static Op fromJson(JsonNode node) {
        Id id = readId(node, "r", "l");
        String type = required(node, "t").asText();
        return switch (type) {
            case "i" -> {
                Id originLeft = node.hasNonNull("or") ? readId(node, "or", "ol") : null;
                yield new Op.Insert(id, originLeft, requireInsertText(node));
            }
            case "d" -> {
                List<Op.Range> targets = new ArrayList<>();
                for (JsonNode entry : required(node, "g")) {
                    if (!entry.isArray() || entry.size() != 3) {
                        throw new IllegalArgumentException(
                            "a deletion range must be [replicaId, lamport, length]: " + entry);
                    }
                    long length = requireIntegral(entry.get(2), "a deletion range length");
                    if (length <= 0 || length > Integer.MAX_VALUE) {
                        throw new IllegalArgumentException(
                            "a deletion range must have a positive length: " + entry);
                    }
                    targets.add(new Op.Range(
                        new Id(requireReplicaId(entry.get(0)), requireLamport(entry.get(1))),
                        (int) length));
                }
                yield new Op.Delete(id, targets);
            }
            case "m" -> new Op.AddMark(
                id,
                requireMarkType(node),
                node.hasNonNull("v") ? requireString(node, "v") : null,
                readAnchor(node, "a"),
                readAnchor(node, "b"));
            case "x" -> new Op.RemoveMark(
                id,
                requireMarkType(node),
                readAnchor(node, "a"),
                readAnchor(node, "b"));
            default -> throw new IllegalArgumentException("unknown operation type: " + type);
        };
    }

    public static String encode(Op op) {
        return toJson(op).toString();
    }

    private static JsonNode required(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            throw new IllegalArgumentException(
                "operation is missing the field '" + field + "': " + node);
        }
        return value;
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
