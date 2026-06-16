package dev.collabedit.app.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;

/**
 * Mapping a session close code onto a metric tag value.
 *
 * <table>
 *   <caption>Test -> failure mode</caption>
 *   <tr><th>Test</th><th>What it catches</th></tr>
 *   <tr><td>{@code bufferOverflowIsRecognised}</td>
 *       <td>Code 4500 falling into the {@code other} bucket. The metric exists, the panel is
 *           there, the graph is flat — and it looks like proof the problem is absent, when it
 *           only means we are not measuring it. That is the worst possible failure mode for a
 *           diagnostic metric.</td></tr>
 *   <tr><td>{@code unknownCodesCollapseIntoOneBucket}</td>
 *       <td>The close code going into the tag verbatim. A client may close a connection with any
 *           code in the 4000–4999 range, so this would be externally driven cardinality — that is,
 *           a way to blow up Prometheus with one malicious client.</td></tr>
 *   <tr><td>{@code missingStatusDoesNotThrow}</td>
 *       <td>An event with no status crashing the listener. An exception while handling a Spring
 *           event brings down the whole session teardown, not just the metric.</td></tr>
 * </table>
 */
class SessionCloseListenerTest {

    @Test
    @DisplayName("send buffer overflow gets its own tag value rather than falling into 'other'")
    void bufferOverflowIsRecognised() {
        assertThat(SessionCloseListener.reasonOf(CloseStatus.SESSION_NOT_RELIABLE))
            .isEqualTo(SessionCloseListener.SLOW_CLIENT);

        // The same code spelled out — if Spring ever changed the constant, we want to hear it
        // from a test rather than from a flat graph.
        assertThat(CloseStatus.SESSION_NOT_RELIABLE.getCode()).isEqualTo(4500);
    }

    @Test
    @DisplayName("recognised closes have disjoint tag values")
    void knownCodesAreDistinct() {
        assertThat(SessionCloseListener.reasonOf(CloseStatus.NORMAL)).isEqualTo("normal");
        assertThat(SessionCloseListener.reasonOf(CloseStatus.GOING_AWAY)).isEqualTo("going_away");
        assertThat(SessionCloseListener.reasonOf(CloseStatus.NO_CLOSE_FRAME)).isEqualTo("abnormal");
        assertThat(SessionCloseListener.reasonOf(CloseStatus.SERVER_ERROR)).isEqualTo("server_error");
    }

    @Test
    @DisplayName("unknown codes land in one bucket — cardinality must not depend on the client")
    void unknownCodesCollapseIntoOneBucket() {
        assertThat(SessionCloseListener.reasonOf(new CloseStatus(4001))).isEqualTo("other");
        assertThat(SessionCloseListener.reasonOf(new CloseStatus(4999))).isEqualTo("other");
        assertThat(SessionCloseListener.reasonOf(new CloseStatus(1013))).isEqualTo("other");
    }

    @Test
    @DisplayName("a missing status gives a fallback value rather than an exception")
    void missingStatusDoesNotThrow() {
        assertThat(SessionCloseListener.reasonOf(null)).isEqualTo("unknown");
    }
}
