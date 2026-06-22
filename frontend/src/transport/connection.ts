import {
  Client,
  ReconnectionTimeMode,
  type IMessage,
  type StompSubscription,
} from '@stomp/stompjs'
import { WS_URL } from '../config'
import { decodeOp, encodeOp, type WireOp } from '../crdt/codec'
import type { Id, Op } from '../crdt/types'
import { traceOperation } from '../observability/tracing'
import { encodeCursorAnchor, type CursorAnchor } from './cursorAnchor'
import { offlineStore } from './offlineStore'
import { NetworkSimulator } from './simulator'

/**
 * The connection to the server: STOMP over WebSocket.
 *
 * Three things this class is responsible for, worth naming:
 *
 * 1. **The buffer of unacknowledged operations.** An operation leaves the buffer only once an
 *    acknowledgement carrying `serverSeq` arrives, that is, after a DURABLE WRITE and not after the
 *    broadcast. Releasing it earlier would mean an instance crash between broadcast and write left
 *    an operation others can see and nobody can reproduce.
 *
 * 2. **The resynchronisation cursor.** `lastServerSeq` says how far the client definitely got.
 *    After reconnecting it asks for everything above that. Caution is cheap: duplicates sent back
 *    are harmless (the idempotence from the CRDT decision), a lost operation is not.
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
  /**
   * Whether the replica holds a CHARACTER with the given identifier — the fallback for gap
   * detection, exact for inserts and only for inserts. See `RgaDocument.contains`.
   */
  hasOperation: (replicaId: number, lamport: number) => boolean
  /** Whether the replica has already integrated this whole operation — see `RgaDocument.holds`. */
  hasApplied: (op: Op) => boolean
  onRemoteOps: (ops: Op[]) => void
  /** The replica's current version vector — reported to the server for GC. */
  versionVector: () => Map<number, number>
  onPresence: (entries: PresenceEntry[], complete: boolean) => void
  onStateChange: (state: ConnectionState, pendingCount: number) => void
  onError: (code: string, message: string) => void
  /**
   * Access to this document changed (CP21).
   *
   * The frame carries nothing, and that is the point: it goes to the document topic, so every
   * participant hears it, and "who was granted what" is precisely what the access endpoints
   * refuse to hand out without permission to manage access. All a client learns is that it
   * should ask the server about ITS OWN role.
   */
  onAccessChanged: () => void
}

/** How many operation identities the gap detector remembers — see `DocumentConnection.handled`. */
const HANDLED_WINDOW = 5000

export class DocumentConnection {
  private client: Client | null = null
  private subscriptions: StompSubscription[] = []

  /** Operations sent but not yet acknowledged as written. Key: replicaId@lamport. */
  private readonly unacknowledged = new Map<string, Op>()
  private lastServerSeq = 0
  /** Participants by replicaId — merged from deltas, see onPresence. */
  private readonly presence = new Map<number, PresenceEntry>()
  private state: ConnectionState = 'connecting'
  private gapDetected = false

  /**
   * The identities of operations this client has actually HANDLED, newest last.
   *
   * Gap detection asks "did this operation's broadcast reach me?" and used to answer it by asking
   * the CRDT whether it holds a CHARACTER with that identity. For an insert that is the same
   * question. For a delete or a mark it is not, and the answer was always no: an operation's own
   * identity is drawn from the same Lamport clock as the characters but never names one, so
   * `contains` is false for every delete and every mark the client has perfectly well applied.
   *
   * The consequence was not subtle. Every acknowledgement is broadcast to everybody, so ONE
   * backspace by anybody — including your own — set `gapDetected`, and `reportProgress` then asked
   * for a full resynchronisation FROM ZERO at the next 15 s tick. On a busy document that is the
   * whole operation log, to every client, every fifteen seconds, for ever. The repair mechanism
   * was its own permanent load.
   *
   * A window rather than a set that grows: an acknowledgement is emitted once, immediately after
   * the write, so the only identities ever asked about are recent ones. Everything older is
   * answered by the structural check, which stays as the second line and is exact for inserts.
   */
  private readonly handled = new Set<string>()
  private readonly handledOrder: string[] = []

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
  private storageFailureReported = false
  private disposed = false

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
    const live: Op[] = []
    for (const op of stored) {
      // Already in the state the server has just handed us, so it reached the LOG before the tab
      // closed and only its acknowledgement went missing. That is not a rare corner: the
      // acknowledgement is broadcast once, at the write, and this replica is rebuilt from a
      // snapshot whose cursor can already be past the operation — in which case the resync that
      // normally releases it (`onSyncResponse`) asks for a range that no longer contains it, and
      // the retransmission is correctly refused as a duplicate, so no acknowledgement is ever
      // produced again. Kept in the buffer it would count as pending for the whole session, be
      // resent on every reconnect, and be restored from here on every future page load.
      //
      // Safe because this runs BEFORE `connect`: the model holds exactly what the SERVER gave
      // us, and nothing this client typed and failed to send is in it.
      if (this.handlers.hasApplied(op)) {
        void offlineStore
          .forget(this.documentId, op)
          .catch((error: unknown) => this.reportStorageFailure(error))
        continue
      }
      this.unacknowledged.set(keyOf(op.id), op)
      live.push(op)
    }
    this.setState(this.state)
    return live
  }

  connect(): void {
    if (this.disposed) return
    // The browser knows about a lost connection IMMEDIATELY, whereas the heartbeat takes a dozen
    // or so seconds to expire (a 10 s interval, 2.5× tolerance). For that whole time the
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

      // Values from the transport decision. A 10 s heartbeat is comfortably shorter than intermediaries' typical
      // idle timeout (nginx 60 s), so the connection will not be torn down as idle.
      heartbeatIncoming: 10_000,
      heartbeatOutgoing: 10_000,

      // Truncated exponential backoff with a per-session jitter. A fixed short interval during a
      // server outage would turn every client into a source of load exactly when the server can
      // least bear it.
      //
      // This used to assign `client.reconnectDelay` from `beforeConnect`, which did nothing:
      // @stomp/stompjs 7.x schedules from a PRIVATE `_nextReconnectDelay`, refreshed from
      // `reconnectDelay` only in `activate` and its own `onConnect` — neither of which runs
      // during an outage. Every retry therefore reused one frozen value and the documented
      // 1→2→4→…→30 s curve never happened. Driving the library's own exponential mode instead
      // uses only public API and actually takes effect.
      reconnectDelay: this.nextReconnectDelay(),
      reconnectTimeMode: ReconnectionTimeMode.EXPONENTIAL,
      maxReconnectDelay: 30_000,

      onConnect: () => {
        // Re-seeds the library's private schedule for the NEXT outage, and re-rolls the jitter so
        // clients that reconnected together do not stay in lockstep.
        client.reconnectDelay = this.nextReconnectDelay()
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
      client.subscribe(`/topic/doc/${this.documentId}/access`, () => {
        this.handlers.onAccessChanged()
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
    // replica has got, and stableVersion — and therefore tombstone GC — stands still.
    // 15 s is shorter than the 60-second threshold for treating a replica as unresponsive, so an
    // active client is never excluded from the computation by silence alone.
    //
    // Cleared first. onConnected runs on EVERY (re)connection, and assigning over a live handle
    // left the previous interval running with nothing holding its id — so after N reconnects there
    // were N of them, disconnect could only clear the last, and with gapDetected set each one
    // could fire its own full resynchronisation from zero in the same window.
    this.clearProgressTimer()
    this.progressTimer = window.setInterval(() => this.reportProgress(), 15_000)
  }

  private reportProgress(): void {
    if (!this.client?.connected) return
    if (this.gapDetected) {
      this.gapDetected = false
      // We ask from zero: duplicates are harmless (the idempotence from the CRDT decision), and computing
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

  /**
   * The FIRST delay of a reconnect sequence, jittered.
   *
   * The doubling is the library's, driven by `reconnectTimeMode: EXPONENTIAL` and bounded by
   * `maxReconnectDelay`; this only supplies the seed. Computing the whole curve here is what the
   * previous version tried to do, and it never took effect (see `connect`).
   *
   * The ±30% spread is the part that matters and cannot be delegated: without it, every client
   * disconnected by the same server restart comes back on the same schedule, and the retry storm
   * arrives all at once.
   */
  private nextReconnectDelay(): number {
    return Math.round(1000 * (0.7 + Math.random() * 0.6))
  }

  /**
   * The durable buffer failed.
   *
   * Reported once rather than per operation: a browser that refuses IndexedDB (a private window,
   * blocked storage) refuses every call, and the user needs to know the guarantee is gone, not to
   * be told so on every keystroke.
   */
  private reportStorageFailure(error: unknown): void {
    if (this.storageFailureReported) return
    this.storageFailureReported = true
    this.handlers.onError(
      'OFFLINE_BUFFER_UNAVAILABLE',
      'This browser is not letting the editor save a local backup, so unsent changes will be '
        + 'lost if the tab closes. Editing still works.',
    )
    console.warn('the offline buffer is unavailable', error)
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
    // NOTHING sets 'online' here, and that is the fix rather than an omission.
    //
    // This line used to read `this.setState('online')`, two statements after `setState('resyncing')`
    // and in the same synchronous handler. React batches state updates inside an event handler, so
    // only the last one ever reached a render: the 'resyncing' state was set, discarded, and never
    // drawn. That made `onSyncResponse`'s `if (payload.last !== false) this.setState('online')` —
    // the line actually written to own this transition, with a comment explaining why it must wait
    // for the final chunk — dead on the ordinary path.
    //
    // What the user saw: `SaveIndicator` showing **"Saved"** while the backlog was still arriving,
    // after a reconnect or on first opening a busy document. That is precisely the lie
    // `SaveIndicator`'s own header says its ordering of conditions exists to prevent — the
    // indicator is the only thing telling somebody whether their work is safe, so it may not
    // report safety it has not got.
    //
    // The transition now belongs to the answer, which is the only thing that knows the
    // resynchronisation finished (CP28 split a large one across several frames).
  }

  private onSyncResponse(message: IMessage): void {
    const payload = JSON.parse(message.body) as {
      ops: WireOp[]
      headServerSeq: number
      last?: boolean
    }
    const ops = payload.ops.map(decodeOp)
    this.remember(ops)
    this.handlers.onRemoteOps(ops)

    // An operation that comes BACK in a sync response is in the operation log, and being in the
    // log is exactly what an acknowledgement asserts. Clearing the buffer on that closes a gap
    // between the two signals: an acknowledgement is broadcast once, when the batch is written,
    // and a client that was disconnected at that moment never hears it. Its retransmission after
    // reconnect is then correctly refused as a duplicate — the store filters duplicates before
    // assigning sequence numbers, so no second acknowledgement is ever produced — and without
    // this the operation stayed in the buffer for the rest of the session, counted as pending,
    // resent on every reconnect, and kept alive in the offline store.
    for (const op of ops) {
      const key = keyOf(op.id)
      const mine = this.unacknowledged.get(key)
      if (mine !== undefined) {
        this.unacknowledged.delete(key)
        void offlineStore
          .forget(this.documentId, mine)
          .catch((error: unknown) => this.reportStorageFailure(error))
      }
    }

    // ONLY on the final chunk. A large resynchronisation arrives split across several frames
    // (CP28), every one of them carrying the same head — so advancing here on each one meant that
    // after chunk 1 of 5 the client believed it held everything up to the head. Losing any later
    // chunk then left it permanently behind with nothing able to notice: the only gap detector
    // fires on an acknowledgement for an operation the client does not have, and these operations
    // were acknowledged long ago and are never acknowledged again.
    //
    // `last !== false` rather than `last === true`: an older server does not send the field, and
    // the safe reading of a missing flag is the behaviour that was correct for a single chunk.
    if (payload.last !== false) {
      this.lastServerSeq = Math.max(this.lastServerSeq, payload.headServerSeq)
      this.setState('online')
    }
  }

  private onAcks(message: IMessage): void {
    const acks = JSON.parse(message.body) as { r: number; l: number; serverSeq: number }[]
    // Whether one of OUR OWN operations got through. This used to be `acks.length > 0`, but
    // acknowledgements are broadcast to a shared topic — this client subscribes to it precisely so
    // it can do the gap detection below on everybody's — so "something got through" was true of
    // some other client. Under load, which is the only condition the rate limiter fires in, ack
    // batches arrive continuously, so the backoff was reset before nearly every retry and the
    // doubling described in scheduleRetryAfterThrottling never advanced past its first step.
    let mine = false
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
      // The window first, the structure second. The window is exact for every operation KIND —
      // it records what arrived, rather than inferring it from what the text now looks like — and
      // the structural check covers anything that has aged out of it.
      const identity = keyOf({ replicaId: ack.r, lamport: ack.l })
      if (!this.handled.has(identity) && !this.handlers.hasOperation(ack.r, ack.l)) {
        this.gapDetected = true
      }

      // Built through keyOf rather than by hand: the format lives in one place, and if it ever
      // changed, acknowledgements would silently stop matching the buffer and every operation
      // would be retried for ever.
      const key = keyOf({ replicaId: ack.r, lamport: ack.l })
      const acknowledged = this.unacknowledged.get(key)
      this.unacknowledged.delete(key)
      if (acknowledged !== undefined) {
        mine = true
        void offlineStore
          .forget(this.documentId, acknowledged)
          .catch((error: unknown) => this.reportStorageFailure(error))
      }
      // The cursor advances on ACKNOWLEDGEMENTS, not broadcasts, because only an acknowledgement
      // carries server_seq. Broadcasts deliberately do not — see Messages.java.
      this.lastServerSeq = Math.max(this.lastServerSeq, ack.serverSeq)
    }
    if (mine) {
      this.throttleRounds = 0
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
    const update = JSON.parse(message.body) as {
      full: boolean
      entries: PresenceEntry[]
      complete?: boolean
    }

    if (update.full) {
      this.presence.clear()
    }
    for (const entry of update.entries) {
      this.presence.set(entry.replicaId, entry)
    }
    // `complete === false` means the server could not reach the cluster channel, so people
    // connected to other instances may be missing from this list (CP26). Defaulting to true
    // keeps an older server working — and defaulting the other way would put a warning on every
    // single-instance deployment, where the list is always complete.
    this.handlers.onPresence([...this.presence.values()], update.complete ?? true)
  }

  private parseOps(message: IMessage): Op[] {
    const payload = JSON.parse(message.body) as { op: WireOp }[]
    const ops = payload.map((entry) => decodeOp(entry.op))
    this.remember(ops)
    return ops
  }

  /**
   * Records that these operations have reached this client — see {@link handled}.
   *
   * The bound is on COUNT rather than on age, because it is enforced on a path that runs per
   * operation and reading the clock there is the kind of cost that only shows up under load. 5000
   * identities is minutes of a busy document's traffic and a few hundred kilobytes at worst;
   * acknowledgements are seconds old.
   */
  private remember(ops: readonly Op[]): void {
    for (const op of ops) {
      const key = keyOf(op.id)
      if (this.handled.has(key)) continue
      this.handled.add(key)
      this.handledOrder.push(key)
    }
    while (this.handledOrder.length > HANDLED_WINDOW) {
      this.handled.delete(this.handledOrder.shift() as string)
    }
  }

  /** Sends a local operation. It stays in the buffer until the write is acknowledged. */
  send(op: Op): void {
    this.unacknowledged.set(keyOf(op.id), op)
    // Our own operations too: they are applied locally before they are sent, and their
    // acknowledgement comes back through the same shared topic as everybody else's. Without this
    // line the client flagged a gap on its own backspaces.
    this.remember([op])
    // The IndexedDB write is asynchronous and DELIBERATELY does not block the send: the operation
    // should go out immediately, and the buffer's durability is a safeguard against the tab being
    // closed, not a precondition for sending.
    void offlineStore
      .remember(this.documentId, op)
      .catch((error: unknown) => this.reportStorageFailure(error))
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
    // operation's wire format independent of whether tracing is enabled (STOMP headers
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

  /**
   * Reports where this user's caret and selection are.
   *
   * The two places arrive as `CursorAnchor`s — a character AND a side — and go out through
   * `encodeCursorAnchor`, which packs the side into the lamport field. The field names on the wire
   * are unchanged, because `Messages.ClientPresence` is shared with the server; the reasoning for
   * the packing, and the position-0 bug that made it necessary, is all in `cursorAnchor.ts`.
   */
  sendPresence(cursor: CursorAnchor | null, anchor: CursorAnchor | null, typing: boolean): void {
    if (!this.client?.connected) return
    const head = encodeCursorAnchor(cursor)
    const tail = encodeCursorAnchor(anchor)
    this.client.publish({
      destination: `/app/doc/${this.documentId}/presence`,
      body: JSON.stringify({
        cursorReplicaId: head?.replicaId ?? null,
        cursorLamport: head?.lamport ?? null,
        anchorReplicaId: tail?.replicaId ?? null,
        anchorLamport: tail?.lamport ?? null,
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
   * Abandons the buffer after a refused merge.
   *
   * Called only after `RESYNC_REQUIRED`. The content of those operations is rescued separately at
   * that point, as a recovered draft — the buffer is cleared only afterwards, so they do not retry
   * forever.
   */
  async abandonBuffer(): Promise<void> {
    this.unacknowledged.clear()
    this.handled.clear()
    this.handledOrder.length = 0
    await offlineStore.clear(this.documentId)
    this.setState(this.state)
  }

  private clearProgressTimer(): void {
    if (this.progressTimer !== null) {
      window.clearInterval(this.progressTimer)
      this.progressTimer = null
    }
  }

  disconnect(): void {
    // Set before anything else: `connect` is reachable from a promise that was already in
    // flight when the effect tore down (restoreOfflineBuffer resolves after the cleanup ran), and
    // without this flag it would build a live STOMP client nothing holds a reference to — with a
    // 15 s interval and two window listeners that nothing can ever remove.
    this.disposed = true
    window.removeEventListener('offline', this.handleBrowserOffline)
    window.removeEventListener('online', this.handleBrowserOnline)
    this.clearProgressTimer()
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
