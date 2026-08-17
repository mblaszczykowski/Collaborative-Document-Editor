const configured = import.meta.env.VITE_API_BASE as string | undefined

const VITE_DEV_PORT = '5173'

function backendOrigin(): string {
  if (configured !== undefined) {
    return configured === '' ? window.location.origin : configured.replace(/\/+$/, '')
  }
  if (window.location.port === VITE_DEV_PORT) {
    return `${window.location.protocol}//${window.location.hostname}:8090`
  }
  return window.location.origin
}

const ORIGIN = backendOrigin()

export const API_BASE = `${ORIGIN}/api`

export const WS_URL = `${ORIGIN.replace(/^http/, 'ws')}/ws`

export const TRACE_ENDPOINT: string | null =
  (import.meta.env.VITE_OTLP_ENDPOINT as string | undefined) || null

export const API_ORIGIN = API_BASE.replace(/\/api$/, '')
