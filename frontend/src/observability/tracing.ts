import { context, propagation, trace, type Span } from '@opentelemetry/api'
import { OTLPTraceExporter } from '@opentelemetry/exporter-trace-otlp-http'
import { resourceFromAttributes } from '@opentelemetry/resources'
import { BatchSpanProcessor, WebTracerProvider } from '@opentelemetry/sdk-trace-web'
import { ATTR_SERVICE_NAME } from '@opentelemetry/semantic-conventions'
import { TRACE_ENDPOINT } from '../config'

let provider: WebTracerProvider | null = null

export function initialiseTracing(): void {
  if (provider !== null) return
  if (TRACE_ENDPOINT === null) return

  provider = new WebTracerProvider({
    resource: resourceFromAttributes({ [ATTR_SERVICE_NAME]: 'collabedit-frontend' }),
    spanProcessors: [
      new BatchSpanProcessor(
        new OTLPTraceExporter({ url: `${TRACE_ENDPOINT}/v1/traces` }),
        {
          scheduledDelayMillis: 1000,
        },
      ),
    ],
  })
  provider.register()
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
