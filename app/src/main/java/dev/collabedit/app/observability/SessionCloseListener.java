package dev.collabedit.app.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

/**
 * Counts <b>why</b> WebSocket sessions end.
 *
 * <h2>Why, given that sessions are already counted</h2>
 * The existing {@code ws.sessions.active} and {@code ws.sessions.opened.total} say how many but not
 * <b>why</b> — and when diagnosing divergence that is the only interesting question. A session
 * closed by the user and a session torn down by the server because its send buffer overflowed look
 * identical in a counter, and mean something entirely different.
 *
 * <h2>The open question this metric is meant to answer</h2>
 * The README (the "Degradation point" section) describes an unresolved thread: above ~10 clients in
 * one document convergence stops being repeatable, the server holds the complete set of operations,
 * and some clients fall permanently behind — <b>with no error in the log at all</b>.
 *
 * <p>The hypothesis is that this is {@link CloseStatus#SESSION_NOT_RELIABLE}: with N clients every
 * operation goes out in N frames, a slow client's send queue exceeds
 * {@code send-buffer-size-bytes} and Spring closes its session. The client comes back through the
 * reconnect path, so from the outside it looks like "a brief hiccup" rather than lost deliveries.
 *
 * <p>This metric settles it with a number instead of a guess. It fires at 25 clients and stays
 * silent at 10 -> hypothesis confirmed. Silent in both -> the cause is elsewhere and the search
 * continues. <b>Both outcomes are results</b>, and both beat the current "I do not know".
 *
 * <p>The reason this is a <i>tag</i> rather than a separate counter: the distribution of close
 * reasons is itself information. A sudden rise in {@code reason="server_error"} with
 * {@code reason="normal"} unchanged is a different problem from the reverse, and two independent
 * counters would not show the proportion.
 */
@Component
public class SessionCloseListener {

    private static final Logger log = LoggerFactory.getLogger(SessionCloseListener.class);

    private final DocumentMetrics metrics;

    public SessionCloseListener(DocumentMetrics metrics) {
        this.metrics = metrics;
    }

    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        CloseStatus status = event.getCloseStatus();
        String reason = reasonOf(status);

        metrics.recordSessionClose(reason);

        // Loudly, because this is the only symptom of a problem the README currently records as
        // unresolved. Silent degradation is worse here than noise in the log.
        if (SLOW_CLIENT.equals(reason)) {
            log.warn("Session {} torn down: the client could not keep up with delivery and "
                    + "overflowed the send buffer (send-buffer-size-bytes, ADR-0002). It will come "
                    + "back through reconnect + resync, but until then it RECEIVES NO broadcasts.",
                event.getSessionId());
        }
    }

    /** The client did not receive fast enough and overflowed the send buffer — {@code CloseStatus(4500)}. */
    static final String SLOW_CLIENT = "slow_client_buffer_overflow";

    /**
     * The close reason as a tag value.
     *
     * <p>The set of values is <b>closed</b>, and that is deliberate: a tag with an unbounded number
     * of values (the raw close code, say, or text from the client) blows up the metric's
     * cardinality in Prometheus. Codes outside this list land in a single {@code other} bucket.
     */
    static String reasonOf(CloseStatus status) {
        if (status == null) {
            return "unknown";
        }
        return switch (status.getCode()) {
            case 1000 -> "normal";
            case 1001 -> "going_away";
            case 1006 -> "abnormal";
            case 1011 -> "server_error";
            // 4500 — Spring closes a session this way when its send buffer exceeded the limit or
            // it exceeded send-time-limit. Both mean the same thing: the client cannot keep up.
            case 4500 -> SLOW_CLIENT;
            default -> "other";
        };
    }
}
