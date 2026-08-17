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

@Component
public class EditTracer {
    public static final String TRACEPARENT = "traceparent";

    private final OpenTelemetry openTelemetry;
    private final Tracer tracer;

    public EditTracer(OpenTelemetry openTelemetry) {
        this.openTelemetry = openTelemetry;
        this.tracer = openTelemetry.getTracer("dev.collabedit");
    }

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

    public EditSpan startReceive(SimpMessageHeaderAccessor headers, UUID documentId,
                                 int replicaId, String operationType) {
        Context parent = extractContext(headers);
        Span span = tracer.spanBuilder("op.receive")
            .setParent(parent)
            .setSpanKind(SpanKind.CONSUMER)
            .setAttribute("collabedit.document", documentId.toString())
            .setAttribute("collabedit.replica", replicaId)
            .setAttribute("collabedit.op.type", operationType)
            .startSpan();
        return new EditSpan(span, span.makeCurrent());
    }

    public EditSpan startStage(String name) {
        Span span = tracer.spanBuilder(name).startSpan();
        return new EditSpan(span, span.makeCurrent());
    }

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

    public String currentTraceparent() {
        Span current = Span.current();
        if (!current.getSpanContext().isValid()) {
            return null;
        }
        return "00-" + current.getSpanContext().getTraceId()
            + "-" + current.getSpanContext().getSpanId() + "-01";
    }

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
