# ADR-0002 — Real-time transport: WebSocket + STOMP

- **Status:** Accepted
- **Date:** 2026-08-14
- **Applies to checkpoints:** CP3 (implementation), CP6 (reconnect), CP7 (traceparent propagation)

---

## Context

We need a channel over which:

1. the client sends CRDT operations to the server (**upstream**, high frequency — every keystroke
   is a potential operation),
2. the server distributes other users' operations and presence events (**downstream**, fan-out to
   N clients),
3. tracing context (`traceparent`) can be carried for ADR-0007 / CP7,
4. a broken connection can be detected **faster than the default TCP timeout**, because the whole
   offline mechanism (CP6) depends on the client knowing it is offline.

Traffic profile: many small messages in both directions, latency more important than throughput,
long-lived connection.

---

## Options considered

### Option A — Raw WebSocket + a custom frame protocol

**For:**
- The smallest per-message overhead — a few bytes of header in a custom binary format.
- Full control over the format, easy to serialise operations compactly.
- No dependency on a pub/sub model imposed from outside.

**Against:**
- We would have to build ourselves: multiplexing (one socket, many documents), routing messages
  to handlers, ack/correlation-id, heartbeat, headers for metadata. That is precisely rewriting
  STOMP, only worse and without its tests.
- Per-message metadata (needed for `traceparent`) requires a convention of our own.
- Effort spent reimplementing the transport layer demonstrates nothing this project is meant to
  demonstrate. The difficulty should be in the CRDT, not in the framing.

### Option B — SSE (downstream) + HTTP POST (upstream)

**For:**
- Passes through every proxy and firewall without an Upgrade negotiation.
- Automatic reconnect with `Last-Event-ID` built into the browser — effectively free
  resynchronisation on the downstream side.
- A simpler server model (plain HTTP).

**Against:**
- **The upstream channel is a separate connection.** Every keystroke is a POST — the HTTP header
  overhead exceeds the size of the operation itself, and we lose ordering within a single stream
  without extra work.
- Two channels = two independent connection states = detecting "I am offline" becomes ambiguous
  (downstream alive, upstream dead). That directly complicates CP6.
- The per-origin connection limit in HTTP/1.1 (6) — a few open document tabs exhaust the pool.
  With HTTP/2 that disappears, but I do not want correctness to depend on the client's protocol
  version.

### Option C — WebSocket + STOMP (Spring `spring-websocket` + `spring-messaging`)

STOMP is a simple text protocol over WebSocket: frames with a command, headers and a body, with
pub/sub semantics (`SUBSCRIBE /topic/...`, `SEND /app/...`).

**For:**
- Multiplexing and routing come for free — one socket serves N documents through separate
  destinations `/topic/doc/{id}/ops`, `/topic/doc/{id}/presence`.
- **Frame-level headers** — `traceparent` (W3C Trace Context) goes there directly, without
  cluttering the operation payload. That genuinely simplifies CP7.
- First-class Spring integration: `@MessageMapping`, `SimpMessagingTemplate`, channel interceptors
  (needed for auth at `CONNECT` and for propagating OTel context).
- Heartbeat is part of the protocol, negotiated at `CONNECT` — no need to invent it.
- Standardised, so clients exist outside the browser — relevant for Gatling in CP8.

**Against:**
- Text frames, so a binary payload needs base64 (~33% overhead) or sticking to JSON.
- STOMP header overhead per message is larger than in option A.
- Another layer of abstraction to understand while debugging.

### Option D — WebTransport / HTTP/3

**For:** streams without head-of-line blocking, native datagrams — theoretically ideal.
**Against:** browser support incomplete, no mature server stack on the Java side, requires HTTPS
with a real certificate even locally. Premature.

---

## Decision

**WebSocket + STOMP (option C).**

CRDT operation payload: **JSON** in the first version. Compact binary serialisation (base64 in a
STOMP frame) has been ready in `crdt-core` since CP2, but we enable it only once CP8 shows with
numbers that payload size genuinely limits throughput. Optimisation without measurement is not
allowed.

### Parameters and their justification

None of these parameters is a default value "because that is what the tutorial had".

| Parameter | Value | Justification |
|---|---|---|
| Heartbeat server→client | **10 000 ms** | Must be clearly shorter than a typical intermediary's idle timeout (nginx `proxy_read_timeout` defaults to 60 s, many CDNs/LBs use 30–60 s). 10 s gives a ~3–6× margin, and at a thousand sessions that is 100 frames/s — negligible. |
| Heartbeat client→server | **10 000 ms** | Symmetrically. The server treats a session as dead once tolerance is exceeded, releasing the presence entry. |
| Missed-heartbeat tolerance | **2.5 × interval (25 s)** | One lost heartbeat must not tear down a session — on a mobile link a single delay >10 s is normal. Two in a row is a real indication. The trade-off: detecting a break takes up to 25 s, but false disconnects are rare. Too aggressive a value is worse than too loose, because every false disconnect triggers a full resynchronisation (CP6). |
| Message size limit | **64 KiB** | A single operation is on the order of 100 B; 64 KiB also accommodates a large paste. A higher limit is a DoS vector absent rate limiting (a deliberate simplification, see README). |
| Session send buffer | **512 KiB** | A slow client must not block the broadcast to everyone else. On overflow the session is closed and the client takes the reconnect+resync path from CP6 — controlled degradation instead of a jam. |
| Send time limit | **20 000 ms** | Beyond this the client is treated as not receiving. |
| SockJS fallback | **disabled** | It adds transport paths (xhr-streaming, jsonp) we will not test, which would make them untested code on the critical path. WebSocket has broad support today. A deliberate scope limitation. |

The values live in `application.yml` with a comment beside each, not in the code.

---

## Consequences

### Positive
- STOMP headers give free room for `traceparent`, so end-to-end tracing (CP7) needs no change to
  the operation format.
- One socket per client regardless of how many documents are open.
- A channel interceptor gives one place for auth (at `CONNECT`) and one for instrumentation —
  instead of scattering them across handlers.

### Negative
- **STOMP header overhead** per operation is real, and at 250 users × a high editing rate it
  could dominate the traffic. **To be measured in CP8** — if it turns out to dominate, the
  mitigation is batching several operations into one frame, not changing protocol.
- **JSON payload, not binary** — a deliberate debt, to be revisited after measurement.
- **No SockJS** means a client behind an exotic proxy blocking Upgrade will not connect at all,
  rather than degrading to polling.
- Detecting a broken connection takes up to 25 s in the worst case. For the user that means the
  "offline" indicator in the UI may appear with a delay — the client-side mitigation is to also
  listen for `navigator.onLine` events and socket errors (CP6).

### Verified in CP8

- **The 64 KiB message limit turned out to have a consequence I had not foreseen.** It does not
  concern a single operation (those are ~100 B) but the **resynchronisation response**: a client
  asking for the backlog of a large document received one frame well above the limit, which
  simply never arrived. The effect: the client that needed the data most could not get it, and
  stayed permanently behind — with no error in the log. Fixed by sending in chunks of 300
  operations. The limit itself stays; the mistake was assuming it applied only to incremental
  traffic.
- **The 512 KiB buffer did not turn out to be a bottleneck** at 50 clients — the broker's
  statistics showed not a single session closed due to overflow. Transport errors were observed
  whose source I did not identify (see README, the degradation point).
- **STOMP+JSON overhead was not measured separately.** The results showed that the bottleneck is
  not frame size, so optimising the format (binary serialisation instead of JSON) remains
  unsupported by data — which is exactly where it should stay.
- **Heartbeat tolerance could not be checked meaningfully**: clients and server ran on one
  machine, so the jitter this value protects against essentially did not occur. It stays as a
  value justified by reasoning rather than by measurement.

### Verified in CP10

- **Broadcasting one operation per frame was replaced by a 10 ms window.** The reason is not
  tuning but a property of the construction: an operation goes out in as many frames as there are
  recipients, so the number of frames grows with the square of the client count. The measured
  gain is a 2.7× shorter time to convergence at 50 clients (details in the README). The new
  `broadcast-window-ms` value must be clearly smaller than `op-batch-window-ms` — justified
  beside the value itself in `application.yml`.
- **The 64 KiB limit started to bind in a third place.** After coalescing broadcasts, a batch can
  exceed the limit just as the resynchronisation response did in CP8. Here, however, the split is
  **by bytes rather than by operation count**, and that incidentally exposes a flaw in the CP8
  solution: `SYNC_CHUNK = 300` assumes an operation weighs ~100 B, which stops being true when
  someone pastes a large fragment of text — one such operation is tens of kilobytes. For the
  resynchronisation path a limit counted in operations **remains incorrect**; this is a known
  debt, not an oversight.
- **The 512 KiB buffer still did not turn out to be a bottleneck**, now measured directly with
  the `ws_sessions_closed_total{reason}` metric rather than with the broker's aggregate
  statistics. Not a single closure due to overflow at 50 clients.
- **The CP8 finding about "transport errors whose source I did not identify" now has a candidate
  explanation — on the rig's side, not the system's.** The test client built a
  `StandardWebSocketClient` without configuring the JSR-356 container, whose default text buffer
  is **8 KiB**, eight times smaller than the limit this ADR declares. With one operation per
  frame the threshold was unreachable and the problem lay dormant; with coalescing it surfaced
  immediately as "no convergence at 10 clients" — with every operation present on the server and
  no error in the log. The browser has no such limit. The rig was measuring itself.
