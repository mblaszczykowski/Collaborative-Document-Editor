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
 * Śledzenie pojedynczej operacji edycji przez cały system (CP7).
 *
 * <p>Cel z {@code CLAUDE.md} §4: jeden trace obejmujący drogę
 * <b>klawisz w przeglądarce → WebSocket → scalenie CRDT → zapis → rozesłanie</b>.
 *
 * <h2>Dlaczego nagłówek ramki STOMP, a nie nagłówek HTTP</h2>
 * Uścisk dłoni WebSocketa to pojedyncze żądanie HTTP na początku połączenia, które potem
 * żyje godzinami. Kontekst śledzenia w nagłówku HTTP dotyczyłby więc <b>całej sesji</b>,
 * a nie pojedynczej edycji — czyli byłby bezużyteczny. Nagłówki na poziomie ramki STOMP
 * są jedynym miejscem, gdzie da się przenieść kontekst per operacja, i to jest konkretny,
 * praktyczny powód wyboru z ADR-0002.
 *
 * <h2>Dlaczego rozesłanie jest powiązane linkiem, a nie zagnieżdżeniem</h2>
 * Operacja przychodzi jedna, a wychodzi do N klientów. Relacja rodzic-dziecko sugerowałaby,
 * że rozesłanie do każdego klienta jest częścią obsługi tego jednego żądania i że jego czas
 * się sumuje. Semantycznie poprawny jest <b>link</b>: „to zdarzenie ma związek z tamtym",
 * bez implikowania zawierania. Przy dwustu odbiorcach różnica przestaje być akademicka.
 */
@Component
public class EditTracer {

    /** Nazwa nagłówka ramki STOMP z kontekstem śledzenia (W3C Trace Context). */
    public static final String TRACEPARENT = "traceparent";

    private final OpenTelemetry openTelemetry;
    private final Tracer tracer;

    public EditTracer(OpenTelemetry openTelemetry) {
        this.openTelemetry = openTelemetry;
        this.tracer = openTelemetry.getTracer("dev.collabedit");
    }

    /**
     * Wyciąga kontekst śledzenia z nagłówków ramki STOMP.
     *
     * <p>Brak nagłówka nie jest błędem — klient bez włączonego śledzenia po prostu nie
     * dołoży kontekstu, a serwer utworzy nowy trace. Wymaganie kontekstu byłoby uzależnieniem
     * poprawności działania od obserwowalności, czyli odwróceniem zależności.
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
     * Otwiera span obejmujący obsługę operacji po stronie serwera.
     *
     * <p>Zwracany obiekt trzeba zamknąć — {@code try-with-resources} w miejscu wywołania.
     */
    public EditSpan startReceive(SimpMessageHeaderAccessor headers, UUID documentId,
                                 int replicaId, String operationType) {
        Context parent = extractContext(headers);
        Span span = tracer.spanBuilder("op.receive")
            .setParent(parent)
            // CONSUMER, bo operacja przychodzi z kolejki komunikatów, a nie jako żądanie
            // synchroniczne. Zła wartość tutaj psuje wykresy zależności usług w Grafanie.
            .setSpanKind(SpanKind.CONSUMER)
            .setAttribute("collabedit.document", documentId.toString())
            .setAttribute("collabedit.replica", replicaId)
            .setAttribute("collabedit.op.type", operationType)
            .startSpan();
        return new EditSpan(span, span.makeCurrent());
    }

    /** Span potomny dla wyodrębnionego etapu (scalenie, rozesłanie). */
    public EditSpan startStage(String name) {
        Span span = tracer.spanBuilder(name).startSpan();
        return new EditSpan(span, span.makeCurrent());
    }

    /**
     * Span dla operacji wsadowej, POWIĄZANY z trace'ami operacji wchodzących w skład wsadu.
     *
     * <p>Zapis do logu obejmuje wiele operacji naraz i dzieje się na innym wątku, w innym
     * momencie niż ich przyjęcie (wsadowanie co 50 ms — ADR-0003). Zagnieżdżenie go pod
     * którymkolwiek z tych trace'ów byłoby fałszywe: sugerowałoby, że zapis jest częścią
     * obsługi tej jednej operacji i że jej czas trwania go obejmuje. Przy wsadzie stu
     * operacji dziewięćdziesiąt dziewięć trace'ów pokazywałoby wtedy czas cudzego zapisu.
     *
     * <p><b>Link</b> wyraża dokładnie to, co zachodzi: „to zdarzenie ma związek z tamtymi",
     * bez implikowania zawierania. Jest to jedna z niewielu sytuacji, w których link jest
     * właściwy, a nie tylko wygodny — i dlatego warto ją nazwać.
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
     * Odczyt nagłówka W3C {@code traceparent} do kontekstu spanu.
     *
     * <p>Zwraca {@code null} przy każdej nieprawidłowości zamiast rzucać: kontekst śledzenia
     * przychodzi od klienta i nie wolno, żeby jego uszkodzenie przewracało zapis operacji.
     * Obserwowalność nie może być warunkiem poprawności działania.
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

    /** Span z bieżącym kontekstem — do zapisania w nagłówku wiadomości klastrowej. */
    public String currentTraceparent() {
        Span current = Span.current();
        if (!current.getSpanContext().isValid()) {
            return null;
        }
        return "00-" + current.getSpanContext().getTraceId()
            + "-" + current.getSpanContext().getSpanId() + "-01";
    }

    /** Para span + zakres, zamykana razem. */
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
