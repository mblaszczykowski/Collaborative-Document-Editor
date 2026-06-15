package dev.collabedit.app.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Component;

/**
 * Tracing a single edit operation through the whole system (CP7).
 *
 * <p>The goal from {@code CLAUDE.md} §4: one trace covering the path
 * <b>keystroke in the browser -> WebSocket -> CRDT merge -> write -> broadcast</b>.
 *
 * <h2>Why a STOMP frame header rather than an HTTP header</h2>
 * The WebSocket handshake is a single HTTP request at the start of a connection that then lives for
 * hours. A trace context in an HTTP header would therefore cover <b>the whole session</b> rather
 * than a single edit — that is, it would be useless. STOMP frame headers are the only place a
 * per-operation context can be carried, and that is the concrete, practical reason behind the
 * choice in ADR-0002.
 *
 * <h2>Why the broadcast is joined by a link rather than by nesting</h2>
 * One operation comes in, and goes out to N clients. A parent-child relationship would suggest that
 * broadcasting to each client is part of handling that one request and that its duration adds up.
 * The semantically correct construct is a <b>link</b>: "this event is related to that one", without
 * implying containment. At two hundred recipients the difference stops being academic.
 */
@Component
public class EditTracer {

    /** The name of the STOMP frame header carrying the trace context (W3C Trace Context). */
    public static final String TRACEPARENT = "traceparent";

    private final OpenTelemetry openTelemetry;
    private final Tracer tracer;

    public EditTracer(OpenTelemetry openTelemetry) {
        this.openTelemetry = openTelemetry;
        this.tracer = openTelemetry.getTracer("dev.collabedit");
    }

    /**
     * Extracts the trace context from the STOMP frame headers.
     *
     * <p>A missing header is not an error — a client without tracing enabled simply adds no
     * context, and the server starts a new trace. Requiring the context would make correct
     * operation depend on observability, which inverts the dependency.
     */
    private Context extractContext(SimpMessageHeaderAccessor headers) {
        return openTelemetry.getPropagators().getTextMapPropagator()
            .extract(Context.current(), headers, STOMP_HEADERS);
    }

    private static final TextMapGetter<SimpMessageHeaderAccessor> STOMP_HEADERS =
        new TextMapGetter<>() {
            @Override
            public Iterable<String> keys(SimpMessageHeaderAccessor headers) {
                Map<String, List<String>> native_ = headers.toNativeHeaderMap();
                return native_ == null ? List.of() : native_.keySet();
            }

            @Override
            public String get(SimpMessageHeaderAccessor headers, String key) {
                if (headers == null) {
                    return null;
                }
                List<String> values = headers.getNativeHeader(key);
                return values == null || values.isEmpty() ? null : values.getFirst();
            }
        };

    /**
     * Opens a span covering the server-side handling of an operation.
     *
     * <p>The returned object has to be closed — {@code try-with-resources} at the call site.
     */
    public EditSpan startReceive(SimpMessageHeaderAccessor headers, UUID documentId,
                                 int replicaId, String operationType) {
        Context parent = extractContext(headers);
        Span span = tracer.spanBuilder("op.receive")
            .setParent(parent)
            // CONSUMER, because the operation arrives from a message queue rather than as a
            // synchronous request. The wrong value here corrupts the service dependency graphs in
            // Grafana.
            .setSpanKind(SpanKind.CONSUMER)
            .setAttribute("collabedit.document", documentId.toString())
            .setAttribute("collabedit.replica", replicaId)
            .setAttribute("collabedit.op.type", operationType)
            .startSpan();
        return new EditSpan(span, span.makeCurrent());
    }

    /** A child span for a distinct stage (merge, broadcast). */
    public EditSpan startStage(String name) {
        Span span = tracer.spanBuilder(name).startSpan();
        return new EditSpan(span, span.makeCurrent());
    }

    /**
     * A span for a batched operation, LINKED to the traces of the operations making up the batch.
     *
     * <p>A write to the log covers many operations at once and happens on a different thread, at a
     * different moment from their arrival (batching every 50 ms — ADR-0003). Nesting it under any
     * one of those traces would be false: it would suggest the write is part of handling that one
     * operation and that its duration contains it. In a batch of a hundred operations,
     * ninety-nine traces would then show someone else's write time.
     *
     * <p>A <b>link</b> expresses exactly what happens: "this event is related to those ones",
     * without implying containment. This is one of the few situations where a link is correct
     * rather than merely convenient — which is why it is worth naming.
     */
    public EditSpan startBatch(String name, List<String> originatingTraceparents) {
        var builder = tracer.spanBuilder(name).setSpanKind(SpanKind.INTERNAL).setNoParent();
        int linked = 0;
        for (String traceparent : originatingTraceparents) {
            SpanContext parsed = parseTraceparent(traceparent);
            if (parsed != null) {
                builder.addLink(parsed);
                linked++;
            }
        }
        Span span = builder.startSpan();
        span.setAttribute("collabedit.batch.linked_traces", linked);
        return new EditSpan(span, span.makeCurrent());
    }

    /**
     * Parses a W3C {@code traceparent} header into a span context.
     *
     * <p>Returns {@code null} on any malformation rather than throwing: the trace context comes
     * from the client, and corrupting it must never bring down an operation's write. Observability
     * cannot be a precondition for correct operation.
     */
    private static SpanContext parseTraceparent(String traceparent) {
        if (traceparent == null) {
            return null;
        }
        String[] parts = traceparent.split("-");
        if (parts.length < 4 || parts[1].length() != 32 || parts[2].length() != 16) {
            return null;
        }
        try {
            return SpanContext.createFromRemoteParent(
                parts[1], parts[2], TraceFlags.getSampled(), TraceState.getDefault());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The current context as a span header value — to be put on a cluster message. */
    public String currentTraceparent() {
        Span current = Span.current();
        if (!current.getSpanContext().isValid()) {
            return null;
        }
        return "00-" + current.getSpanContext().getTraceId()
            + "-" + current.getSpanContext().getSpanId() + "-01";
    }

    /** A span + scope pair, closed together. */
    public record EditSpan(Span span, Scope scope) implements AutoCloseable {

        public void fail(Throwable error) {
            span.recordException(error);
            span.setStatus(io.opentelemetry.api.trace.StatusCode.ERROR);
        }

        public void attribute(String key, long value) {
            span.setAttribute(key, value);
        }

        public void attribute(String key, String value) {
            span.setAttribute(key, value);
        }

        @Override
        public void close() {
            scope.close();
            span.end();
        }
    }
}
