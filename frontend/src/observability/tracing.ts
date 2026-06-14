import { context, propagation, trace, type Span } from '@opentelemetry/api'
import { OTLPTraceExporter } from '@opentelemetry/exporter-trace-otlp-http'
import { resourceFromAttributes } from '@opentelemetry/resources'
import { BatchSpanProcessor, WebTracerProvider } from '@opentelemetry/sdk-trace-web'
import { ATTR_SERVICE_NAME } from '@opentelemetry/semantic-conventions'
import { TRACE_ENDPOINT } from '../config'

/**
 * Śledzenie zaczynające się w przeglądarce (CP7).
 *
 * <p>Bez tego trace zaczynałby się dopiero na serwerze, a najciekawsza część drogi —
 * od naciśnięcia klawisza do wysłania ramki — byłaby niewidoczna. `CLAUDE.md` §4 wymaga
 * trace'u obejmującego całą drogę pojedynczej edycji, więc kontekst musi powstać tutaj.
 *
 * <p>Kontekst jest przenoszony nagłówkiem ramki STOMP, nie nagłówkiem HTTP. Uścisk dłoni
 * WebSocketa zdarza się raz na sesję, więc kontekst w nagłówku HTTP opisywałby całą sesję
 * zamiast pojedynczej operacji.
 *
 * <p><b>Świadome ograniczenie:</b> śledzimy tylko wysyłanie operacji, nie odbieranie.
 * Powiązanie „operacja pojawiła się u odbiorcy" z pierwotnym trace'em wymagałoby przenoszenia
 * kontekstu wraz z każdą rozesłaną operacją, co powiększyłoby ramkę o ~55 bajtów przy jej
 * typowym rozmiarze rzędu stu — narzut nieproporcjonalny do korzyści. Latencję po stronie
 * odbiorcy mierzymy zamiast tego w harnessie benchmarkowym (CP8), gdzie obie strony dzielą
 * jeden zegar.
 */

let provider: WebTracerProvider | null = null

/**
 * Starts browser tracing, if a collector has been configured.
 *
 * <p>Returns without doing anything when {@link TRACE_ENDPOINT} is unset, and that case is now
 * the default. The exporter used to point unconditionally at `http://localhost:4318`, which is
 * correct on the machine running the observability profile and wrong everywhere else: on a
 * hosted instance every visitor's browser would attempt a request per second to a collector on
 * *their* machine, blocked as mixed content before it left the page.
 *
 * <p>Tracing being off is not a degraded mode here. The spans this file produces cover the
 * client half of an edit; the server half is exported by the Java agent regardless, and the
 * numbers in the README come from the load harness, not from these spans.
 */
export function initialiseTracing(): void {
  if (provider !== null) return
  if (TRACE_ENDPOINT === null) return

  provider = new WebTracerProvider({
    resource: resourceFromAttributes({ [ATTR_SERVICE_NAME]: 'inkline-frontend' }),
    spanProcessors: [
      new BatchSpanProcessor(
        // The collector accepts OTLP over HTTP on 4318 (gRPC does not work from a browser).
        new OTLPTraceExporter({ url: `${TRACE_ENDPOINT}/v1/traces` }),
        {
          // Krótkie okno: przy demo i przy diagnozowaniu chcemy zobaczyć trace niemal
          // od razu, a nie po domyślnych pięciu sekundach.
          scheduledDelayMillis: 1000,
        },
      ),
    ],
  })
  provider.register()
}

const tracer = () => trace.getTracer('inkline')

/**
 * Wykonuje wysłanie operacji wewnątrz spanu i zwraca kontekst do przekazania serwerowi.
 *
 * Zwracany `traceparent` jest formatem W3C Trace Context — tym samym, który rozumie
 * propagator po stronie Javy, więc obie strony nie muszą nic o sobie wiedzieć poza
 * nazwą nagłówka.
 */
export function traceOperation<T>(
  name: string,
  attributes: Record<string, string | number>,
  action: (traceparent: string | undefined) => T,
): T {
  if (provider === null) {
    return action(undefined)
  }
  const span: Span = tracer().startSpan(name, { attributes })
  return context.with(trace.setSpan(context.active(), span), () => {
    const carrier: Record<string, string> = {}
    propagation.inject(context.active(), carrier)
    try {
      return action(carrier['traceparent'])
    } finally {
      span.end()
    }
  })
}
