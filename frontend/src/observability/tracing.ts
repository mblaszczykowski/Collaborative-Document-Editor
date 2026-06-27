import { context, propagation, trace, type Span } from '@opentelemetry/api'
import { OTLPTraceExporter } from '@opentelemetry/exporter-trace-otlp-http'
import { resourceFromAttributes } from '@opentelemetry/resources'
import { BatchSpanProcessor, WebTracerProvider } from '@opentelemetry/sdk-trace-web'
import { ATTR_SERVICE_NAME } from '@opentelemetry/semantic-conventions'
import { TRACE_ENDPOINT } from '../config'

/**
 * Tracing that starts in the browser (CP7).
 *
 * Without this a trace would only begin at the server, and the most interesting part of the path —
 * from keystroke to frame — would be invisible. `CLAUDE.md` §4 calls for a trace covering the whole
 * path of a single edit, so the context has to be created here.
 *
 * The context travels in a STOMP frame header, not an HTTP header. The WebSocket handshake happens
 * once per session, so a context in an HTTP header would describe the whole session rather than a
 * single operation.
 *
 * **A deliberate limitation:** we trace only sending operations, not receiving them. Linking "the
 * operation appeared at the receiver" to the original trace would require carrying the context with
 * every broadcast operation, growing a frame by ~55 bytes against a typical size of about a hundred
 * — overhead out of proportion to the benefit. The receiver-side latency is measured instead in the
 * benchmark harness (CP8), where both sides share one clock.
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
    resource: resourceFromAttributes({ [ATTR_SERVICE_NAME]: 'collabedit-frontend' }),
    spanProcessors: [
      new BatchSpanProcessor(
        // The collector accepts OTLP over HTTP on 4318 (gRPC does not work from a browser).
        new OTLPTraceExporter({ url: `${TRACE_ENDPOINT}/v1/traces` }),
        {
          // A short window: for a demo and for diagnosis we want to see a trace almost
          // immediately, not after the default five seconds.
          scheduledDelayMillis: 1000,
        },
      ),
    ],
  })
  provider.register()
}

const tracer = () => trace.getTracer('collabedit')

/**
 * Performs the send of an operation inside a span and returns the context to hand to the server.
 *
 * The returned `traceparent` is in the W3C Trace Context format — the same one the propagator on
 * the Java side understands, so neither side needs to know anything about the other beyond the
 * header's name.
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
