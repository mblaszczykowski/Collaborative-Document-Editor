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
import { SuggestionsPanel } from './ui/SuggestionsPanel'
import { DEBUG, SESSION_KEY, loadSession } from './ui/session'

/**
 * The application shell.
 *
 * **The interface shows only things that are a feature of the product.** The network simulator and
 * the CRDT state view — two tools that were invaluable while working on this project — are hidden
 * behind the `?debug=1` parameter and are seen by nobody who simply opened the editor (ADR-0007, a
 * revised decision).
 *
 * The reason: a user has no idea what a tombstone or a version vector is, and a "packet loss"
 * slider above the document makes the product look like a laboratory rig. The diagnostics do not
 * disappear — they move to where they belong.
 */

export default function App() {
  const [session, setSession] = useState<Session | null>(loadSession)

  // The token has to be set BEFORE the first REST request goes out — including for a session
  // restored from sessionStorage, where there is no login at which to set it. useState with an
  // initialiser function runs before the first render, so setting it here precedes any useEffect in
  // child components.
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
        <p>A document editor for real-time teamwork.</p>
        <input
          value={name}
          onChange={(event) => setName(event.target.value)}
          placeholder="Your name"
          autoFocus
        />
        <button type="submit" disabled={busy}>
          {busy ? 'Connecting…' : 'Get started'}
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
   * The address is the source of truth about what is open.
   *
   * `pushState`, not `replaceState`: the browser's back button should return to the previous
   * document. With `replaceState` the browser history contained no switches, so "back" threw the
   * user out of the application — behaviour nobody expects from a document list.
   */
  const select = useCallback((id: string) => {
    setOpenId(id)
    const url = new URL(window.location.href)
    url.searchParams.set('doc', id)
    window.history.pushState({ doc: id }, '', url)
  }, [])

  // Browser navigation (back/forward) has to switch documents, otherwise the address and the
  // content diverge after the first click of "back".
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
      <div className="loading">Loading…</div>
    </div>
  )
}

function DocumentWorkspace(props: {
  session: Session
  documentId: string
  documents: DocumentSummary[]
  onSelect: (id: string) => void
  onCreate: () => void
  /** The list needs refreshing — after a rename, a delete or a restore. */
  onRefresh: () => void
}) {
  const { session, documentId } = props

  // Everything about the document itself lives in the hook: opening it, the replica, the socket,
  // the commands and their permission checks. What is left here is what the page LOOKS like.
  // The split is described in useCollaborativeDocument; the short version is that only one of
  // those two changes when somebody redesigns the page.
  const doc = useCollaborativeDocument(documentId, session)

  const [panel, setPanel] = useState<'history' | 'comments' | 'suggestions'>('history')
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
          {!doc.presenceComplete && (
            // The list may be missing people connected to another instance (CP26). Said out
            // loud rather than hidden: a participant list that is quietly short looks exactly
            // like a document nobody else is in, and that is a worse lie than an honest doubt.
            <span className="role-badge" title="This instance cannot reach the cluster channel">
              List may be incomplete
            </span>
          )}
          {!doc.canWrite && (
            <span className="role-badge" title="Your role in this document">
              {doc.canComment ? 'You can comment' : 'Read-only'}
            </span>
          )}
          <button className="btn-outline" onClick={share}>
            {shared ? 'Copied' : 'Share'}
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
              title="Comments"
              aria-label="Comments"
              onClick={() => setPanel((current) => (current === 'comments' ? 'history' : 'comments'))}
            >
              💬
            </button>
            {/* Suggestions (CP23). Shown to anybody who may comment — which includes editors,
                because proposing a change to somebody else's paragraph is a reasonable thing to
                do even when you could simply make it. */}
            <button
              className={`tb-icon${panel === 'suggestions' ? ' active' : ''}`}
              title="Suggestions"
              aria-label="Suggestions"
              disabled={!doc.canComment}
              onClick={() =>
                setPanel((current) => (current === 'suggestions' ? 'history' : 'suggestions'))
              }
            >
              ✍️
            </button>
            <span className="tb-divider" />
            <button className="tb-icon" onClick={doc.runUndo} title="Undo (⌘Z)" aria-label="Undo"
              disabled={!doc.canWrite}>
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                <path d="M3 10h10a4 4 0 0 1 0 8H8" />
                <path d="M7 6l-4 4 4 4" />
              </svg>
            </button>
            <button className="tb-icon" onClick={doc.runRedo} title="Redo (⇧⌘Z)" aria-label="Redo"
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
                {others.length === 0 ? 'Only you' : `${peopleLabel(others.length + 1)} in the document`}
              </div>
              {doc.recovery !== null && (
                <div className="recovery-banner">
                  <strong>Your changes could not be merged.</strong> You were offline for too
                  long and the place you were writing into no longer exists. Your version has been
                  saved as <a href={`?doc=${doc.recovery.id}`}>{doc.recovery.title}</a>. Nothing
                  was lost.
                </div>
              )}
              {doc.unavailable && (
                <div className="unavailable-banner">
                  <strong>We cannot open this document.</strong> It may have been deleted, or you
                  may not have access to it — ask whoever sent you the link to share it again.
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
            <span data-testid="char-count">{doc.characters} characters</span>
            <div className="statusbar-right">
              <span>{others.length + 1} online</span>
            </div>
          </footer>
        </main>

        {panel === 'history' && (
          <HistoryPanel documentId={documentId} revision={doc.characters} readOnly={!doc.canWrite} />
        )}
        {panel === 'comments' && (
          <CommentsPanel
            documentId={documentId}
            selection={doc.selection}
            revision={doc.characters}
            onAnchor={doc.anchorComment}
            readOnly={!doc.canComment}
          />
        )}
        {panel === 'suggestions' && (
          <SuggestionsPanel
            documentId={documentId}
            // The character count alone would not do here: accepting a proposed DELETION is the
            // only kind of change that alters marks without altering the text, and the list has
            // to follow it.
            revision={doc.characters + doc.formatting.length}
            canPropose={doc.canComment}
            canDecide={doc.canWrite}
            selection={doc.selection}
            onProposeDeletion={doc.proposeDeletion}
            onProposeInsertion={doc.proposeInsertion}
          />
        )}
      </div>
    </div>
  )
}

/**
 * The save indicator — the only information about the connection state a user sees.
 *
 * Deliberately in the product's language ("Saved", "Working offline") rather than the protocol's
 * ("resynchronising", "N operations buffered"). The user should know whether their work is safe —
 * not how the transport works.
 */

/**
 * The formatting buttons.
 *
 * They act on the CURRENT SELECTION read from the DOM, not from React state. The reason is
 * practical: clicking a button takes focus away from the editor, so if the selection were held in
 * state and updated by an event, it would already be empty by the time of the click. `onMouseDown`
 * with `preventDefault` stops the focus loss before it happens.
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

    // Offsets are computed relative to the editor's content, not a DOM node — CodeMirror splits
    // the text across many elements, so an offset within a node says nothing about the position in
    // the document.
    const range = selection.getRangeAt(0)
    const before = range.cloneRange()
    before.selectNodeContents(editor)
    before.setEnd(range.startContainer, range.startOffset)
    const from = before.toString().length
    const to = from + range.toString().length
    if (from < to) onToggle(type, from, to)
  }

  const buttons: { type: string; label: string; title: string; render: React.ReactNode }[] = [
    { type: 'bold', label: 'Bold', title: 'Bold (⌘B)', render: <strong>B</strong> },
    { type: 'italic', label: 'Italic', title: 'Italic (⌘I)', render: <em>I</em> },
    { type: 'underline', label: 'Underline', title: 'Underline (⌘U)', render: <u>U</u> },
    { type: 'code', label: 'Code', title: 'Code', render: <code>{'<>'}</code> },
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
