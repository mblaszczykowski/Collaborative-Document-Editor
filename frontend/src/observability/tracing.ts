import { context, propagation, trace, type Span } from '@opentelemetry/api'
import type { WebTracerProvider } from '@opentelemetry/sdk-trace-web'
import { TRACE_ENDPOINT } from '../config'

let provider: WebTracerProvider | null = null
let starting = false

export function initialiseTracing(): void {
  if (provider !== null || starting) return
  if (TRACE_ENDPOINT === null) return

  starting = true
  void Promise.all([
    import('@opentelemetry/sdk-trace-web'),
    import('@opentelemetry/exporter-trace-otlp-http'),
    import('@opentelemetry/resources'),
  ]).then(([sdk, exporter, resources]) => {
    provider = new sdk.WebTracerProvider({
      resource: resources.resourceFromAttributes({ 'service.name': 'collabedit-frontend' }),
      spanProcessors: [new sdk.BatchSpanProcessor(
        new exporter.OTLPTraceExporter({ url: `${TRACE_ENDPOINT}/v1/traces` }),
        { scheduledDelayMillis: 1000 },
      )],
    })
    provider.register()
  }).catch((error: unknown) => {
    starting = false
    console.warn('Tracing could not be initialized', error)
  })
}

const tracer = () => trace.getTracer('collabedit')

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
