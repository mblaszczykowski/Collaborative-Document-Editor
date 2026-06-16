import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { api, setAuthToken, type DocumentState, type DocumentSummary, type Session } from './api'
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
import { DiagnosticsBar } from './ui/DiagnosticsBar'
import type { Span } from './crdt/marks'
import { DocumentList } from './ui/DocumentList'
import { initials, peopleLabel, shiftAfter, wordCount } from './ui/format'
import { CommentsPanel } from './ui/CommentsPanel'
import { HistoryPanel } from './ui/HistoryPanel'
import { SaveIndicator } from './ui/SaveIndicator'
import { SharePanel } from './ui/SharePanel'
import { DEBUG, SESSION_KEY, loadSession } from './ui/session'

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

export default function App() {
  const [session, setSession] = useState<Session | null>(loadSession)

  // Token musi być ustawiony ZANIM padnie pierwsze żądanie REST — również przy sesji
  // odtworzonej z sessionStorage, gdzie nie ma logowania, przy którym można by go ustawić.
  // useState z funkcją inicjalizującą wykonuje się przed pierwszym renderem, więc
  // ustawienie tutaj wyprzedza useEffect w komponentach potomnych.
  const [, setTokenReady] = useState(() => {
    setAuthToken(session?.token ?? null)
    return true
  })
  void setTokenReady

  function remember(next: Session): void {
    sessionStorage.setItem(SESSION_KEY, JSON.stringify(next))
    setAuthToken(next.token)
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

  const refresh = useCallback(async () => {
    const list = await api.listDocuments()
    setDocuments(list)
    return list
  }, [])

  useEffect(() => {
    void refresh().then(async (list) => {
      const params = new URLSearchParams(window.location.search)

      /**
       * Zaproszenie w adresie (CP21).
       *
       * Wymieniamy je na rolę PRZED otwarciem dokumentu, bo bez tego pierwsze żądanie
       * poszłoby bez dostępu i skończyło się ekranem „nie możemy otworzyć" — dla osoby,
       * która właśnie kliknęła zaproszenie, jest to najgorszy możliwy komunikat.
       *
       * Token znika z adresu od razu po wymianie. Zostawiony w pasku adresu byłby dalej
       * roznoszony przy każdym kopiowaniu linku — a jest to poświadczenie na okaziciela.
       */
      const invite = params.get('invite')
      if (invite !== null) {
        const url = new URL(window.location.href)
        url.searchParams.delete('invite')
        try {
          const redeemed = await api.redeemInvite(invite)
          url.searchParams.set('doc', redeemed.documentId)
          window.history.replaceState({ doc: redeemed.documentId }, '', url)
          await refresh()
          setOpenId(redeemed.documentId)
          return
        } catch {
          // Zaproszenie nieznane, wycofane albo przeterminowane — jedna odpowiedź na trzy
          // przypadki, tak samo jak przy dokumencie bez dostępu. Otwieramy dokument
          // normalnie: jeśli dostęp jest z innego tytułu, zadziała; jeśli nie, użytkownik
          // zobaczy ekran braku dostępu, który tłumaczy, co robić.
          window.history.replaceState({}, '', url)
        }
      }

      const requested = params.get('doc')
      if (requested !== null) {
        setOpenId(requested)
        return
      }
      if (list.length === 0) {
        const created = await api.createDocument('Q3 Product Roadmap')
        setDocuments([created])
        setOpenId(created.id)
        return
      }
      setOpenId(list[0].id)
    })
  }, [refresh])

  /**
   * Adres jest źródłem prawdy o tym, co jest otwarte.
   *
   * `pushState`, nie `replaceState`: przycisk „wstecz" przeglądarki ma wracać
   * do poprzedniego dokumentu. Przy `replaceState` historia przeglądarki nie zawierała
   * przełączeń, więc „wstecz" wyrzucało użytkownika z aplikacji — zachowanie, którego
   * nikt się nie spodziewa po liście dokumentów.
   */
  const select = useCallback((id: string) => {
    setOpenId(id)
    const url = new URL(window.location.href)
    url.searchParams.set('doc', id)
    window.history.pushState({ doc: id }, '', url)
  }, [])

  // Nawigacja przeglądarki (wstecz/dalej) musi przełączać dokument, inaczej adres
  // i zawartość rozjeżdżają się po pierwszym kliknięciu „wstecz".
  useEffect(() => {
    const onPop = () => {
      const requested = new URLSearchParams(window.location.search).get('doc')
      if (requested !== null) setOpenId(requested)
    }
    window.addEventListener('popstate', onPop)
    return () => window.removeEventListener('popstate', onPop)
  }, [])

  async function createDocument() {
    const created = await api.createDocument('Untitled document')
    setDocuments((current) => [created, ...current])
    select(created.id)
  }

  return openId ? (
    <DocumentWorkspace
      key={openId}
      session={session}
      documentId={openId}
      documents={documents}
      onSelect={select}
      onCreate={createDocument}
      onRefresh={() => void refresh()}
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
  /** Lista wymaga odświeżenia — po zmianie nazwy, usunięciu albo przywróceniu. */
  onRefresh: () => void
}) {
  const { session, documentId } = props

  const [title, setTitle] = useState('')
  /**
   * Rola w tym dokumencie, przysłana razem ze stanem początkowym (CP12).
   *
   * Domyślnie **najsłabsza z możliwych**, a nie najsilniejsza: gdyby serwer nie odpowiedział
   * albo odpowiedział starym formatem, użytkownik ma widzieć dokument tylko do odczytu.
   * Odwrotny domyślny wybór pozwalałby pisać w oknie między otwarciem a odpowiedzią —
   * czyli dokładnie w tym momencie, w którym nie wiadomo jeszcze, czy wolno.
   */
  const [role, setRole] = useState<DocumentState['role']>('viewer')
  const [ready, setReady] = useState(false)
  /**
   * Dokumentu nie da się otworzyć — bo nie istnieje, jest w koszu albo nie mamy do niego
   * dostępu. Serwer odpowiada 404 na wszystkie trzy przypadki celowo (potwierdzenie, że
   * dokument istnieje, jest samo w sobie wyciekiem), więc i komunikat jest jeden.
   *
   * Bez tego stanu odmowa dostępu kończyła się PUSTĄ STRONĄ: `ready` zostawało `false`,
   * edytor nie renderował się nigdy, a jedynym śladem było odrzucone `Promise` w konsoli.
   * Regresja wprowadzona razem z autoryzacją w CP12 — wcześniej to żądanie nie miało prawa
   * zwrócić 404 dla poprawnego identyfikatora.
   */
  const [unavailable, setUnavailable] = useState(false)

  // Te same progi co po stronie serwera (Role.canWriteText / Role.canComment). Duplikat
  // reguły jest tu ŚWIADOMY i nieszkodliwy: serwer sprawdza niezależnie i to on rozstrzyga.
  // Klient używa jej wyłącznie do tego, żeby nie proponować gestu, który zostanie odrzucony.
  const canWrite = role === 'owner' || role === 'editor'
  const canComment = canWrite || role === 'commenter'
  const [connectionState, setConnectionState] = useState<ConnectionState>('connecting')
  const [pendingCount, setPendingCount] = useState(0)
  const [presence, setPresence] = useState<PresenceEntry[]>([])
  const [characters, setCharacters] = useState(0)
  const [recovery, setRecovery] = useState<{ title: string; id: string } | null>(null)
  const [shared, setShared] = useState(false)
  const [sharing, setSharing] = useState(false)
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

  const [formatting, setFormatting] = useState<Span[]>([])
  const [panel, setPanel] = useState<'history' | 'comments'>('history')

  /**
   * Aktualnie zaznaczony tekst i jego zakres.
   *
   * Trzymane w stanie, bo panel komentarzy potrzebuje go PO kliknięciu w swoje pole tekstowe —
   * a wtedy zaznaczenie w edytorze już nie istnieje. Zapamiętujemy je w chwili, gdy jeszcze jest.
   */
  const [selection, setSelection] = useState('')
  const selectionRange = useRef<{ from: number; to: number } | null>(null)

  /**
   * Stawia kotwicę komentarza — znacznik CRDT typu `comment` z identyfikatorem wątku.
   *
   * Powstaje PO wątku, bo potrzebuje jego identyfikatora, i idzie inną drogą: wątek REST-em,
   * kotwica WebSocketem. Jest to celowe — kotwica musi scalać się jak każda inna operacja
   * CRDT, a treść rozmowy nie ma czego scalać (ADR-0010, CP14).
   */
  const anchorComment = useCallback((threadId: string) => {
    const model = document.current
    const link = connection.current
    const range = selectionRange.current
    if (model === null || link === null || range === null) return
    // Jw. — panel nie pokazuje czytelnikowi pola komentarza, ale wejście do tej funkcji
    // ma być zamknięte niezależnie od tego, co akurat rysuje interfejs.
    if (!canComment) return
    link.send(model.addMark(range.from, range.to, 'comment', threadId))
    setFormatting(model.formatting())
  }, [canComment])

  /**
   * Przełącza formatowanie na zaznaczeniu.
   *
   * „Przełącza", nie „nadaje": jeśli CAŁY zaznaczony zakres ma już ten znacznik, gest
   * ma go zdjąć. Bez tego Ctrl+B na pogrubionym tekście nadawałby pogrubienie po raz drugi,
   * czyli nie robił nic widocznego — a użytkownik nie ma jak się domyślić, że musi użyć
   * innego gestu, żeby cofnąć własną zmianę.
   */
  const toggleMark = useCallback((type: string, from: number, to: number) => {
    const model = document.current
    const link = connection.current
    if (model === null || link === null || from >= to) return
    // Wyszarzenie przycisków nie wystarcza: SKRÓT KLAWISZOWY omija pasek narzędzi,
    // a `EditorState.readOnly` blokuje wyłącznie zmiany dokumentu — polecenia z keymapy
    // wykonują się dalej. Bez tego warunku Ctrl+B u czytelnika tworzył znacznik lokalnie,
    // serwer odrzucał go po cichu i replika rozjeżdżała się na zawsze. Ta sama klasa błędu
    // co przy limicie tempa, tylko wejściem, o którym łatwo zapomnieć.
    if (!canWrite) return

    const alreadyMarked = coveredBy(model.formatting(), type, from, to)
    const op = alreadyMarked
      ? model.removeMark(from, to, type)
      : model.addMark(from, to, type, type === 'link' ? window.prompt('Adres linku') : null)

    link.send(op)
    // Przeliczamy od razu, zamiast czekać na echo z serwera: operacja jest już zastosowana
    // lokalnie, więc czekanie oznaczałoby widoczne opóźnienie między naciśnięciem Ctrl+B
    // a zmianą wyglądu — dokładnie ten koszt, którego edycja optymistyczna ma unikać.
    setFormatting(model.formatting())
  }, [canWrite])

  const refreshStats = useCallback(() => {
    const model = document.current
    if (model === null) return
    setCharacters(model.visibleLength)
    // Formatowanie przeliczamy przy każdej zmianie treści, bo odcinki są wyrażone
    // w pozycjach widocznego tekstu — a te przesuwają się przy każdym wstawieniu.
    setFormatting(model.formatting())
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
        `Recovered draft — ${new Date().toLocaleString('en-GB')}`,
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
      setUnavailable(false)

      // Dokument startuje PUSTY i jest budowany z operacji przysłanych przez serwer.
      // Zasianie go tekstem z REST-a tworzyłoby nowe operacje lokalne, których serwer nie
      // zna — a potem synchronizacja dostarczyłaby operacje opisujące ten sam tekst.
      const model = new RgaDocument(0)
      document.current = model
      undo.current = new UndoManager(model)
      setTitle(state.title)
      setRole(state.role)
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
        /**
         * Zmiana dostępu (CP21) — pytamy serwer o SWOJĄ rolę, nie wierzymy komunikatowi.
         *
         * Do CP21 rola przyjeżdżała raz, ze stanem początkowym, więc podniesienie komuś
         * uprawnień nie robiło nic do czasu przeładowania strony. Nie była to dziura —
         * serwer i tak odrzucał operacje — tylko sposób, w jaki działająca funkcja
         * wygląda na zepsutą: właściciel nadaje dostęp, a druga osoba dalej widzi
         * dokument tylko do odczytu i nic jej tego nie tłumaczy.
         *
         * Pytamy o samą rolę, a nie przez `openDocument`: tamto niesie też cały tekst.
         */
        onAccessChanged: (userId) => {
          if (userId !== null && userId !== session.userId) return
          void api.myRole(documentId)
            .then((mine) => setRole(mine.role))
            // 404 znaczy „już nie masz dostępu" — ta sama odpowiedź co przy dokumencie,
            // którego nie wolno czytać, i ten sam ekran co przy otwarciu bez dostępu.
            .catch(() => setUnavailable(true))
        },
      })
      link.simulator.onChange(setNetwork)
      void link.restoreOfflineBuffer().then(() => link.connect())
      connection.current = link
    }).catch(() => {
      // Nie łączymy się WebSocketem: interceptor odrzuciłby subskrypcję z tego samego
      // powodu, tylko po cichu — a klient próbowałby się łączyć w pętli.
      if (!cancelled) setUnavailable(true)
    })

    return () => {
      cancelled = true
      connection.current?.disconnect()
      connection.current = null
    }
  }, [documentId, session.token, session.userId, refreshStats, recoverAfterRejection])

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

  /**
   * Zapamiętuje zaznaczenie, dopóki jeszcze istnieje.
   *
   * Kliknięcie w pole komentarza odbiera fokus edytorowi i wtedy jest już za późno,
   * żeby je odczytać. Offsety, nie identyfikatory: identyfikator nie wyraża pozycji zero,
   * więc zaznaczenie od początku dokumentu wyglądałoby jak jego brak.
   */
  const onSelectionChange = useCallback((from: number, to: number, text: string) => {
    selectionRange.current = from < to ? { from, to } : null
    setSelection(text)
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

  /**
   * Udostępnianie (CP21).
   *
   * Właściciel dostaje panel; wszyscy pozostali — kopię adresu, czyli to, co ten przycisk
   * robił wcześniej dla wszystkich. Różnica nie jest kosmetyczna: od CP21 nowy dokument
   * jest prywatny, więc sam adres nikogo nie wpuszcza. Skopiowanie go przez osobę bez prawa
   * zarządzania dostępem jest nadal sensownym gestem („wyślij mi to jeszcze raz"), ale to
   * zaproszenie wpuszcza — i tylko właściciel może je wystawić.
   */
  async function share() {
    if (role === 'owner') {
      setSharing(true)
      return
    }
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
          {!canWrite && (
            <span className="role-badge" title="Twoja rola w tym dokumencie">
              {canComment ? 'Możesz komentować' : 'Tylko do odczytu'}
            </span>
          )}
          <button className="btn-outline" onClick={share}>
            {shared ? 'Skopiowano' : 'Udostępnij'}
          </button>
        </div>
      </header>

      {sharing && <SharePanel documentId={documentId} onClose={() => setSharing(false)} />}

      {DEBUG && (
        <DiagnosticsBar
          network={network}
          onChange={(patch) => connection.current?.simulator.update(patch)}
          document={document.current}
          characters={characters}
        />
      )}

      <div className="body-row">
        <DocumentList
          documents={props.documents}
          openId={documentId}
          onSelect={props.onSelect}
          onCreate={props.onCreate}
          onChanged={props.onRefresh}
        />

        <main className="canvas-col">
          <div className="editor-toolbar">
            <FormatButtons onToggle={toggleMark} disabled={!canWrite} />
            <span className="tb-divider" />
            <button
              className={`tb-icon${panel === 'comments' ? ' active' : ''}`}
              title="Komentarze"
              aria-label="Komentarze"
              onClick={() => setPanel((current) => (current === 'comments' ? 'history' : 'comments'))}
            >
              💬
            </button>
            <span className="tb-divider" />
            <button className="tb-icon" onClick={runUndo} title="Cofnij (⌘Z)" aria-label="Cofnij"
              disabled={!canWrite}>
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                <path d="M3 10h10a4 4 0 0 1 0 8H8" />
                <path d="M7 6l-4 4 4 4" />
              </svg>
            </button>
            <button className="tb-icon" onClick={runRedo} title="Ponów (⇧⌘Z)" aria-label="Ponów"
              disabled={!canWrite}>
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
              {unavailable && (
                <div className="unavailable-banner">
                  <strong>Nie możemy otworzyć tego dokumentu.</strong> Mógł zostać usunięty
                  albo nie masz do niego dostępu — poproś osobę, która przysłała Ci link,
                  żeby udostępniła go ponownie.
                </div>
              )}
              {ready && document.current && undo.current && (
                <Editor
                  document={document.current}
                  undo={undo.current}
                  remoteCursors={remoteCursors}
                  formatting={formatting}
                  onLocalOps={onLocalOps}
                  onToggleMark={toggleMark}
                  onCursorMove={onCursorMove}
                  onSelectionChange={onSelectionChange}
                  onUndo={runUndo}
                  onRedo={runRedo}
                  readOnly={!canWrite}
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

        {panel === 'history' ? (
          <HistoryPanel documentId={documentId} revision={characters} readOnly={!canWrite} />
        ) : (
          <CommentsPanel
            documentId={documentId}
            selection={selection}
            revision={characters}
            onAnchor={anchorComment}
            readOnly={!canComment}
          />
        )}
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

/**
 * Przyciski formatowania.
 *
 * Działają na AKTUALNYM ZAZNACZENIU odczytanym z DOM-u, a nie ze stanu Reacta. Powód jest
 * praktyczny: kliknięcie przycisku odbiera fokus edytorowi, więc gdyby zaznaczenie było
 * trzymane w stanie i aktualizowane zdarzeniem, w chwili kliknięcia byłoby już puste.
 * `onMouseDown` z `preventDefault` zatrzymuje utratę fokusu, zanim do niej dojdzie.
 */
function FormatButtons(
  { onToggle, disabled }:
    { onToggle: (type: string, from: number, to: number) => void; disabled: boolean },
) {
  function apply(type: string) {
    const selection = window.getSelection()
    if (selection === null || selection.isCollapsed) return
    const editor = window.document.querySelector('.cm-content')
    if (editor === null) return

    // Offsety liczone względem treści edytora, nie węzła DOM — CodeMirror dzieli tekst
    // na wiele elementów, więc offset w węźle nie mówi nic o pozycji w dokumencie.
    const range = selection.getRangeAt(0)
    const before = range.cloneRange()
    before.selectNodeContents(editor)
    before.setEnd(range.startContainer, range.startOffset)
    const from = before.toString().length
    const to = from + range.toString().length
    if (from < to) onToggle(type, from, to)
  }

  const buttons: { type: string; label: string; title: string; render: React.ReactNode }[] = [
    { type: 'bold', label: 'Pogrubienie', title: 'Pogrubienie (⌘B)', render: <strong>B</strong> },
    { type: 'italic', label: 'Kursywa', title: 'Kursywa (⌘I)', render: <em>I</em> },
    { type: 'underline', label: 'Podkreślenie', title: 'Podkreślenie (⌘U)', render: <u>U</u> },
    { type: 'code', label: 'Kod', title: 'Kod', render: <code>{'<>'}</code> },
    { type: 'link', label: 'Link', title: 'Link', render: <span>🔗</span> },
  ]

  return (
    <>
      {buttons.map((button) => (
        <button
          key={button.type}
          className="tb-icon tb-format"
          title={button.title}
          aria-label={button.label}
          onMouseDown={(event) => event.preventDefault()}
          onClick={() => apply(button.type)}
          disabled={disabled}
        >
          {button.render}
        </button>
      ))}
    </>
  )
}

/**
 * Czy CAŁY zakres [from, to) ma już znacznik danego typu.
 *
 * Warunek jest celowo mocny: przy zaznaczeniu obejmującym pogrubiony i niepogrubiony
 * fragment gest ma DODAĆ pogrubienie do całości, a nie zdjąć je z części. Tak zachowują się
 * edytory, do których ludzie są przyzwyczajeni, i jest to zachowanie łatwiejsze do cofnięcia
 * niż odwrotne.
 */
function coveredBy(spans: Span[], type: string, from: number, to: number): boolean {
  let covered = from
  for (const span of spans) {
    if (!span.marks.has(type)) continue
    if (span.from > covered) break
    covered = Math.max(covered, span.to)
    if (covered >= to) return true
  }
  return covered >= to
}
