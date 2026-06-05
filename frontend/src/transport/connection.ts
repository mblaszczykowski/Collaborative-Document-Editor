import { Client, type IMessage, type StompSubscription } from '@stomp/stompjs'
import { decodeOp, encodeOp, type WireOp } from '../crdt/codec'
import type { Id, Op } from '../crdt/types'
import { NetworkSimulator } from './simulator'

/**
 * Połączenie z serwerem: STOMP nad WebSocketem (ADR-0002).
 *
 * Trzy rzeczy, za które ta klasa odpowiada i które warto nazwać:
 *
 * 1. **Bufor niezapotwierdzonych operacji.** Operacja opuszcza bufor dopiero po odebraniu
 *    potwierdzenia z `serverSeq`, czyli po TRWAŁYM ZAPISIE, a nie po rozesłaniu. Gdyby
 *    zwalniać ją wcześniej, pad instancji między rozesłaniem a zapisem zostawiłby operację,
 *    którą inni widzą, a której nikt nie potrafi odtworzyć (ADR-0003).
 *
 * 2. **Kursor resynchronizacji.** `lastServerSeq` mówi, dokąd klient na pewno doszedł.
 *    Po ponownym połączeniu prosi o wszystko powyżej. Ostrożność jest tania: dosłane
 *    duplikaty są nieszkodliwe (idempotencja z ADR-0001), a zgubiona operacja nie.
 *
 * 3. **Wpięcie symulatora sieci** na ścieżce wychodzącej, żeby demo i testy chaosu
 *    korzystały z tego samego mechanizmu.
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
  /** Identyfikator repliki nadany przez serwer — przychodzi zanim padnie pierwsza operacja. */
  onSession: (replicaId: number) => void
  onRemoteOps: (ops: Op[]) => void
  onPresence: (entries: PresenceEntry[]) => void
  onStateChange: (state: ConnectionState, pendingCount: number) => void
  onError: (code: string, message: string) => void
}

export class DocumentConnection {
  private client: Client | null = null
  private subscriptions: StompSubscription[] = []

  /** Operacje wysłane, ale jeszcze niepotwierdzone zapisem. Klucz: replicaId@lamport. */
  private readonly unacknowledged = new Map<string, Op>()
  private lastServerSeq = 0
  private state: ConnectionState = 'connecting'

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

  connect(): void {
    const client = new Client({
      brokerURL: `ws://${window.location.hostname}:8080/ws`,
      connectHeaders: { token: this.token },

      // Wartości z ADR-0002. Heartbeat 10 s jest wyraźnie krótszy niż typowy idle timeout
      // pośredników (nginx 60 s), więc połączenie nie zostanie zerwane jako bezczynne.
      heartbeatIncoming: 10_000,
      heartbeatOutgoing: 10_000,

      // Ponowne łączenie z narastającym odstępem. Stały krótki odstęp przy awarii serwera
      // zamieniłby wszystkich klientów w źródło obciążenia dokładnie wtedy, gdy serwer
      // najmniej może je znieść.
      reconnectDelay: 1000,

      onConnect: () => this.onConnected(),
      onWebSocketClose: () => this.setState('offline'),
      onStompError: (frame) =>
        this.handlers.onError('STOMP_ERROR', frame.headers['message'] ?? 'błąd protokołu'),
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
        this.handlers.onPresence(JSON.parse(message.body) as PresenceEntry[]),
      ),
      client.subscribe('/user/queue/session', (message) => {
        const info = JSON.parse(message.body) as { replicaId: number }
        this.handlers.onSession(info.replicaId)
      }),
      client.subscribe('/user/queue/sync', (message) => this.onSyncResponse(message)),
      client.subscribe('/user/queue/errors', (message) => {
        const error = JSON.parse(message.body) as { code: string; message: string }
        this.handlers.onError(error.code, error.message)
      }),
    ]

    client.publish({ destination: `/app/doc/${this.documentId}/join`, body: '{}' })
    this.resynchronize()
  }

  /**
   * Prosi o operacje, których klient mógł nie widzieć, i retransmituje własne
   * niepotwierdzone.
   *
   * Kolejność jest istotna: najpierw pobieramy cudze, potem wysyłamy swoje. Odwrotna
   * kolejność też dałaby poprawny wynik (scalanie jest przemienne), ale ta pozwala serwerowi
   * odrzucić nasze operacje jako duplikaty, jeśli w rzeczywistości doszły przed rozłączeniem.
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
    for (const ack of acks) {
      this.unacknowledged.delete(`${ack.r}@${ack.l}`)
      // Kursor przesuwamy na podstawie POTWIERDZEŃ, nie rozgłoszeń, bo tylko potwierdzenie
      // niesie server_seq. Rozgłoszenia z zasady go nie mają — patrz Messages.java.
      this.lastServerSeq = Math.max(this.lastServerSeq, ack.serverSeq)
    }
    this.setState(this.state)
  }

  private parseOps(message: IMessage): Op[] {
    const payload = JSON.parse(message.body) as { op: WireOp }[]
    return payload.map((entry) => decodeOp(entry.op))
  }

  /** Wysyła operację lokalną. Trafia do bufora do czasu potwierdzenia zapisu. */
  send(op: Op): void {
    this.unacknowledged.set(keyOf(op.id), op)
    this.setState(this.state)
    this.simulator.transmit(op)
  }

  private publishOp(op: Op): void {
    if (!this.client?.connected) {
      // Brak połączenia nie jest błędem: operacja została już zastosowana lokalnie
      // i czeka w buforze. Wyśle się przy ponownym połączeniu.
      return
    }
    this.client.publish({
      destination: `/app/doc/${this.documentId}/op`,
      body: JSON.stringify({ op: encodeOp(op) }),
    })
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

  disconnect(): void {
    for (const subscription of this.subscriptions) subscription.unsubscribe()
    this.subscriptions = []
    void this.client?.deactivate()
    this.client = null
  }
}

function keyOf(value: Id): string {
  return `${value.replicaId}@${value.lamport}`
}
