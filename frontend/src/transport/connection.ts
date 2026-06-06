import { Client, type IMessage, type StompSubscription } from '@stomp/stompjs'
import { decodeOp, encodeOp, type WireOp } from '../crdt/codec'
import type { Id, Op } from '../crdt/types'
import { traceOperation } from '../observability/tracing'
import { offlineStore } from './offlineStore'
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
  /** Czy replika ma już operację o podanym identyfikatorze — do wykrywania luk. */
  hasOperation: (replicaId: number, lamport: number) => boolean
  onRemoteOps: (ops: Op[]) => void
  /** Aktualny version vector repliki — raportowany serwerowi na potrzeby GC (ADR-0005). */
  versionVector: () => Map<number, number>
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
  private reconnectAttempt = 0
  private gapDetected = false

  private readonly handleBrowserOffline = () => this.setState('offline')
  private readonly handleBrowserOnline = () => {
    // Nie ustawiamy tu „online" — połączenie STOMP musi się dopiero podnieść.
    // Deklarowanie gotowości przed faktycznym połączeniem byłoby tym samym błędem
    // co pokazywanie „Zapisano" po utracie sieci, tylko w drugą stronę.
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
   * Wczytuje operacje niepotwierdzone z poprzedniej sesji przeglądarki.
   *
   * Wywoływane przed połączeniem: operacje z zamkniętej karty muszą trafić do bufora,
   * zanim ruszy resynchronizacja, inaczej pierwszy przebieg ich nie wyśle.
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
    // Przeglądarka wie o utracie łączności NATYCHMIAST, a wygaśnięcie heartbeatu zajmuje
    // kilkanaście sekund (ADR-0002: 10 s interwału, 2,5× tolerancji). Przez ten czas
    // wskaźnik pokazywałby „Zapisano", mimo że nic już nie dociera do serwera — czyli
    // kłamałby dokładnie w tej jednej sytuacji, w której użytkownik musi wiedzieć prawdę.
    window.addEventListener('offline', this.handleBrowserOffline)
    window.addEventListener('online', this.handleBrowserOnline)

    const client = new Client({
      brokerURL: `ws://${window.location.hostname}:8080/ws`,
      connectHeaders: { token: this.token },

      // Wartości z ADR-0002. Heartbeat 10 s jest wyraźnie krótszy niż typowy idle timeout
      // pośredników (nginx 60 s), więc połączenie nie zostanie zerwane jako bezczynne.
      heartbeatIncoming: 10_000,
      heartbeatOutgoing: 10_000,

      // Odstęp między próbami wyliczamy sami (patrz nextReconnectDelay). Stały krótki
      // odstęp przy awarii serwera zamieniłby wszystkich klientów w źródło obciążenia
      // dokładnie wtedy, gdy serwer najmniej może je znieść.
      reconnectDelay: 1000,
      beforeConnect: () => {
        client.reconnectDelay = this.nextReconnectDelay()
      },

      onConnect: () => {
        this.reconnectAttempt = 0
        this.onConnected()
      },
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

    // Okresowy raport postępu. Bez niego serwer nie ma skąd wiedzieć, dokąd doszła ta
    // replika, a stableVersion — a więc i GC tombstone'ów — stoi w miejscu (ADR-0005).
    // 15 s jest krótsze niż 60-sekundowy próg uznania repliki za niereagującą, więc
    // aktywny klient nigdy nie zostanie wykluczony z rachunku przez samo milczenie.
    this.progressTimer = window.setInterval(() => this.reportProgress(), 15_000)
  }

  private reportProgress(): void {
    if (!this.client?.connected) return
    if (this.gapDetected) {
      this.gapDetected = false
      // Prosimy od zera: duplikaty są nieszkodliwe (idempotencja z ADR-0001), a wyliczanie
      // dokładnego punktu startu wymagałoby wiedzy, której właśnie nam brakuje.
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
   * Odstęp do kolejnej próby połączenia: wykładniczy, z losowym rozrzutem.
   *
   * Wykładniczy, bo awaria serwera zwykle trwa dłużej niż sekundę i dobijanie się co
   * sekundę tylko ją przedłuża. Z rozrzutem, bo bez niego wszyscy klienci rozłączeni
   * tym samym zdarzeniem wracają DOKŁADNIE w tej samej chwili — i sami wywołują drugą
   * awarię tuż po tym, jak serwer wstał. Rozrzut ±30% rozkłada ich w czasie.
   *
   * Górna granica 30 s: powyżej użytkownik zdąży uznać, że aplikacja jest zepsuta,
   * a zysk dla serwera jest już znikomy.
   */
  private nextReconnectDelay(): number {
    const base = Math.min(1000 * 2 ** this.reconnectAttempt, 30_000)
    this.reconnectAttempt++
    return Math.round(base * (0.7 + Math.random() * 0.6))
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
      // WYKRYWANIE LUK.
      //
      // Rozgłoszenie przez WebSocket potrafi zawieść przy błędzie transportu albo
      // przepełnieniu bufora, a klient, który jest POŁĄCZONY, nie ma skąd o tym wiedzieć —
      // resynchronizuje się tylko przy ponownym połączeniu, którego wtedy nie ma. Wykryte
      // harnessem obciążeniowym: przy 25+ klientach serwer miał komplet operacji, a część
      // klientów była trwale w tyle i nigdy nie nadrabiała.
      //
      // Potwierdzenie niesie TOŻSAMOŚĆ operacji, więc jej brak u nas jest jednoznacznym
      // sygnałem, że rozgłoszenie do nas nie dotarło. Kursor `lastServerSeq` do niczego
      // by się tu nie przydał — rośnie od potwierdzeń, więc byłby „na bieżąco" mimo luki.
      if (!this.handlers.hasOperation(ack.r, ack.l)) {
        this.gapDetected = true
      }

      const key = `${ack.r}@${ack.l}`
      const acknowledged = this.unacknowledged.get(key)
      this.unacknowledged.delete(key)
      if (acknowledged !== undefined) {
        void offlineStore.forget(this.documentId, acknowledged)
      }
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
    // Zapis do IndexedDB jest asynchroniczny i CELOWO nie blokuje wysyłki: operacja ma
    // polecieć natychmiast, a trwałość bufora jest zabezpieczeniem na wypadek zamknięcia
    // karty, nie warunkiem wysłania.
    void offlineStore.remember(this.documentId, op)
    this.setState(this.state)
    this.simulator.transmit(op)
  }

  private publishOp(op: Op): void {
    if (!this.client?.connected) {
      // Brak połączenia nie jest błędem: operacja została już zastosowana lokalnie
      // i czeka w buforze. Wyśle się przy ponownym połączeniu.
      return
    }
    // Kontekst śledzenia wędruje NAGŁÓWKIEM RAMKI, nie w treści operacji. Dzięki temu
    // format przewodowy operacji pozostaje niezależny od tego, czy śledzenie jest włączone
    // (ADR-0002: nagłówki STOMP są jedynym miejscem na metadane per wiadomość).
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
   * Porzuca bufor po odmowie scalenia (ADR-0005).
   *
   * Wywoływane wyłącznie po `RESYNC_REQUIRED`. Treść tych operacji jest wtedy ratowana
   * osobno, jako odzyskany szkic — bufor czyścimy dopiero potem, żeby nie próbowały
   * w nieskończoność.
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
    for (const subscription of this.subscriptions) subscription.unsubscribe()
    this.subscriptions = []
    void this.client?.deactivate()
    this.client = null
  }
}

function keyOf(value: Id): string {
  return `${value.replicaId}@${value.lamport}`
}
