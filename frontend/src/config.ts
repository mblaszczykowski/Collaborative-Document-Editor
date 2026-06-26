/**
 * Where the backend lives, resolved once.
 *
 * ## What this replaces, and why it was blocking
 *
 * Both addresses used to be written out at their point of use:
 *
 * ```
 * const BASE = `http://${window.location.hostname}:8090/api`   // api.ts
 * brokerURL: `ws://${window.location.hostname}:8090/ws`        // connection.ts
 * ```
 *
 * That works on a laptop and **cannot work anywhere else**, for a reason worse than the
 * hardcoded port: a page served over `https:` is not allowed to open a `ws:` socket. Browsers
 * block it as mixed content, with no fallback. So the plan's §0.1 — "put a link at the top of
 * the README" — was not waiting on a hosting account, as its audit records. It was waiting on
 * four lines of code, and this is the first of them.
 *
 * The scheme is therefore derived from the page rather than written down: `https:` gives
 * `wss:`, `http:` gives `ws:`, and neither is a decision anyone has to remember to make.
 *
 * ## The three deployment shapes, in the order they are checked
 *
 * 1. **`VITE_API_BASE` is set** — the backend is on a known, different origin. Vite compiles
 *    environment variables into the bundle, so this is fixed at *build* time, not at container
 *    start; `frontend/Dockerfile` takes it as a build argument for exactly that reason.
 * 2. **Vite dev server (port 5173)** — the backend is a separate process on `:8090`. This is
 *    what `npm run dev` and the Playwright suite use, and it is why the check exists at all.
 * 3. **Anything else — same origin.** nginx proxies `/api` and `/ws` to the backend, so there
 *    is one origin, one certificate, no CORS preflight and no mixed content. This is the shape
 *    a hosted instance should use, and it is now the default rather than the special case.
 */

const configured = import.meta.env.VITE_API_BASE as string | undefined

/** Port the Vite dev server listens on; see shape 2 above. */
const VITE_DEV_PORT = '5173'

function backendOrigin(): string {
  // UNSET and SET-TO-EMPTY are different answers, and conflating them had a consequence.
  //
  // The first version treated `''` as "not configured" and fell through to the dev-port check.
  // docker-compose builds the image with VITE_API_BASE: "" meaning "same origin, go through the
  // proxy", and publishes the frontend on 5173 — so the check fired, traffic went straight to
  // :8090, and the nginx proxy was never exercised by the one command the README tells people to
  // run. It still worked, because compose also publishes 8090; what it stopped doing was testing
  // the arrangement a real deployment uses.
  //
  // That also made the compose build argument a no-op that looks like configuration, which is the
  // same species of problem as a tuning value nothing reads.
  if (configured !== undefined) {
    // Empty means same origin. Trailing slash removed so callers can concatenate "/api" and get
    // exactly one.
    return configured === '' ? window.location.origin : configured.replace(/\/+$/, '')
  }
  if (window.location.port === VITE_DEV_PORT) {
    return `${window.location.protocol}//${window.location.hostname}:8090`
  }
  return window.location.origin
}

const ORIGIN = backendOrigin()

export const API_BASE = `${ORIGIN}/api`

/**
 * The STOMP endpoint.
 *
 * `http` → `ws` and `https` → `wss` in one substitution, because the two cases differ by
 * exactly the prefix. Writing the scheme out instead is how the previous version ended up
 * pinned to `ws:` forever.
 */
export const WS_URL = `${ORIGIN.replace(/^http/, 'ws')}/ws`

/**
 * Where browser traces go, or `null` to collect none.
 *
 * Unset means **off**, and that is a change: the exporter used to point unconditionally at
 * `http://localhost:4318`. On a hosted instance that is a request every viewer's browser makes
 * once a second (`scheduledDelayMillis`), to a host that is theirs rather than ours, blocked as
 * mixed content before it leaves the page. Observability that only works on the developer's
 * machine should be off everywhere else rather than failing loudly in everyone's console.
 *
 * `docker-compose.yml` sets it for the observability profile, which is where it belongs — the
 * collector is part of that profile and does not exist without it.
 */
export const TRACE_ENDPOINT: string | null =
  (import.meta.env.VITE_OTLP_ENDPOINT as string | undefined) || null

/**
 * The API's origin, without the {@code /api} prefix.
 *
 * The OAuth entry point Spring installs lives at {@code /oauth2/authorization/{provider}} — at the
 * root, not under {@code /api} — and the login screen links to it directly. Derived from
 * {@link API_BASE} rather than resolved a second time, so the two cannot disagree about which
 * server this is.
 */
export const API_ORIGIN = API_BASE.replace(/\/api$/, '')
