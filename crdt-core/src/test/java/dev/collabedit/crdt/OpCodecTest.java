package dev.collabedit.crdt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The operation wire format — the half of the two-implementation agreement the vectors do not
 * reach.
 *
 * <p>The conformance corpus replays operations both sides consider well-formed. What it cannot
 * cover is what happens to a payload that is valid JSON and not a valid operation, or one that
 * uses a shape the format documents but nothing in the corpus produces. Both are reachable: this
 * decoder is what {@code DocumentAccessInterceptor}, {@code ClusterBus} and {@code OperationStore}
 * hand inbound bytes to.
 */
class OpCodecTest {

    @Test
    @DisplayName("an explicit JSON null is a null, not the string \"null\"")
    void explicitNullIsNotTheWordNull() {
        // `has` is true for a field present with an explicit null, and NullNode.asText() returns
        // "null". So this decoded to a mark whose value was the four characters n-u-l-l — for a
        // link, a bogus address — while the TypeScript twin decoded the same payload to a real
        // null. The format's own javadoc published this shape as an example.
        Op.AddMark mark = (Op.AddMark) OpCodec.decode(
            "{\"t\":\"m\",\"r\":1,\"l\":11,\"k\":\"bold\",\"v\":null,\"a\":[1,1,0],\"b\":[1,3,1]}");

        assertThat(mark.value()).isNull();
    }

    @Test
    @DisplayName("an explicitly null origin is no origin, not the identifier 0@0")
    void explicitNullOriginIsNoOrigin() {
        // The worse half of the same bug: a fabricated origin makes the insert integrate in the
        // wrong place, or wait for ever for a character that was never created.
        Op.Insert insert = (Op.Insert) OpCodec.decode(
            "{\"t\":\"i\",\"r\":1,\"l\":5,\"s\":\"a\",\"or\":null,\"ol\":null}");

        assertThat(insert.originLeft()).isNull();
    }

    @Test
    @DisplayName("a truncated operation is refused, not a NullPointerException")
    void truncatedOperationsAreRefused() {
        // Valid JSON, invalid operation. These used to surface as an NPE from inside the decoder,
        // which `decode` did not catch — so an unparseable frame reached an inbound message
        // handler as a null dereference rather than as a rejected message.
        for (String malformed : new String[] {
            "{\"t\":\"d\",\"r\":1,\"l\":0}",
            "{\"t\":\"i\",\"r\":1,\"l\":0}",
            "{\"t\":\"m\",\"r\":1,\"l\":0,\"k\":\"bold\"}",
            "{\"t\":\"m\",\"r\":1,\"l\":0,\"k\":\"bold\",\"a\":[1,1],\"b\":[1,3,1]}",
            "{\"r\":1,\"l\":0}",
        }) {
            assertThatThrownBy(() -> OpCodec.decode(malformed))
                .as("payload %s", malformed)
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("the same operation buffered twice is buffered once")
    void aBufferedOperationIsNotAccumulated() {
        RgaDocument writer = new RgaDocument(1);
        Op cause = writer.insert(0, "a");
        Op orphan = writer.insert(1, "b");

        RgaDocument reader = new RgaDocument(2);
        assertThat(reader.apply(orphan)).isEqualTo(RgaDocument.ApplyResult.BUFFERED);

        // isAlreadyKnown detects an insert only once it is IN THE STRUCTURE, so an operation
        // waiting in the buffer was never recognised as a duplicate. Every redelivery path feeds
        // this — the cluster re-broadcast, the client's retransmission, log replay.
        //
        // Two separate costs came out of that, and the buffer being a map only fixes the second.
        // DocumentReplica.merge queues anything news() for a log write and a cluster broadcast,
        // and BUFFERED is news — so the ANSWER matters as much as the storage, and a redelivery
        // of something already waiting has to come back as DUPLICATE.
        for (int i = 0; i < 3; i++) {
            assertThat(reader.apply(orphan))
                .as("a redelivery of a buffered operation is news again — it will be written to "
                    + "the log and broadcast to the cluster once more, per copy")
                .isEqualTo(RgaDocument.ApplyResult.DUPLICATE);
        }

        assertThat(reader.pendingCount())
            .as("the buffer accumulated one entry per redelivery")
            .isEqualTo(1);

        // ...and the operation is still applied when its cause finally arrives. A dedup that
        // dropped it would be worse than the amplification it replaced.
        reader.apply(cause);
        assertThat(reader.text()).isEqualTo("ab");
        assertThat(reader.pendingCount()).isZero();
    }

    @Test
    @DisplayName("an inverted formatting range is refused rather than stored for ever")
    void invertedMarkRangesAreRefused() {
        RgaDocument document = new RgaDocument(1);
        document.insert(0, "abcdef");

        // The two anchors validate independently, so nothing checked that one came before the
        // other. addMark(4, 2) was accepted: it consumed a Lamport tick, was stored permanently,
        // was broadcast and logged, pinned two blocks from GC — and resolved to nothing.
        assertThatThrownBy(() -> document.addMark(4, 2, MarkTypes.BOLD, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> document.removeMark(3, 3, MarkTypes.BOLD))
            .isInstanceOf(IllegalArgumentException.class);

        assertThat(document.markCount()).isZero();
    }

    /**
     * <b>A crafted timestamp cannot poison the clock.</b>
     *
     * <p>The most expensive defect this codec has had. {@code RgaDocument.apply} advances the
     * Lamport clock past an incoming operation BEFORE checking whether it is a duplicate or even
     * applicable — correctly, because the rule is about receipt. So an operation carrying a
     * timestamp near {@link Long#MAX_VALUE} and an origin that does not exist was <b>buffered</b>,
     * which the layer above treats as news: written to the operation log, broadcast to every
     * client, published to every instance. It had already saturated the clock, and the next local
     * insert overflowed it negative, after which {@link Id}'s constructor refused every
     * server-generated operation for that document — restoring a version, accepting a suggestion,
     * creating a recovered draft — permanently, through snapshots and restarts.
     */
    @Test
    @DisplayName("an implausible Lamport timestamp is refused at the edge")
    void implausibleTimestampsAreRefused() {
        for (String payload : new String[] {
            "{\"t\":\"i\",\"r\":5,\"l\":9223372036854775806,\"or\":5,\"ol\":1,\"s\":\"x\"}",
            "{\"t\":\"i\",\"r\":5,\"l\":1,\"or\":5,\"ol\":9223372036854775806,\"s\":\"x\"}",
            "{\"t\":\"d\",\"r\":1,\"l\":9,\"g\":[[2,9223372036854775806,1]]}",
            "{\"t\":\"m\",\"r\":1,\"l\":9,\"k\":\"bold\",\"a\":[1,9223372036854775806,0],\"b\":[1,3,1]}",
        }) {
            assertThatThrownBy(() -> OpCodec.decode(payload))
                .as("accepted: %s", payload)
                .isInstanceOf(IllegalArgumentException.class);
        }

        // The bound is generous rather than tight — a document edited at a thousand operations a
        // second would need about thirty-five thousand years to reach it — so nothing real is
        // refused by it.
        assertThat(OpCodec.decode(
            "{\"t\":\"i\",\"r\":5,\"l\":1099511627775,\"s\":\"x\"}").id().lamport())
            .isEqualTo(1_099_511_627_775L);
    }

    /**
     * <b>A malformed deletion range is refused the way a malformed anchor already was.</b>
     *
     * <p>{@code readAnchor} checked that an anchor is a three-element array; the deletion branch
     * did not, so {@code entry.get(0)} returned null on anything else and the next call
     * dereferenced it. The class javadoc states that the {@code required} helper turned exactly
     * this into an {@link IllegalArgumentException} "which is what the callers already handle" —
     * it did so for every field except this one. A four-element range was worse than a throw: it
     * was accepted, silently discarding whatever the sender meant by the extra element.
     */
    @Test
    @DisplayName("a deletion range that is not [replica, lamport, length] is refused")
    void malformedDeletionRangesAreRefused() {
        for (String payload : new String[] {
            "{\"t\":\"d\",\"r\":1,\"l\":9,\"g\":[[2,3]]}",
            "{\"t\":\"d\",\"r\":1,\"l\":9,\"g\":[7]}",
            "{\"t\":\"d\",\"r\":1,\"l\":9,\"g\":[{}]}",
            "{\"t\":\"d\",\"r\":1,\"l\":9,\"g\":[[2,3,5,7]]}",
        }) {
            assertThatThrownBy(() -> OpCodec.decode(payload))
                .as("accepted or threw the wrong type: %s", payload)
                .isInstanceOf(IllegalArgumentException.class);
        }

        assertThat(OpCodec.decode("{\"t\":\"d\",\"r\":1,\"l\":9,\"g\":[[2,3,5]]}"))
            .isInstanceOf(Op.Delete.class);
    }
}
