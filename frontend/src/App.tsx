import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { api, type DocumentSummary, type Session, type Version } from './api'
import { RgaDocument } from './crdt/document'
import { UndoManager } from './crdt/undo'
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
 * Powłoka aplikacji.
 *
 * **Interfejs pokazuje wyłącznie rzeczy, które są funkcją produktu.** Symulator sieci
 * i podgląd stanu CRDT — dwa narzędzia bezcenne przy pracy nad tym projektem — są schowane
 * za parametrem `?debug=1` i nie widzi ich nikt, kto po prostu otworzył edytor
 * (ADR-0007, decyzja zrewidowana).
 *
 * Powód: użytkownik nie ma pojęcia, czym jest tombstone ani version vector, a suwak
 * „utrata pakietów" nad dokumentem sprawia, że produkt wygląda na stanowisko laboratoryjne.
 * Diagnostyka nie znika — przenosi się tam, gdzie jej miejsce.
 */

const SESSION_KEY = 'inkline.session'

/** Tryb diagnostyczny — wyłącznie do pracy nad projektem i testów chaosu. */
const DEBUG = new URLSearchParams(window.location.search).has('debug')

/**
 * Sesja przeżywa przeładowanie strony.
 *
 * `sessionStorage`, nie `localStorage`: tożsamość ma być związana z KARTĄ, nie z przeglądarką.
 * Przy `localStorage` dwie karty otwarte obok siebie dzieliłyby konto — czyli scenariusz
 * „dwie osoby w dwóch oknach" przestałby działać.
 */
function loadSession(): Session | null {
  const stored = sessionStorage.getItem(SESSION_KEY)
  if (stored === null) return null
  try {
    return JSON.parse(stored) as Session
  } catch {
    sessionStorage.removeItem(SESSION_KEY)
    return null
  }
}

export default function App() {
  const [session, setSession] = useState<Session | null>(loadSession)

  function remember(next: Session): void {
    sessionStorage.setItem(SESSION_KEY, JSON.stringify(next))
    setSession(next)
  }

  return session ? <Workspace session={session} /> : <Login onLogin={remember} />
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
        <div className="login-brand">
          <span className="brand-mark" />
          Inkline
        </div>
        <p>Edytor dokumentów do pracy zespołowej w czasie rzeczywistym.</p>
        <input
          value={name}
          onChange={(event) => setName(event.target.value)}
          placeholder="Twoje imię"
          autoFocus
        />
        <button type="submit" disabled={busy}>
          {busy ? 'Łączę…' : 'Rozpocznij'}
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
    const url = new URL(window.location.href)
    url.searchParams.set('doc', id)
    window.history.replaceState(null, '', url)
  }

  return openId ? (
    <DocumentWorkspace
      key={openId}
      session={session}
      documentId={openId}
      documents={documents}
      onSelect={select}
      onCreate={createDocument}
    />
  ) : (
    <div className="app">
      <div className="loading">Ładowanie…</div>
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
  const [connectionState, setConnectionState] = useState<ConnectionState>('connecting')
  const [pendingCount, setPendingCount] = useState(0)
  const [presence, setPresence] = useState<PresenceEntry[]>([])
  const [characters, setCharacters] = useState(0)
  const [recovery, setRecovery] = useState<{ title: string; id: string } | null>(null)
  const [shared, setShared] = useState(false)
  const [network, setNetwork] = useState<NetworkConditions>({
    latencyMs: 0,
    jitterMs: 0,
    packetLoss: 0,
    partitioned: false,
  })

  const document = useRef<RgaDocument | null>(null)
  const undo = useRef<UndoManager | null>(null)
  const connection = useRef<DocumentConnection | null>(null)
  const applyRemote = useRef<() => void>(() => {})

  const refreshStats = useCallback(() => {
    const model = document.current
    if (model !== null) setCharacters(model.visibleLength)
  }, [])

  /**
   * Reakcja na odmowę scalenia (ADR-0005).
   *
   * Serwer odmówił, bo punkt zaczepienia tych operacji usunął garbage collector. Informacja
   * o pozycji tego tekstu jest bezpowrotnie utracona i nie ma sposobu, żeby go uczciwie
   * wstawić z powrotem. Zamiast udawać sukces (doklejenie na koniec = cicha korupcja) albo
   * milczeć (= cicha utrata pracy), ratujemy lokalną wersję jako osobny dokument.
   */
  const recoverAfterRejection = useCallback(
    async (model: RgaDocument, link: DocumentConnection) => {
      const rescued = await api.createRecovered(
        `Odzyskany szkic — ${new Date().toLocaleString('pl-PL')}`,
        model.text,
      )
      await link.abandonBuffer()
      setRecovery({ title: rescued.title, id: rescued.id })
    },
    [],
  )

  useEffect(() => {
    let cancelled = false

    void api.openDocument(documentId).then((state) => {
      if (cancelled) return

      // Dokument startuje PUSTY i jest budowany z operacji przysłanych przez serwer.
      // Zasianie go tekstem z REST-a tworzyłoby nowe operacje lokalne, których serwer nie
      // zna — a potem synchronizacja dostarczyłaby operacje opisujące ten sam tekst.
      const model = new RgaDocument(0)
      document.current = model
      undo.current = new UndoManager(model)
      setTitle(state.title)
      setReady(true)
      refreshStats()

      const link = new DocumentConnection(documentId, session.token, 0, {
        onSession: (replicaId) => model.adoptReplicaId(replicaId),
        versionVector: () => model.version,
        hasOperation: (replicaId, lamport) => model.contains({ replicaId, lamport }),
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
        onError: (code, message) => {
          if (code === 'RESYNC_REQUIRED') {
            void recoverAfterRejection(model, link)
            return
          }
          console.error(`[${code}] ${message}`)
        },
      })
      link.simulator.onChange(setNetwork)
      void link.restoreOfflineBuffer().then(() => link.connect())
      connection.current = link
    })

    return () => {
      cancelled = true
      connection.current?.disconnect()
      connection.current = null
    }
  }, [documentId, session.token, refreshStats, recoverAfterRejection])

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

  const runUndo = useCallback(() => {
    const ops = undo.current?.undo() ?? []
    if (ops.length > 0) {
      applyRemote.current()
      onLocalOps(ops)
    }
  }, [onLocalOps])

  const runRedo = useCallback(() => {
    const ops = undo.current?.redo() ?? []
    if (ops.length > 0) {
      applyRemote.current()
      onLocalOps(ops)
    }
  }, [onLocalOps])

  // Pozycje kursorów przychodzą jako identyfikatory znaków CRDT i dopiero tutaj,
  // na ostatnią chwilę przed rysowaniem, zamieniamy je na offsety (ADR-0006).
  const remoteCursors: RemoteCursor[] = useMemo(() => {
    const model = document.current
    if (!model) return []
    const clamp = (index: number | null) => (index === null ? null : Math.min(index, characters))

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
  }, [presence, session.userId, characters])

  async function share() {
    await navigator.clipboard.writeText(window.location.href)
    setShared(true)
    setTimeout(() => setShared(false), 2000)
  }

  const others = presence.filter((entry) => entry.userId !== session.userId)

  return (
    <div className="app">
      <header className="menubar">
        <div className="brand">
          <span className="brand-mark" />
          Inkline
        </div>
        <span className="menu-divider" />
        <span className="doc-title">{title}</span>

        <div className="menubar-right">
          <SaveIndicator state={connectionState} pending={pendingCount} />
          <div className="presence">
            <span className="avatar" style={{ background: session.color }} title={session.displayName}>
              {initials(session.displayName)}
            </span>
            {others.slice(0, 4).map((person) => (
              <span
                key={person.replicaId}
                className="avatar"
                style={{ background: person.color }}
                title={person.displayName}
              >
                {initials(person.displayName)}
              </span>
            ))}
          </div>
          <button className="btn-outline" onClick={share}>
            {shared ? 'Skopiowano' : 'Udostępnij'}
          </button>
        </div>
      </header>

      {DEBUG && (
        <DiagnosticsBar
          network={network}
          onChange={(patch) => connection.current?.simulator.update(patch)}
          document={document.current}
          characters={characters}
        />
      )}

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
          <button className="sb-item sb-new" onClick={props.onCreate}>
            + Nowy dokument
          </button>
        </aside>

        <main className="canvas-col">
          <div className="editor-toolbar">
            <button className="tb-icon" onClick={runUndo} title="Cofnij (⌘Z)" aria-label="Cofnij">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                <path d="M3 10h10a4 4 0 0 1 0 8H8" />
                <path d="M7 6l-4 4 4 4" />
              </svg>
            </button>
            <button className="tb-icon" onClick={runRedo} title="Ponów (⇧⌘Z)" aria-label="Ponów">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                <path d="M21 10H11a4 4 0 0 0 0 8h5" />
                <path d="M17 6l4 4-4 4" />
              </svg>
            </button>
          </div>

          <div className="canvas-wrap">
            <div className="page">
              <h1>{title}</h1>
              <div className="meta-line">
                {others.length === 0 ? 'Tylko Ty' : `${peopleLabel(others.length + 1)} w dokumencie`}
              </div>
              {recovery !== null && (
                <div className="recovery-banner">
                  <strong>Twoich zmian nie dało się scalić.</strong> Zbyt długo pracowałeś
                  offline i miejsce, do którego pisałeś, przestało istnieć. Twoja wersja
                  została zapisana jako <a href={`?doc=${recovery.id}`}>{recovery.title}</a>.
                  Nic nie przepadło.
                </div>
              )}
              {ready && document.current && undo.current && (
                <Editor
                  document={document.current}
                  undo={undo.current}
                  remoteCursors={remoteCursors}
                  onLocalOps={onLocalOps}
                  onCursorMove={onCursorMove}
                  onUndo={runUndo}
                  onRedo={runRedo}
                  registerRemoteApply={(apply) => {
                    applyRemote.current = apply
                  }}
                />
              )}
            </div>
          </div>

          <footer className="statusbar">
            <span>{wordCount(characters)}</span>
            <span className="sep" />
            <span data-testid="char-count">{characters} znaków</span>
            <div className="statusbar-right">
              <span>{others.length + 1} online</span>
            </div>
          </footer>
        </main>

        <HistoryPanel documentId={documentId} revision={characters} />
      </div>
    </div>
  )
}

/**
 * Wskaźnik zapisu — jedyna informacja o stanie połączenia, jaką widzi użytkownik.
 *
 * Świadomie w języku produktu („Zapisano", „Praca offline"), a nie protokołu
 * („resynchronizacja", „N operacji w buforze"). Użytkownik ma wiedzieć, czy jego praca
 * jest bezpieczna — nie jak działa transport.
 */
function SaveIndicator({ state, pending }: { state: ConnectionState; pending: number }) {
  if (state === 'offline') {
    return (
      <span className="save-state offline">
        <span className="save-dot" />
        Praca offline
      </span>
    )
  }
  if (pending > 0) {
    return (
      <span className="save-state pending">
        <span className="save-dot" />
        Zapisywanie…
      </span>
    )
  }
  if (state === 'connecting' || state === 'resyncing') {
    return (
      <span className="save-state pending">
        <span className="save-dot" />
        Łączenie…
      </span>
    )
  }
  return (
    <span className="save-state saved">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3">
        <polyline points="20 6 9 17 4 12" />
      </svg>
      Zapisano
    </span>
  )
}

function HistoryPanel({ documentId, revision }: { documentId: string; revision: number }) {
  const [versions, setVersions] = useState<Version[]>([])
  const [selected, setSelected] = useState<Version | null>(null)
  const [preview, setPreview] = useState<string | null>(null)
  const [restoring, setRestoring] = useState(false)

  useEffect(() => {
    void api.history(documentId).then(setVersions)
  }, [documentId, revision])

  async function select(version: Version) {
    setSelected(version)
    setPreview(null)
    setPreview((await api.textAt(documentId, version.throughServerSeq)).text)
  }

  async function restore() {
    if (selected === null) return
    setRestoring(true)
    try {
      await api.restore(documentId, selected.throughServerSeq)
      setSelected(null)
      setPreview(null)
    } finally {
      setRestoring(false)
    }
  }

  return (
    <aside className="rightpanel">
      <div className="rp-header">Historia zmian</div>
      <div className="rp-body">
        {versions.length === 0 && <div className="rp-empty">Brak zmian.</div>}

        {versions.map((version) => (
          <div key={version.throughServerSeq}>
            <button
              className={`history-entry${
                selected?.throughServerSeq === version.throughServerSeq ? ' selected' : ''
              }`}
              onClick={() => void select(version)}
            >
              <div className="h-top">
                <span className="history-dot" style={{ background: version.color }} />
                {version.displayName}
                <span className="h-time">{formatTime(version.at)}</span>
              </div>
              <div className="h-desc">
                {version.charactersInserted > 0 && (
                  <span className="added">+{version.charactersInserted}</span>
                )}
                {version.charactersInserted > 0 && version.charactersDeleted > 0 && ' '}
                {version.charactersDeleted > 0 && (
                  <span className="removed">−{version.charactersDeleted}</span>
                )}
              </div>
            </button>

            {selected?.throughServerSeq === version.throughServerSeq && (
              <>
                <div className="history-preview">{preview ?? 'Wczytuję…'}</div>
                <button className="history-restore" onClick={restore} disabled={restoring}>
                  {restoring ? 'Przywracam…' : 'Przywróć tę wersję'}
                </button>
              </>
            )}
          </div>
        ))}
      </div>
    </aside>
  )
}

/**
 * Pasek diagnostyczny — WYŁĄCZNIE przy `?debug=1`.
 *
 * Symulator sieci napędza demo i testy chaosu, a podgląd stanu CRDT był nieoceniony przy
 * pracy nad algorytmem. Żadna z tych rzeczy nie ma prawa pojawić się przed użytkownikiem:
 * pierwsza sprawia, że produkt wygląda na stanowisko laboratoryjne, druga posługuje się
 * pojęciami, których nie ma obowiązku znać.
 */
function DiagnosticsBar(props: {
  network: NetworkConditions
  onChange: (patch: Partial<NetworkConditions>) => void
  document: RgaDocument | null
  characters: number
}) {
  const { network, onChange, document: model } = props
  return (
    <div className="diagnostics">
      <span className="diag-label">DIAGNOSTYKA</span>

      <label className="diag-slider">
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

      <label className="diag-slider">
        strata
        <input
          type="range"
          min={0}
          max={50}
          step={5}
          value={Math.round(network.packetLoss * 100)}
          onChange={(event) => onChange({ packetLoss: Number(event.target.value) / 100 })}
        />
        <span className="value">{Math.round(network.packetLoss * 100)}%</span>
      </label>

      <button
        className={`diag-btn${network.partitioned ? ' on' : ''}`}
        onClick={() => onChange({ partitioned: !network.partitioned })}
      >
        {network.partitioned ? 'Przywróć łączność' : 'Odetnij sieć'}
      </button>

      {model && (
        <span className="diag-stats">
          bloki {model.blockCount} · tombstony {model.tombstoneCount} · bez przyczyny{' '}
          {model.pendingCount}
        </span>
      )}
    </div>
  )
}

function shiftAfter(index: number | null): number | null {
  return index === null ? null : index + 1
}

function initials(name: string): string {
  return name
    .split(/\s+/)
    .slice(0, 2)
    .map((part) => part.charAt(0).toUpperCase())
    .join('')
}

/** Przybliżenie: średnie polskie słowo to około sześciu znaków plus spacja. */
function wordCount(characters: number): string {
  const words = Math.max(0, Math.round(characters / 7))
  if (words === 1) return '1 słowo'
  const last = words % 10
  const lastTwo = words % 100
  if (last >= 2 && last <= 4 && (lastTwo < 12 || lastTwo > 14)) return `${words} słowa`
  return `${words} słów`
}

/** Polska odmiana: 1 osoba, 2–4 osoby, 5+ osób, ale 12–14 osób. */
function peopleLabel(count: number): string {
  const lastTwo = count % 100
  const last = count % 10
  if (count === 1) return '1 osoba'
  if (last >= 2 && last <= 4 && (lastTwo < 12 || lastTwo > 14)) return `${count} osoby`
  return `${count} osób`
}

function formatTime(iso: string): string {
  const at = new Date(iso)
  const secondsAgo = Math.floor((Date.now() - at.getTime()) / 1000)
  if (secondsAgo < 60) return `${secondsAgo} s temu`
  if (secondsAgo < 3600) return `${Math.floor(secondsAgo / 60)} min temu`
  return at.toLocaleTimeString('pl-PL', { hour: '2-digit', minute: '2-digit' })
}
