package dev.collabedit.crdt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;

/**
 * Serialising operations to JSON — the wire format for STOMP and the storage format for
 * the {@code payload} column of the operation log.
 *
 * <p>The codec is written by hand rather than generated from Jackson annotations, for three
 * reasons:
 * <ol>
 *   <li><b>Size.</b> the transport decision plans to measure in CP8 how much of the traffic is protocol
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
 * mark+: {"t":"m","r":1,"l":11,"k":"bold","a":[2,3,0],"b":[2,7,1]}
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
 * schema carries this unchanged, which is down to the the operation-log decision rather than to luck.
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
        JsonNode array = required(node, field);
        if (!array.isArray() || array.size() != 3) {
            throw new IllegalArgumentException(
                "anchor '" + field + "' is not a three-element array: " + node);
        }
        return new Anchor(
            new Id(array.get(0).asInt(), requireLamport(array.get(1).asLong())),
            array.get(2).asInt() == 0 ? Anchor.Side.BEFORE : Anchor.Side.AFTER);
    }

    /**
     * The largest Lamport value this codec will accept from the wire.
     *
     * <h2>What an unbounded one did</h2>
     * {@code RgaDocument.apply} bumps the clock past an incoming operation's timestamp BEFORE it
     * checks whether the operation is a duplicate or even applicable — correctly, because the
     * Lamport rule is about receipt rather than application. So an operation carrying a lamport
     * near {@link Long#MAX_VALUE} and an origin that does not exist was <b>buffered</b>, which
     * {@code DocumentReplica.merge} treats as news: written to the operation log, broadcast to
     * every client, and published to every other instance. Meanwhile it had saturated the
     * receiving replica's clock, and the next local insert overflowed it to a negative value, at
     * which point {@link Id}'s own constructor refused every subsequent server-generated
     * operation — history restore, accepting a suggestion, creating a recovered draft — for the
     * lifetime of that document. Reproduced end to end; the poisoned clock is persisted in the
     * snapshot and the crafted operation is in the log, so it survived both eviction and restart.
     *
     * <p>2^50 is chosen to be unreachable and obviously so: a document edited at a thousand
     * operations a second, continuously, would need about thirty-five thousand years to reach it.
     * It also leaves the clock room to keep counting — {@code clock += op.span} can add at most
     * one frame's worth of characters — so the arithmetic downstream cannot overflow either.
     *
     * <p>Refused at the EDGE, in the codec, rather than in the merge: by the time an operation
     * reaches {@code apply} it has already been decoded, and every caller of this method
     * (the STOMP handler, the cluster listener, log replay) already handles the exception this
     * throws. The alternative — a check inside the algorithm — would put a transport concern in
     * the one class the CRDT decision keeps free of them.
     */
    static final long MAX_LAMPORT = 1L << 50;

    private static long requireLamport(long lamport) {
        if (lamport < 0 || lamport > MAX_LAMPORT) {
            throw new IllegalArgumentException(
                "lamport outside the accepted range [0, " + MAX_LAMPORT + "]: " + lamport);
        }
        return lamport;
    }

    /** An identifier from two named fields, with the timestamp bounded. */
    private static Id readId(JsonNode node, String replicaField, String lamportField) {
        return new Id(
            required(node, replicaField).asInt(),
            requireLamport(required(node, lamportField).asLong()));
    }

    /**
     * Reads an operation, refusing a payload that is valid JSON but not a valid operation.
     *
     * <h2>hasNonNull, not has</h2>
     * {@code JsonNode.has} is true for a field present with an explicit {@code null}, and
     * {@code NullNode.asText} returns the STRING {@code "null"}. So {@code {"v":null}} decoded to
     * a mark whose value was the four characters {@code null} — which for a link is a bogus address
     * — and {@code {"or":null,"ol":null}} fabricated an origin of {@code 0@0}, which is worse: the
     * insert then integrates in the wrong place or waits for a character that does not exist.
     *
     * <p>The javadoc above published {@code "v":null} as the wire format while the TypeScript twin
     * decoded it to a real null, so the two implementations disagreed on a payload this class
     * sanctioned. The example is corrected and both fields are now read with {@code hasNonNull}.
     *
     * <h2>Missing fields</h2>
     * A truncated operation used to arrive here as a {@link NullPointerException} —
     * {@code node.get("g")} returning null and being iterated — and {@link #decode} caught only
     * {@code JsonProcessingException}, so it escaped into an inbound message handler. The
     * {@code required} helper turns it into the same {@link IllegalArgumentException} an unknown
     * operation type produces, which is what the callers already handle.
     */
    public static Op fromJson(JsonNode node) {
        Id id = readId(node, "r", "l");
        String type = required(node, "t").asText();
        return switch (type) {
            case "i" -> {
                Id originLeft = node.hasNonNull("or") ? readId(node, "or", "ol") : null;
                yield new Op.Insert(id, originLeft, required(node, "s").asText());
            }
            case "d" -> {
                List<Op.Range> targets = new ArrayList<>();
                for (JsonNode entry : required(node, "g")) {
                    // Shape-checked like an anchor, and for the same reason. Without this,
                    // entry.get(0) on anything that is not a three-element array returns null and
                    // the NEXT call dereferences it — so a truncated range came out of this method
                    // as a raw NullPointerException, while the class javadoc below states that
                    // `required` turned exactly this into an IllegalArgumentException "which is
                    // what the callers already handle". Measured on the four malformed shapes:
                    // [[2,3]], [7] and [{}] all threw NPE, and [[2,3,5,7]] was accepted as a
                    // perfectly ordinary range, silently discarding whatever the sender meant by
                    // the fourth element.
                    if (!entry.isArray() || entry.size() != 3) {
                        throw new IllegalArgumentException(
                            "a deletion range must be [replicaId, lamport, length]: " + entry);
                    }
                    targets.add(new Op.Range(
                        new Id(entry.get(0).asInt(), requireLamport(entry.get(1).asLong())),
                        entry.get(2).asInt()));
                }
                yield new Op.Delete(id, targets);
            }
            case "m" -> new Op.AddMark(
                id,
                required(node, "k").asText(),
                node.hasNonNull("v") ? node.get("v").asText() : null,
                readAnchor(node, "a"),
                readAnchor(node, "b"));
            case "x" -> new Op.RemoveMark(
                id,
                required(node, "k").asText(),
                readAnchor(node, "a"),
                readAnchor(node, "b"));
            default -> throw new IllegalArgumentException("unknown operation type: " + type);
        };
    }

    public static String encode(Op op) {
        return toJson(op).toString();
    }

    /** The field, or an {@link IllegalArgumentException} naming what was missing. */
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
