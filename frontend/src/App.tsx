import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { api, type DocumentSummary, type Session } from './api'
import { RgaDocument } from './crdt/document'
import type { Id, Op } from './crdt/types'
import { Editor } from './editor/Editor'
import type { RemoteCursor } from './editor/remoteCursors'
import {
  DocumentConnection,
  type ConnectionState,
  type PresenceEntry,
} from './transport/connection'
import type { NetworkConditions } from './transport/simulator'

/**
 * Powłoka aplikacji — układ i język wizualny z mockupu, zawartość wg ADR-0007.
 *
 * Najważniejsza różnica wobec mockupu jest w toolbarze i w panelu prawym. Zamiast
 * przycisków formatowania (rich text jest poza zakresem) stoi tam **symulator sieci**,
 * a zamiast komentarzy — **podgląd stanu replik CRDT**. Obie zamiany są celowe: każdy
 * widoczny element ma coś robić, a te dwa pokazują dokładnie to, czego ten projekt dowodzi.
 */
export default function App() {
  const [session, setSession] = useState<Session | null>(null)
  return session ? <Workspace session={session} /> : <Login onLogin={setSession} />
}

function Login({ onLogin }: { onLogin: (session: Session) => void }) {
  const [name, setName] = useState('')
  const [busy, setBusy] = useState(false)

  async function submit(event: React.FormEvent) {
    event.preventDefault()
    setBusy(true)
    try {
      onLogin(await api.login(name))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="login">
      <form className="login-card" onSubmit={submit}>
        <h1>Inkline</h1>
        <p>
          Podaj imię, żeby dołączyć. Bez hasła — uwierzytelnianie jest w tym projekcie
          świadomie uproszczone, bo jego tematem jest spójność danych rozproszonych,
          a nie zarządzanie tożsamością.
        </p>
        <input
          value={name}
          onChange={(event) => setName(event.target.value)}
          placeholder="Twoje imię"
          autoFocus
        />
        <button type="submit" disabled={busy}>
          {busy ? 'Łączę…' : 'Wejdź'}
        </button>
      </form>
    </div>
  )
}

function Workspace({ session }: { session: Session }) {
  const [documents, setDocuments] = useState<DocumentSummary[]>([])
  const [openId, setOpenId] = useState<string | null>(null)

  useEffect(() => {
    void api.listDocuments().then(async (list) => {
      // Dokument z adresu ma pierwszeństwo — dzięki temu link do dokumentu działa,
      // a testy end-to-end mogą pracować każdy na swoim, zamiast dzielić pierwszy z listy
      // i widzieć nawzajem swoje zmiany.
      const requested = new URLSearchParams(window.location.search).get('doc')
      if (requested !== null) {
        setDocuments(list)
        setOpenId(requested)
        return
      }
      if (list.length === 0) {
        const created = await api.createDocument('Q3 Product Roadmap')
        setDocuments([created])
        setOpenId(created.id)
        return
      }
      setDocuments(list)
      setOpenId(list[0].id)
    })
  }, [])

  async function createDocument() {
    const created = await api.createDocument('Dokument bez tytułu')
    setDocuments((current) => [created, ...current])
    select(created.id)
  }

  function select(id: string): void {
    setOpenId(id)
    // Adres odzwierciedla otwarty dokument, żeby dało się go komuś wysłać.
    const url = new URL(window.location.href)
    url.searchParams.set('doc', id)
    window.history.replaceState(null, '', url)
  }

  return (
    <div className="app">
      <div className="menubar">
        <div className="brand">
          <span className="brand-mark" />
          Inkline
        </div>
        <div className="menubar-right" id="menubar-slot" />
      </div>

      {openId ? (
        <DocumentWorkspace
          key={openId}
          session={session}
          documentId={openId}
          documents={documents}
          onSelect={select}
          onCreate={createDocument}
        />
      ) : (
        <div className="body-row" style={{ padding: 24, color: 'var(--text-faint)' }}>
          Ładowanie…
        </div>
      )}
    </div>
  )
}

function DocumentWorkspace(props: {
  session: Session
  documentId: string
  documents: DocumentSummary[]
  onSelect: (id: string) => void
  onCreate: () => void
}) {
  const { session, documentId } = props

  const [title, setTitle] = useState('')
  const [ready, setReady] = useState(false)
  const [initialText, setInitialText] = useState('')
  const [connectionState, setConnectionState] = useState<ConnectionState>('connecting')
  const [pendingCount, setPendingCount] = useState(0)
  const [presence, setPresence] = useState<PresenceEntry[]>([])
  const [network, setNetwork] = useState<NetworkConditions>({
    latencyMs: 0,
    jitterMs: 0,
    packetLoss: 0,
    partitioned: false,
  })
  const [stats, setStats] = useState({ characters: 0, tombstones: 0, blocks: 0, pending: 0 })

  const document = useRef<RgaDocument | null>(null)
  const connection = useRef<DocumentConnection | null>(null)
  const applyRemote = useRef<() => void>(() => {})

  const refreshStats = useCallback(() => {
    const model = document.current
    if (!model) return
    setStats({
      characters: model.visibleLength,
      tombstones: model.tombstoneCount,
      blocks: model.blockCount,
      pending: model.pendingCount,
    })
  }, [])

  useEffect(() => {
    let cancelled = false

    void api.openDocument(documentId).then((state) => {
      if (cancelled) return

      // Dokument startuje PUSTY i jest budowany z operacji przysłanych przez serwer.
      //
      // Kuszące jest zasianie go tekstem z REST-a (`state.text`) — i tak właśnie było
      // w pierwszej wersji. To BŁĄD: `insert` tworzy nowe, lokalne operacje, których
      // serwer nie zna, a potem synchronizacja dostarcza operacje opisujące ten sam
      // tekst. Efekt: dokument zduplikowany. Wykryte testem end-to-end, bo ani property
      // testy, ani testy integracyjne backendu nie dotykają inicjalizacji klienta.
      //
      // Cena obecnego rozwiązania: klient odtwarza CAŁY log operacji przy każdym
      // otwarciu. Przy długiej historii to zacznie boleć — właściwą odpowiedzią jest
      // przesłanie zserializowanego snapshotu CRDT, tak jak robi to serwer wewnętrznie
      // (ADR-0003). Do zmierzenia w CP8, do sekcji „co inaczej w produkcji".
      const model = new RgaDocument(0)

      document.current = model
      setTitle(state.title)
      setInitialText('')
      setReady(true)
      refreshStats()

      const link = new DocumentConnection(documentId, session.token, 0, {
        onSession: (replicaId) => model.adoptReplicaId(replicaId),
        onRemoteOps: (ops) => {
          model.applyAll(ops)
          applyRemote.current()
          refreshStats()
        },
        onPresence: setPresence,
        onStateChange: (nextState, pending) => {
          setConnectionState(nextState)
          setPendingCount(pending)
        },
        onError: (code, message) => console.error(`[${code}] ${message}`),
      })
      link.simulator.onChange(setNetwork)
      link.connect()
      connection.current = link
    })

    return () => {
      cancelled = true
      connection.current?.disconnect()
      connection.current = null
    }
  }, [documentId, session.token, refreshStats])

  const onLocalOps = useCallback(
    (ops: Op[]) => {
      for (const op of ops) connection.current?.send(op)
      refreshStats()
    },
    [refreshStats],
  )

  const onCursorMove = useCallback((head: Id | null, anchor: Id | null) => {
    connection.current?.sendPresence(head, anchor, false)
  }, [])

  const updateNetwork = useCallback((patch: Partial<NetworkConditions>) => {
    connection.current?.simulator.update(patch)
  }, [])

  // Pozycje kursorów przychodzą jako identyfikatory znaków CRDT i dopiero tutaj,
  // na ostatnią chwilę przed rysowaniem, zamieniamy je na offsety (ADR-0006).
  const remoteCursors: RemoteCursor[] = useMemo(() => {
    const model = document.current
    if (!model) return []

    // `stats.characters` jest tu użyte NAPRAWDĘ, nie jako sztuczna zależność: przeliczenie
    // musi nastąpić po każdej zmianie dokumentu (bo identyfikatory mapują się wtedy na inne
    // offsety), a przy okazji przycinamy pozycje do aktualnej długości. Kursor wskazujący
    // poza dokument to skutek wyścigu między presence a operacjami — CodeMirror rzuciłby
    // wtedy wyjątkiem przy renderowaniu dekoracji.
    const clamp = (index: number | null) =>
      index === null ? null : Math.min(index, stats.characters)

    return presence
      .filter((entry) => entry.userId !== session.userId)
      .map((entry) => ({
        replicaId: entry.replicaId,
        displayName: entry.displayName,
        color: entry.color,
        head:
          entry.cursorReplicaId !== null && entry.cursorLamport !== null
            ? clamp(shiftAfter(model.visibleIndexOf({
                replicaId: entry.cursorReplicaId,
                lamport: entry.cursorLamport,
              })))
            : null,
        anchor:
          entry.anchorReplicaId !== null && entry.anchorLamport !== null
            ? clamp(shiftAfter(model.visibleIndexOf({
                replicaId: entry.anchorReplicaId,
                lamport: entry.anchorLamport,
              })))
            : null,
        typing: entry.typing,
      }))
  }, [presence, session.userId, stats.characters])

  return (
    <>
      <NetworkToolbar
        network={network}
        onChange={updateNetwork}
        connectionState={connectionState}
        pendingCount={pendingCount}
      />

      <div className="body-row">
        <aside className="sidebar">
          <div className="sb-section-label">Dokumenty</div>
          {props.documents.map((entry) => (
            <button
              key={entry.id}
              className={`sb-item${entry.id === documentId ? ' active' : ''}`}
              onClick={() => props.onSelect(entry.id)}
            >
              {entry.title}
            </button>
          ))}
          <button className="sb-item" onClick={props.onCreate} style={{ color: 'var(--text-faint)' }}>
            + nowy dokument
          </button>
        </aside>

        <div className="canvas-col">
          <div className="canvas-wrap">
            <div className="page">
              <h1>{title}</h1>
              <div className="meta-line">{peopleLabel(presence.length)} w dokumencie</div>
              {ready && document.current && (
                <Editor
                  document={document.current}
                  initialText={initialText}
                  remoteCursors={remoteCursors}
                  onLocalOps={onLocalOps}
                  onCursorMove={onCursorMove}
                  registerRemoteApply={(apply) => {
                    applyRemote.current = apply
                  }}
                />
              )}
            </div>
          </div>

          <div className="statusbar">
            <span className="metric">{stats.characters} znaków</span>
            <span className="sep" />
            <span className="metric">{stats.blocks} bloków</span>
            <span className="sep" />
            <span className="metric">{stats.tombstones} tombstonów</span>
            <div className="statusbar-right">
              {pendingCount > 0 && <span className="metric">{pendingCount} w buforze</span>}
              <span className="metric">{presence.length} online</span>
            </div>
          </div>
        </div>

        <ReplicaPanel
          stats={stats}
          presence={presence}
          session={session}
          connectionState={connectionState}
          pendingCount={pendingCount}
        />
      </div>
    </>
  )
}

/**
 * Polska odmiana liczebnika: 1 osoba, 2-4 osoby, 5+ osób, ale 12-14 osób.
 * Wyjątek dla nastek jest tym, o którym zapomina większość implementacji.
 */
function peopleLabel(count: number): string {
  const lastTwo = count % 100
  const last = count % 10
  if (count === 1) return '1 osoba'
  if (last >= 2 && last <= 4 && (lastTwo < 12 || lastTwo > 14)) return `${count} osoby`
  return `${count} osób`
}

/** Kursor stoi ZA znakiem, na którym jest zakotwiczony — stąd przesunięcie o jeden. */
function shiftAfter(index: number | null): number | null {
  return index === null ? null : index + 1
}

function NetworkToolbar(props: {
  network: NetworkConditions
  onChange: (patch: Partial<NetworkConditions>) => void
  connectionState: ConnectionState
  pendingCount: number
}) {
  const { network, onChange } = props
  return (
    <div className="toolbar">
      <span style={{ fontSize: 11, color: 'var(--text-faint)', whiteSpace: 'nowrap' }}>
        SYMULATOR SIECI
      </span>
      <span className="tb-sep" />

      <label className="tb-slider">
        opóźnienie
        <input
          type="range"
          min={0}
          max={2000}
          step={50}
          value={network.latencyMs}
          onChange={(event) => onChange({ latencyMs: Number(event.target.value) })}
        />
        <span className="value">{network.latencyMs} ms</span>
      </label>

      <label className="tb-slider">
        jitter
        <input
          type="range"
          min={0}
          max={1000}
          step={25}
          value={network.jitterMs}
          onChange={(event) => onChange({ jitterMs: Number(event.target.value) })}
        />
        <span className="value">±{network.jitterMs} ms</span>
      </label>

      <label className="tb-slider">
        strata
        <input
          type="range"
          min={0}
          max={50}
          step={5}
          value={Math.round(network.packetLoss * 100)}
          onChange={(event) => onChange({ packetLoss: Number(event.target.value) / 100 })}
        />
        <span className="value">{Math.round(network.packetLoss * 100)} %</span>
      </label>

      <span className="tb-sep" />
      <button
        className={`tb-btn danger${network.partitioned ? ' on' : ''}`}
        onClick={() => onChange({ partitioned: !network.partitioned })}
      >
        {network.partitioned ? 'Przywróć łączność' : 'Odetnij sieć'}
      </button>

      <div style={{ marginLeft: 'auto' }} className={`conn-state conn-${props.connectionState}`}>
        <span className="conn-dot" />
        {labelFor(props.connectionState)}
        {props.pendingCount > 0 && ` · ${props.pendingCount} w buforze`}
      </div>
    </div>
  )
}

function labelFor(state: ConnectionState): string {
  switch (state) {
    case 'online':
      return 'połączono'
    case 'offline':
      return 'brak połączenia'
    case 'resyncing':
      return 'synchronizacja'
    default:
      return 'łączenie'
  }
}

/**
 * Podgląd wewnętrznego stanu CRDT.
 *
 * Panel, którego nie ma w żadnym prawdziwym edytorze — i jest tu świadomie (ADR-0007).
 * Adresatem tego repozytorium jest inżynier, a nie użytkownik końcowy, a nic nie pokazuje
 * lepiej, że pod spodem faktycznie działa opisana struktura danych, niż możliwość
 * zobaczenia tombstone'ów i narastania bufora na żywo.
 */
function ReplicaPanel(props: {
  stats: { characters: number; tombstones: number; blocks: number; pending: number }
  presence: PresenceEntry[]
  session: Session
  connectionState: ConnectionState
  pendingCount: number
}) {
  const { stats } = props
  const total = stats.characters + stats.tombstones
  const tombstoneRatio = total === 0 ? 0 : Math.round((stats.tombstones / total) * 100)

  return (
    <aside className="rightpanel">
      <div className="rp-tabs">
        <button className="rp-tab active">Repliki</button>
        <button className="rp-tab" disabled title="Dochodzi w CP5">
          Historia
        </button>
      </div>
      <div className="rp-body">
        <div className="rp-hint">
          Stan struktury CRDT w tej przeglądarce. Tombstone to znak usunięty, ale nadal
          obecny w strukturze — nie da się go skasować, dopóki może być punktem zaczepienia
          operacji, która jeszcze nie dotarła (ADR-0005).
        </div>

        <div className="rp-metric">
          <span className="label">znaki widoczne</span>
          <span className="value">{stats.characters}</span>
        </div>
        <div className="rp-metric">
          <span className="label">tombstone'y</span>
          <span className={`value${tombstoneRatio > 50 ? ' warn' : ''}`}>
            {stats.tombstones} ({tombstoneRatio}%)
          </span>
        </div>
        <div className="rp-metric">
          <span className="label">bloki run-length</span>
          <span className="value">{stats.blocks}</span>
        </div>
        <div className="rp-metric">
          <span className="label">operacje bez przyczyny</span>
          <span className={`value${stats.pending > 0 ? ' warn' : ''}`}>{stats.pending}</span>
        </div>
        <div className="rp-metric">
          <span className="label">niepotwierdzone</span>
          <span className={`value${props.pendingCount > 0 ? ' warn' : ''}`}>
            {props.pendingCount}
          </span>
        </div>

        <div className="sb-section-label" style={{ marginTop: 16 }}>
          Podłączone repliki
        </div>
        {props.presence.map((entry) => (
          <div className="rp-replica" key={entry.replicaId}>
            <div className="who">
              <span className="dot" style={{ background: entry.color }} />
              {entry.displayName}
              {entry.userId === props.session.userId && (
                <span style={{ color: 'var(--text-faint)', fontWeight: 400 }}>(ty)</span>
              )}
            </div>
            <div className="vv">replika {entry.replicaId}</div>
          </div>
        ))}
      </div>
    </aside>
  )
}
