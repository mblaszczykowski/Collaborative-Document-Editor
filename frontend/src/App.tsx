import { useCallback, useEffect, useState } from 'react'
import { api, setAuthToken, type DocumentSummary, type Session } from './api'
import { Editor } from './editor/Editor'
import { useCollaborativeDocument } from './editor/useCollaborativeDocument'
import { DiagnosticsBar } from './ui/DiagnosticsBar'
import { DocumentList } from './ui/DocumentList'
import { initials, peopleLabel, wordCount } from './ui/format'
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
       * An invitation in the address (CP21).
       *
       * Redeemed for a role BEFORE the document is opened: without that the first request
       * would go out with no access and end on the "we cannot open this" screen — the worst
       * possible message for somebody who has just clicked an invitation.
       *
       * The token leaves the address immediately after redemption. Left in the address bar it
       * would keep being passed on every time somebody copies the link — and it is a bearer
       * credential.
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
          // Unknown, revoked or expired — one answer to three cases, exactly as with a
          // document one may not read. We then open the document normally: if access exists
          // for another reason it works, and if it does not, the user gets the no-access
          // screen, which explains what to do.
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

  // Everything about the document itself lives in the hook: opening it, the replica, the socket,
  // the commands and their permission checks. What is left here is what the page LOOKS like.
  // The split is described in useCollaborativeDocument; the short version is that only one of
  // those two changes when somebody redesigns the page.
  const doc = useCollaborativeDocument(documentId, session)

  const [panel, setPanel] = useState<'history' | 'comments'>('history')
  const [shared, setShared] = useState(false)
  const [sharing, setSharing] = useState(false)

  /**
   * Sharing (CP21).
   *
   * The owner gets the panel; everybody else gets a copy of the address, which is what this
   * button did for everyone before. The difference is not cosmetic: since CP21 a new document
   * is private, so the address alone lets nobody in. Copying it is still a sensible gesture
   * for somebody without access management ("send me that again"), but it is the invitation
   * that opens the door — and only the owner can issue one.
   */
  async function share() {
    if (doc.role === 'owner') {
      setSharing(true)
      return
    }
    await navigator.clipboard.writeText(window.location.href)
    setShared(true)
    setTimeout(() => setShared(false), 2000)
  }

  const others = doc.presence.filter((entry) => entry.userId !== session.userId)

  return (
    <div className="app">
      <header className="menubar">
        <div className="brand">
          <span className="brand-mark" />
          Inkline
        </div>
        <span className="menu-divider" />
        <span className="doc-title">{doc.title}</span>

        <div className="menubar-right">
          <SaveIndicator state={doc.connectionState} pending={doc.pendingCount} />
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
          {!doc.canWrite && (
            <span className="role-badge" title="Twoja rola w tym dokumencie">
              {doc.canComment ? 'Możesz komentować' : 'Tylko do odczytu'}
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
          network={doc.network}
          onChange={doc.updateNetwork}
          document={doc.model}
          characters={doc.characters}
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
            <FormatButtons onToggle={doc.toggleMark} disabled={!doc.canWrite} />
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
            <button className="tb-icon" onClick={doc.runUndo} title="Cofnij (⌘Z)" aria-label="Cofnij"
              disabled={!doc.canWrite}>
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                <path d="M3 10h10a4 4 0 0 1 0 8H8" />
                <path d="M7 6l-4 4 4 4" />
              </svg>
            </button>
            <button className="tb-icon" onClick={doc.runRedo} title="Ponów (⇧⌘Z)" aria-label="Ponów"
              disabled={!doc.canWrite}>
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                <path d="M21 10H11a4 4 0 0 0 0 8h5" />
                <path d="M17 6l4 4-4 4" />
              </svg>
            </button>
          </div>

          <div className="canvas-wrap">
            <div className="page">
              <h1>{doc.title}</h1>
              <div className="meta-line">
                {others.length === 0 ? 'Tylko Ty' : `${peopleLabel(others.length + 1)} w dokumencie`}
              </div>
              {doc.recovery !== null && (
                <div className="recovery-banner">
                  <strong>Twoich zmian nie dało się scalić.</strong> Zbyt długo pracowałeś
                  offline i miejsce, do którego pisałeś, przestało istnieć. Twoja wersja
                  została zapisana jako <a href={`?doc=${doc.recovery.id}`}>{doc.recovery.title}</a>.
                  Nic nie przepadło.
                </div>
              )}
              {doc.unavailable && (
                <div className="unavailable-banner">
                  <strong>Nie możemy otworzyć tego dokumentu.</strong> Mógł zostać usunięty
                  albo nie masz do niego dostępu — poproś osobę, która przysłała Ci link,
                  żeby udostępniła go ponownie.
                </div>
              )}
              {doc.ready && doc.model && doc.undo && (
                <Editor
                  document={doc.model}
                  undo={doc.undo}
                  remoteCursors={doc.remoteCursors}
                  formatting={doc.formatting}
                  onLocalOps={doc.onLocalOps}
                  onToggleMark={doc.toggleMark}
                  onCursorMove={doc.onCursorMove}
                  onSelectionChange={doc.onSelectionChange}
                  onUndo={doc.runUndo}
                  onRedo={doc.runRedo}
                  readOnly={!doc.canWrite}
                  registerRemoteApply={doc.registerRemoteApply}
                />
              )}
            </div>
          </div>

          <footer className="statusbar">
            <span>{wordCount(doc.characters)}</span>
            <span className="sep" />
            <span data-testid="char-count">{doc.characters} znaków</span>
            <div className="statusbar-right">
              <span>{others.length + 1} online</span>
            </div>
          </footer>
        </main>

        {panel === 'history' ? (
          <HistoryPanel documentId={documentId} revision={doc.characters} readOnly={!doc.canWrite} />
        ) : (
          <CommentsPanel
            documentId={documentId}
            selection={doc.selection}
            revision={doc.characters}
            onAnchor={doc.anchorComment}
            readOnly={!doc.canComment}
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
