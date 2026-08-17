package dev.collabedit.crdt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OpCodecTest {
    @Test
    @DisplayName("an explicit JSON null is a null, not the string \"null\"")
    void explicitNullIsNotTheWordNull() {
        Op.AddMark mark = (Op.AddMark) OpCodec.decode(
            "{\"t\":\"m\",\"r\":1,\"l\":11,\"k\":\"bold\",\"v\":null,\"a\":[1,1,0],\"b\":[1,3,1]}");

        assertThat(mark.value()).isNull();
    }

    @Test
    @DisplayName("an explicitly null origin is no origin, not the identifier 0@0")
    void explicitNullOriginIsNoOrigin() {
        Op.Insert insert = (Op.Insert) OpCodec.decode(
            "{\"t\":\"i\",\"r\":1,\"l\":5,\"s\":\"a\",\"or\":null,\"ol\":null}");

        assertThat(insert.originLeft()).isNull();
    }

    @Test
    @DisplayName("a truncated operation is refused, not a NullPointerException")
    void truncatedOperationsAreRefused() {
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

        for (int i = 0; i < 3; i++) {
            assertThat(reader.apply(orphan))
                .as("a redelivery of a buffered operation is news again - it will be written to "
                    + "the log and broadcast to the cluster once more, per copy")
                .isEqualTo(RgaDocument.ApplyResult.DUPLICATE);
        }

        assertThat(reader.pendingCount())
            .as("the buffer accumulated one entry per redelivery")
            .isEqualTo(1);

        reader.apply(cause);
        assertThat(reader.text()).isEqualTo("ab");
        assertThat(reader.pendingCount()).isZero();
    }

    @Test
    @DisplayName("an inverted formatting range is refused rather than stored for ever")
    void invertedMarkRangesAreRefused() {
        RgaDocument document = new RgaDocument(1);
        document.insert(0, "abcdef");

        assertThatThrownBy(() -> document.addMark(4, 2, MarkTypes.BOLD, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> document.removeMark(3, 3, MarkTypes.BOLD))
            .isInstanceOf(IllegalArgumentException.class);

        assertThat(document.markCount()).isZero();
    }

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

        assertThat(OpCodec.decode(
            "{\"t\":\"i\",\"r\":5,\"l\":1099511627775,\"s\":\"x\"}").id().lamport())
            .isEqualTo(1_099_511_627_775L);
    }

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

    @Test
    @DisplayName("a numeric field that is not a number is refused rather than coerced")
    void coercedNumbersAreRefused() {
        for (String payload : new String[] {
            "{\"t\":\"m\",\"r\":1,\"l\":9,\"k\":\"bold\",\"a\":[1,1,\"0\"],\"b\":[1,3,1]}",
            "{\"t\":\"m\",\"r\":1,\"l\":9,\"k\":\"bold\",\"a\":[1,1,2],\"b\":[1,3,1]}",
            "{\"t\":\"m\",\"r\":1,\"l\":9,\"k\":\"bold\",\"a\":[1,1,null],\"b\":[1,3,1]}",
            "{\"t\":\"m\",\"r\":1,\"l\":9,\"k\":\"bold\",\"a\":[1,1,true],\"b\":[1,3,1]}",
            "{\"t\":\"i\",\"r\":{},\"l\":5,\"s\":\"a\"}",
            "{\"t\":\"i\",\"r\":true,\"l\":5,\"s\":\"a\"}",
            "{\"t\":\"i\",\"r\":\"1\",\"l\":5,\"s\":\"a\"}",
            "{\"t\":\"i\",\"r\":3000000000,\"l\":5,\"s\":\"a\"}",
            "{\"t\":\"i\",\"r\":1,\"l\":5,\"or\":[],\"ol\":1,\"s\":\"a\"}",
            "{\"t\":\"m\",\"r\":1,\"l\":9,\"k\":\"bold\",\"a\":[{},1,0],\"b\":[1,3,1]}",
            "{\"t\":\"d\",\"r\":1,\"l\":9,\"g\":[[\"2\",3,1]]}",
            "{\"t\":\"i\",\"r\":1,\"l\":1.5,\"s\":\"a\"}",
            "{\"t\":\"i\",\"r\":1,\"l\":\"5\",\"s\":\"a\"}",
            "{\"t\":\"m\",\"r\":1,\"l\":9,\"k\":\"bold\",\"a\":[1,{},0],\"b\":[1,3,1]}",
            "{\"t\":\"d\",\"r\":1,\"l\":9,\"g\":[[2,3,1.5]]}",
            "{\"t\":\"d\",\"r\":1,\"l\":9,\"g\":[[2,3,0]]}",
            "{\"t\":\"d\",\"r\":1,\"l\":9,\"g\":[[2,3,-1]]}",
            "{\"t\":\"d\",\"r\":1,\"l\":9,\"g\":[[2,3,\"5\"]]}",
            "{\"t\":\"d\",\"r\":1,\"l\":9,\"g\":[[2,3,null]]}",
            "{\"t\":\"i\",\"r\":1,\"l\":18446744073709551616,\"s\":\"a\"}",
        }) {
            assertThatThrownBy(() -> OpCodec.decode(payload))
                .as("accepted: %s", payload)
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("an empty insert and a blank mark type are refused by name")
    void constructorRulesAreEnforcedByTheCodec() {
        assertThatThrownBy(() -> OpCodec.decode("{\"t\":\"i\",\"r\":1,\"l\":5,\"s\":\"\"}"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must carry text");
        assertThatThrownBy(() -> OpCodec.decode("{\"t\":\"i\",\"r\":1,\"l\":5,\"s\":[]}"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must be a string");
        assertThatThrownBy(() -> OpCodec.decode(
            "{\"t\":\"m\",\"r\":1,\"l\":9,\"k\":\"  \",\"a\":[1,1,0],\"b\":[1,3,1]}"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must have a type");
        assertThatThrownBy(() -> OpCodec.decode(
            "{\"t\":\"x\",\"r\":1,\"l\":9,\"k\":7,\"a\":[1,1,0],\"b\":[1,3,1]}"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must be a string");
        assertThatThrownBy(() -> OpCodec.decode(
            "{\"t\":\"m\",\"r\":1,\"l\":9,\"k\":\"link\",\"v\":7,\"a\":[1,1,0],\"b\":[1,3,1]}"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must be a string");
    }

    @Test
    @DisplayName("the shapes the encoder actually produces still decode")
    void wellFormedPayloadsStillDecode() {
        RgaDocument document = new RgaDocument(3);
        document.insert(0, "abcdef");
        Op.Delete delete = document.delete(1, 2);
        Op.AddMark bold = document.addMark(0, 3, MarkTypes.BOLD, null);
        Op.RemoveMark unbold = document.removeMark(0, 2, MarkTypes.BOLD);
        Op.Insert appended = document.insert(document.visibleLength(), "gh");

        for (Op op : new Op[] {delete, bold, unbold, appended}) {
            assertThat(OpCodec.decode(OpCodec.encode(op)))
                .as("the encoder's own output was refused: %s", OpCodec.encode(op))
                .isEqualTo(op);
        }
        Op.AddMark link = document.addMark(0, 2, MarkTypes.LINK, "https://example.invalid");
        assertThat(OpCodec.decode(OpCodec.encode(link))).isEqualTo(link);
    }
}
