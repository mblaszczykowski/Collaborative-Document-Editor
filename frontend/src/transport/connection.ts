import { Client, type IMessage, type StompSubscription } from '@stomp/stompjs'
import { WS_URL } from '../config'
import { decodeOp, encodeOp, type WireOp } from '../crdt/codec'
import type { Id, Op } from '../crdt/types'
import { traceOperation } from '../observability/tracing'
import { offlineStore } from './offlineStore'
import { NetworkSimulator } from './simulator'

/**
 * The connection to the server: STOMP over WebSocket (ADR-0002).
 *
 * Three things this class is responsible for, worth naming:
 *
 * 1. **The buffer of unacknowledged operations.** An operation leaves the buffer only once an
 *    acknowledgement carrying `serverSeq` arrives, that is, after a DURABLE WRITE and not after the
 *    broadcast. Releasing it earlier would mean an instance crash between broadcast and write left
 *    an operation others can see and nobody can reproduce (ADR-0003).
 *
 * 2. **The resynchronisation cursor.** `lastServerSeq` says how far the client definitely got.
 *    After reconnecting it asks for everything above that. Caution is cheap: duplicates sent back
 *    are harmless (the idempotence from ADR-0001), a lost operation is not.
 *
 * 3. **Wiring in the network simulator** on the outbound path, so that the demo and the chaos tests
 *    use the same mechanism.
 */

export type ConnectionState = 'connecting' | 'online' | 'offline' | 'resyncing'

export interface PresenceEntry {
  replicaId: number
  userId: string
  displayName: string
  color: string
  cursorReplicaId: number | null
  cursorLamport: number | null
  anchorReplicaId: number | null
  anchorLamport: number | null
  typing: boolean
}

export interface ConnectionHandlers {
  /** The replica identifier assigned by the server — it arrives before the first operation. */
  onSession: (replicaId: number) => void
  /** Whether the replica already holds the operation with the given identifier — for gap detection. */
  hasOperation: (replicaId: number, lamport: number) => boolean
  onRemoteOps: (ops: Op[]) => void
  /** The replica's current version vector — reported to the server for GC (ADR-0005). */
  versionVector: () => Map<number, number>
  onPresence: (entries: PresenceEntry[]) => void
  onStateChange: (state: ConnectionState, pendingCount: number) => void
  onError: (code: string, message: string) => void
  /**
   * Somebody's access to this document changed (CP21).
   *
   * The message does NOT carry this client's new role in a form worth trusting — it goes to
   * the document topic, so every participant hears it. All the client learns from it is that
   * it should ask the server about ITS OWN role.
   *
   * @param userId the person affected; `null` means "everybody re-check", which is what a
   *               visibility change looks like
   */
  onAccessChanged: (userId: string | null) => void
}

export class DocumentConnection {
  private client: Client | null = null
  private subscriptions: StompSubscription[] = []

  /** Operations sent but not yet acknowledged as written. Key: replicaId@lamport. */
  private readonly unacknowledged = new Map<string, Op>()
  private lastServerSeq = 0
  /** Participants by replicaId — merged from deltas, see onPresence. */
  private readonly presence = new Map<number, PresenceEntry>()
  private state: ConnectionState = 'connecting'
  private reconnectAttempt = 0
  private gapDetected = false

  private readonly handleBrowserOffline = () => this.setState('offline')
  private readonly handleBrowserOnline = () => {
    // We do not set "online" here — the STOMP connection still has to come up. Declaring
    // readiness before actually connecting would be the same mistake as showing "Saved" after
    // losing the network, only in the other direction.
    if (this.state === 'offline') {
      this.setState('connecting')
    }
  }
  private progressTimer: number | null = null

  readonly simulator = new NetworkSimulator((payload) => this.publishOp(payload as Op))

  private readonly documentId: string
  private readonly token: string
  private readonly handlers: ConnectionHandlers

  constructor(
    documentId: string,
    token: string,
    initialServerSeq: number,
    handlers: ConnectionHandlers,
  ) {
    this.documentId = documentId
    this.token = token
    this.handlers = handlers
    this.lastServerSeq = initialServerSeq
  }

  /**
   * Loads the unacknowledged operations from the previous browser session.
   *
   * Called before connecting: operations from a closed tab have to reach the buffer before
   * resynchronisation starts, otherwise the first pass will not send them.
   */
  async restoreOfflineBuffer(): Promise<Op[]> {
    const stored = await offlineStore.pending(this.documentId)
    for (const op of stored) {
      this.unacknowledged.set(keyOf(op.id), op)
    }
    this.setState(this.state)
    return stored
  }

  connect(): void {
    // The browser knows about a lost connection IMMEDIATELY, whereas the heartbeat takes a dozen
    // or so seconds to expire (ADR-0002: a 10 s interval, 2.5× tolerance). For that whole time the
    // indicator would show "Saved" even though nothing is reaching the server any more — that is,
    // it would lie in the one situation where the user has to know the truth.
    window.addEventListener('offline', this.handleBrowserOffline)
    window.addEventListener('online', this.handleBrowserOnline)

    const client = new Client({
      // Scheme and host come from config.ts, which derives them from the page. Writing
      // `ws://…:8080` here meant the app could not run over TLS at all: a page served over
      // https may not open a ws: socket, and the browser blocks it as mixed content.
      brokerURL: WS_URL,
      connectHeaders: { token: this.token },

      // Values from ADR-0002. A 10 s heartbeat is comfortably shorter than intermediaries' typical
      // idle timeout (nginx 60 s), so the connection will not be torn down as idle.
      heartbeatIncoming: 10_000,
      heartbeatOutgoing: 10_000,

      // We compute the interval between attempts ourselves (see nextReconnectDelay). A fixed short
      // interval during a server outage would turn every client into a source of load exactly when
      // the server can least bear it.
      reconnectDelay: 1000,
      beforeConnect: () => {
        client.reconnectDelay = this.nextReconnectDelay()
      },

      onConnect: () => {
        this.reconnectAttempt = 0
        // A new connection starts with a clean refusal counter. Without this, a client that had
        // previously wound up to the maximum interval would come back from a disconnect with a
        // one-second delay on its first retry — punished for a situation that no longer exists.
        this.throttleRounds = 0
        this.onConnected()
      },
      onWebSocketClose: () => this.setState('offline'),
      onStompError: (frame) =>
        this.handlers.onError('STOMP_ERROR', frame.headers['message'] ?? 'protocol error'),
    })

    this.client = client
    client.activate()
  }

  private onConnected(): void {
    const client = this.client
    if (!client) return

    this.subscriptions = [
      client.subscribe(`/topic/doc/${this.documentId}/ops`, (message) =>
        this.handlers.onRemoteOps(this.parseOps(message)),
      ),
      client.subscribe(`/topic/doc/${this.documentId}/acks`, (message) => this.onAcks(message)),
      client.subscribe(`/topic/doc/${this.documentId}/presence`, (message) =>
        this.onPresence(message),
      ),
      client.subscribe(`/topic/doc/${this.documentId}/access`, (message) => {
        const change = JSON.parse(message.body) as { userId: string | null }
        this.handlers.onAccessChanged(change.userId)
      }),
      client.subscribe('/user/queue/session', (message) => {
        const info = JSON.parse(message.body) as { replicaId: number }
        this.handlers.onSession(info.replicaId)
      }),
      client.subscribe('/user/queue/sync', (message) => this.onSyncResponse(message)),
      client.subscribe('/user/queue/errors', (message) => {
        const error = JSON.parse(message.body) as {
          code: string
          message: string
          retryAfterMillis?: number
        }
        if (error.code === 'RATE_LIMITED') {
          // The server refused the operation on rate. This is not an error needing the user's
          // attention — the operation is waiting in the unacknowledged buffer and only has to be
          // retried shortly. WITHOUT that retry the signal would be useless and the operation would
          // be lost silently: exactly what the harness found in CP15.
          this.scheduleRetryAfterThrottling(error.retryAfterMillis)
          return
        }
        this.handlers.onError(error.code, error.message)
      }),
    ]

    client.publish({ destination: `/app/doc/${this.documentId}/join`, body: '{}' })
    this.resynchronize()

    // The periodic progress report. Without it the server has no way of knowing how far this
    // replica has got, and stableVersion — and therefore tombstone GC — stands still (ADR-0005).
    // 15 s is shorter than the 60-second threshold for treating a replica as unresponsive, so an
    // active client is never excluded from the computation by silence alone.
    this.progressTimer = window.setInterval(() => this.reportProgress(), 15_000)
  }

  private reportProgress(): void {
    if (!this.client?.connected) return
    if (this.gapDetected) {
      this.gapDetected = false
      // We ask from zero: duplicates are harmless (the idempotence from ADR-0001), and computing
      // the exact starting point would need the very knowledge we are missing.
      this.setState('resyncing')
      this.client.publish({
        destination: `/app/doc/${this.documentId}/sync`,
        body: JSON.stringify({ sinceServerSeq: 0, version: {} }),
      })
    }
    this.client.publish({
      destination: `/app/doc/${this.documentId}/progress`,
      body: JSON.stringify({
        sinceServerSeq: this.lastServerSeq,
        version: Object.fromEntries(this.handlers.versionVector()),
      }),
    })
  }

  /**
   * The interval before the next connection attempt: exponential, with random spread.
   *
   * Exponential, because a server outage usually lasts longer than a second and hammering every
   * second only prolongs it. With spread, because without it every client disconnected by the same
   * event comes back at EXACTLY the same instant — and causes a second outage right after the
   * server came up. A ±30% spread distributes them in time.
   *
   * An upper bound of 30 s: above that the user has time to conclude the application is broken, and
   * the gain for the server is already negligible.
   */
  private throttleRetryTimer: number | null = null

  /**
   * The headroom added to the time the server advertises.
   *
   * The client's and server's clocks are not synchronised and the bucket refills continuously — a
   * retry a millisecond too early is refused a second time. 25 ms is imperceptible to the user and
   * comfortably covers the jitter of a network hop.
   */
  private static readonly THROTTLE_GRACE_MS = 25

  /**
   * The interval used when the server advertised none (an older server without `retryAfterMillis`).
   */
  private static readonly THROTTLE_FALLBACK_MS = 1000

  /**
   * The upper bound on the interval under consecutive refusals.
   *
   * One second — exactly what the pre-CP20 code did. The exponential backoff is meant to reach this
   * value, not exceed it: above it the user starts to see that their text is not getting through.
   */
  private static readonly THROTTLE_MAX_MS = 1000

  /**
   * How many times in a row the server refused on rate.
   *
   * It exists because the server's advertised time alone is NOT ENOUGH, and that was measured. The
   * server advertises the time to ONE token (~17 ms at 60/s), while the client has dozens of
   * operations buffered — so retrying after that time sends the whole buffer, gets another refusal,
   * and repeats indefinitely. The effect measured by the harness: p99 at 50 clients on a
   * 100k-character document rose from 872 ms to 1352 ms, and the refusal count from 1523 to 1614.
   * The client was polling in a loop, and that produced no extra tokens.
   *
   * Exponential backoff solves it without either side guessing: the first retry goes out after the
   * time the server advertised, and every further refusal doubles the interval until the client's
   * rate drops to the bucket's refill rate on its own.
   */
  private throttleRounds = 0

  /**
   * The upper bound on the exponent.
   *
   * Not a micro-optimisation: without it a long session drives `throttleRounds` to a value where
   * `2 ** rounds` is `Infinity`. That alone is still harmless (the minimum with `THROTTLE_MAX_MS`
   * fixes it), but `0 * Infinity` gives `NaN`, and `setTimeout(fn, NaN)` fires IMMEDIATELY — that
   * is, exactly the polling loop this backoff exists to prevent.
   */
  private static readonly THROTTLE_MAX_ROUNDS = 8

  /**
   * Retries the unacknowledged operations after a rate-limit refusal.
   *
   * One timer per run of refusals, not one per operation: when the limit is exceeded, many frames
   * are usually refused at once, and each scheduling its own retry would produce exactly the burst
   * the limit just refused.
   *
   * The wait comes from the SERVER, because only it knows it — a token bucket can say to the
   * millisecond when the next one arrives.
   */
  private scheduleRetryAfterThrottling(retryAfterMillis?: number): void {
    if (this.throttleRetryTimer !== null) return
    const advertised =
      typeof retryAfterMillis === 'number' && retryAfterMillis >= 0
        ? retryAfterMillis + DocumentConnection.THROTTLE_GRACE_MS
        : DocumentConnection.THROTTLE_FALLBACK_MS

    const delay = Math.min(
      advertised * 2 ** this.throttleRounds,
      DocumentConnection.THROTTLE_MAX_MS,
    )
    this.throttleRounds = Math.min(
      this.throttleRounds + 1,
      DocumentConnection.THROTTLE_MAX_ROUNDS,
    )

    this.throttleRetryTimer = window.setTimeout(() => {
      this.throttleRetryTimer = null
      for (const op of this.unacknowledged.values()) {
        this.publishOp(op)
      }
    }, delay)
  }

  private nextReconnectDelay(): number {
    const base = Math.min(1000 * 2 ** this.reconnectAttempt, 30_000)
    this.reconnectAttempt++
    return Math.round(base * (0.7 + Math.random() * 0.6))
  }

  /**
   * Asks for operations the client may not have seen, and retransmits its own unacknowledged ones.
   *
   * The order matters: fetch other people's first, then send our own. The reverse order would also
   * give a correct result (merging is commutative), but this one lets the server reject our
   * operations as duplicates if they did in fact arrive before the disconnect.
   */
  private resynchronize(): void {
    const client = this.client
    if (!client) return

    this.setState('resyncing')
    client.publish({
      destination: `/app/doc/${this.documentId}/sync`,
      body: JSON.stringify({ sinceServerSeq: this.lastServerSeq, version: {} }),
    })

    for (const op of this.unacknowledged.values()) {
      this.publishOp(op)
    }
    this.setState('online')
  }

  private onSyncResponse(message: IMessage): void {
    const payload = JSON.parse(message.body) as { ops: WireOp[]; headServerSeq: number }
    this.handlers.onRemoteOps(payload.ops.map(decodeOp))
    this.lastServerSeq = Math.max(this.lastServerSeq, payload.headServerSeq)
    this.setState('online')
  }

  private onAcks(message: IMessage): void {
    const acks = JSON.parse(message.body) as { r: number; l: number; serverSeq: number }[]
    // Something got through — the rate is back to normal, so the backoff starts over. Without
    // this, one run of refusals would slow the client down for the rest of the session.
    if (acks.length > 0) {
      this.throttleRounds = 0
    }
    for (const ack of acks) {
      // GAP DETECTION.
      //
      // A WebSocket broadcast can fail on a transport error or a buffer overflow, and a client
      // that is CONNECTED has no way of knowing — it only resynchronises on reconnect, which does
      // not happen then. Found by the load harness: at 25+ clients the server held the complete
      // set of operations while some clients were permanently behind and never caught up.
      //
      // An acknowledgement carries the operation's IDENTITY, so not holding it is an unambiguous
      // signal that the broadcast never reached us. The `lastServerSeq` cursor would be no help
      // here — it advances on acknowledgements, so it would look "up to date" despite the gap.
      if (!this.handlers.hasOperation(ack.r, ack.l)) {
        this.gapDetected = true
      }

      const key = `${ack.r}@${ack.l}`
      const acknowledged = this.unacknowledged.get(key)
      this.unacknowledged.delete(key)
      if (acknowledged !== undefined) {
        void offlineStore.forget(this.documentId, acknowledged)
      }
      // The cursor advances on ACKNOWLEDGEMENTS, not broadcasts, because only an acknowledgement
      // carries server_seq. Broadcasts deliberately do not — see Messages.java.
      this.lastServerSeq = Math.max(this.lastServerSeq, ack.serverSeq)
    }
    this.setState(this.state)
  }

  /**
   * Presence: either the full list or a delta for a single participant.
   *
   * Cursor movement is the most frequent event in the system (every keystroke, and ~30 per second
   * with an arrow key held), so the server sends back ONE changed entry rather than every
   * participant. Joins and leaves arrive as a full list, because a leave means "this entry is gone"
   * and cannot be expressed as a delta.
   *
   * The state lives here rather than in the component, because merging deltas is a property of the
   * PROTOCOL — the view layer should receive a finished list and not know the protocol has deltas
   * at all.
   */
  private onPresence(message: IMessage): void {
    const update = JSON.parse(message.body) as { full: boolean; entries: PresenceEntry[] }

    if (update.full) {
      this.presence.clear()
    }
    for (const entry of update.entries) {
      this.presence.set(entry.replicaId, entry)
    }
    this.handlers.onPresence([...this.presence.values()])
  }

  private parseOps(message: IMessage): Op[] {
    const payload = JSON.parse(message.body) as { op: WireOp }[]
    return payload.map((entry) => decodeOp(entry.op))
  }

  /** Sends a local operation. It stays in the buffer until the write is acknowledged. */
  send(op: Op): void {
    this.unacknowledged.set(keyOf(op.id), op)
    // The IndexedDB write is asynchronous and DELIBERATELY does not block the send: the operation
    // should go out immediately, and the buffer's durability is a safeguard against the tab being
    // closed, not a precondition for sending.
    void offlineStore.remember(this.documentId, op)
    this.setState(this.state)
    this.simulator.transmit(op)
  }

  private publishOp(op: Op): void {
    if (!this.client?.connected) {
      // Being disconnected is not an error: the operation has already been applied locally and is
      // waiting in the buffer. It will go out on reconnect.
      return
    }
    // The trace context travels in a FRAME HEADER, not in the operation's body. That keeps the
    // operation's wire format independent of whether tracing is enabled (ADR-0002: STOMP headers
    // are the only place for per-message metadata).
    traceOperation(
      'edit.submit',
      { 'collabedit.op.type': op.kind, 'collabedit.op.lamport': op.id.lamport },
      (traceparent) => {
        this.client?.publish({
          destination: `/app/doc/${this.documentId}/op`,
          body: JSON.stringify({ op: encodeOp(op) }),
          headers: traceparent === undefined ? {} : { traceparent },
        })
      },
    )
  }

  sendPresence(cursor: Id | null, anchor: Id | null, typing: boolean): void {
    if (!this.client?.connected) return
    this.client.publish({
      destination: `/app/doc/${this.documentId}/presence`,
      body: JSON.stringify({
        cursorReplicaId: cursor?.replicaId ?? null,
        cursorLamport: cursor?.lamport ?? null,
        anchorReplicaId: anchor?.replicaId ?? null,
        anchorLamport: anchor?.lamport ?? null,
        typing,
      }),
    })
  }

  get pendingCount(): number {
    return this.unacknowledged.size + this.simulator.heldCount
  }

  private setState(state: ConnectionState): void {
    this.state = state
    this.handlers.onStateChange(state, this.pendingCount)
  }

  /**
   * Abandons the buffer after a refused merge (ADR-0005).
   *
   * Called only after `RESYNC_REQUIRED`. The content of those operations is rescued separately at
   * that point, as a recovered draft — the buffer is cleared only afterwards, so they do not retry
   * forever.
   */
  async abandonBuffer(): Promise<void> {
    this.unacknowledged.clear()
    await offlineStore.clear(this.documentId)
    this.setState(this.state)
  }

  disconnect(): void {
    window.removeEventListener('offline', this.handleBrowserOffline)
    window.removeEventListener('online', this.handleBrowserOnline)
    if (this.progressTimer !== null) {
      window.clearInterval(this.progressTimer)
      this.progressTimer = null
    }
    // The rate-limit retry is also scheduled on the browser window and also has to disappear with
    // the connection. Today the guard in publishOp would stop it, but relying on that means the
    // first change that queues operations while disconnected would start pushing them into a
    // connection that no longer exists.
    if (this.throttleRetryTimer !== null) {
      window.clearTimeout(this.throttleRetryTimer)
      this.throttleRetryTimer = null
    }
    for (const subscription of this.subscriptions) subscription.unsubscribe()
    this.subscriptions = []
    void this.client?.deactivate()
    this.client = null
  }
}

function keyOf(value: Id): string {
  return `${value.replicaId}@${value.lamport}`
}
