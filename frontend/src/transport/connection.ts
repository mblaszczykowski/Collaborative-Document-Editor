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
  onSession: (replicaId: number) => void
  hasOperation: (replicaId: number, lamport: number) => boolean
  hasApplied: (op: Op) => boolean
  onRemoteOps: (ops: Op[]) => void
  versionVector: () => Map<number, number>
  onPresence: (entries: PresenceEntry[], complete: boolean) => void
  onStateChange: (state: ConnectionState, pendingCount: number) => void
  onError: (code: string, message: string) => void
  onAccessChanged: () => void
}

const HANDLED_WINDOW = 5000

export class DocumentConnection {
  private client: Client | null = null
  private subscriptions: StompSubscription[] = []

  private readonly unacknowledged = new Map<string, Op>()
  private lastServerSeq = 0
  private readonly presence = new Map<number, PresenceEntry>()
  private state: ConnectionState = 'connecting'
  private gapDetected = false

  private readonly handled = new Set<string>()
  private readonly handledOrder: string[] = []

  private readonly handleBrowserOffline = () => this.setState('offline')
  private readonly handleBrowserOnline = () => {
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

  async restoreOfflineBuffer(): Promise<Op[]> {
    const stored = await offlineStore.pending(this.documentId)
    const live: Op[] = []
    for (const op of stored) {
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
    window.addEventListener('offline', this.handleBrowserOffline)
    window.addEventListener('online', this.handleBrowserOnline)

    const client = new Client({
      brokerURL: WS_URL,
      connectHeaders: { token: this.token },

      heartbeatIncoming: 10_000,
      heartbeatOutgoing: 10_000,

      reconnectDelay: this.nextReconnectDelay(),
      reconnectTimeMode: ReconnectionTimeMode.EXPONENTIAL,
      maxReconnectDelay: 30_000,

      onConnect: () => {
        client.reconnectDelay = this.nextReconnectDelay()
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
          this.scheduleRetryAfterThrottling(error.retryAfterMillis)
          return
        }
        this.handlers.onError(error.code, error.message)
      }),
    ]

    client.publish({ destination: `/app/doc/${this.documentId}/join`, body: '{}' })
    this.resynchronize()

    this.clearProgressTimer()
    this.progressTimer = window.setInterval(() => this.reportProgress(), 15_000)
  }

  private reportProgress(): void {
    if (!this.client?.connected) return
    if (this.gapDetected) {
      this.gapDetected = false
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

  private throttleRetryTimer: number | null = null

  private static readonly THROTTLE_GRACE_MS = 25

  private static readonly THROTTLE_FALLBACK_MS = 1000

  private static readonly THROTTLE_MAX_MS = 1000

  private throttleRounds = 0

  private static readonly THROTTLE_MAX_ROUNDS = 8

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
    return Math.round(1000 * (0.7 + Math.random() * 0.6))
  }

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

    if (payload.last !== false) {
      this.lastServerSeq = Math.max(this.lastServerSeq, payload.headServerSeq)
      this.setState('online')
    }
  }

  private onAcks(message: IMessage): void {
    const acks = JSON.parse(message.body) as { r: number; l: number; serverSeq: number }[]
    let mine = false
    for (const ack of acks) {
      const key = keyOf({ replicaId: ack.r, lamport: ack.l })
      if (!this.handled.has(key) && !this.handlers.hasOperation(ack.r, ack.l)) {
        this.gapDetected = true
      }

      const acknowledged = this.unacknowledged.get(key)
      this.unacknowledged.delete(key)
      if (acknowledged !== undefined) {
        mine = true
        void offlineStore
          .forget(this.documentId, acknowledged)
          .catch((error: unknown) => this.reportStorageFailure(error))
      }
      this.lastServerSeq = Math.max(this.lastServerSeq, ack.serverSeq)
    }
    if (mine) {
      this.throttleRounds = 0
    }
    this.setState(this.state)
  }

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
    this.handlers.onPresence([...this.presence.values()], update.complete ?? true)
  }

  private parseOps(message: IMessage): Op[] {
    const payload = JSON.parse(message.body) as { op: WireOp }[]
    const ops = payload.map((entry) => decodeOp(entry.op))
    this.remember(ops)
    return ops
  }

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

  send(op: Op): void {
    this.unacknowledged.set(keyOf(op.id), op)
    this.remember([op])
    void offlineStore
      .remember(this.documentId, op)
      .catch((error: unknown) => this.reportStorageFailure(error))
    this.setState(this.state)
    this.simulator.transmit(op)
  }

  private publishOp(op: Op): void {
    if (!this.client?.connected) {
      return
    }
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
    return this.unacknowledged.size
  }

  private setState(state: ConnectionState): void {
    this.state = state
    this.handlers.onStateChange(state, this.pendingCount)
  }

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
    this.disposed = true
    window.removeEventListener('offline', this.handleBrowserOffline)
    window.removeEventListener('online', this.handleBrowserOnline)
    this.clearProgressTimer()
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
