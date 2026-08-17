import { useCallback, useEffect, useState } from 'react'
import { API_ORIGIN } from './config'
import {
  ApiError,
  api,
  setUnauthorizedHandler,
  type AuthOptions,
  type DocumentSummary,
  type Session,
} from './api'
import { Editor } from './editor/Editor'
import { useCollaborativeDocument } from './editor/useCollaborativeDocument'
import { DiagnosticsBar } from './ui/DiagnosticsBar'
import { DocumentList } from './ui/DocumentList'
import { initials, peopleLabel, wordCount } from './ui/format'
import { CommentsPanel } from './ui/CommentsPanel'
import { FailureNote } from './ui/FailureNote'
import { HistoryPanel } from './ui/HistoryPanel'
import { SaveIndicator } from './ui/SaveIndicator'
import { SharePanel } from './ui/SharePanel'
import { SuggestionsPanel } from './ui/SuggestionsPanel'
import { copyText } from './ui/browser'
import { DEBUG, SESSION_KEY, forgetSession, loadSession, rememberSession } from './ui/session'
import { useFailure } from './ui/useFailure'

export default function App() {
  const [session, setSession] = useState<Session | null>(loadSession)
  const [notice, setNotice] = useState<string | null>(null)

  function remember(next: Session): void {
    rememberSession(next)
    setSession(next)
    setNotice(null)
  }

  useEffect(() => {
    const onStorage = (event: StorageEvent) => {
      if (event.key !== null && event.key !== SESSION_KEY) return
      setSession(loadSession())
    }
    window.addEventListener('storage', onStorage)
    return () => window.removeEventListener('storage', onStorage)
  }, [])

  const signOut = useCallback((reason?: string) => {
    forgetSession()
    setSession(null)
    setNotice(reason ?? null)
  }, [])

  useEffect(() => {
    setUnauthorizedHandler(() => signOut('Your session expired. Please sign in again.'))
    return () => setUnauthorizedHandler(null)
  }, [signOut])

  return session ? (
    <Workspace session={session} onSignOut={signOut} />
  ) : (
    <Login onLogin={remember} notice={notice} />
  )
}

const FIRST_DOCUMENT_TITLE = 'Welcome to the Collaborative Document Editor'

const PROVIDER_LABELS: Record<string, string> = { google: 'Google', github: 'GitHub' }

const RETURN_KEY = 'collabedit.return'

function Login({ onLogin, notice }: {
  onLogin: (session: Session) => void
  notice: string | null
}) {
  const [name, setName] = useState('')
  const [busy, setBusy] = useState(() =>
    new URLSearchParams(window.location.search).has('code'))
  const [options, setOptions] = useState<AuthOptions | null>(null)
  const [optionsFailed, setOptionsFailed] = useState(false)
  const failure = useFailure()

  useEffect(() => {
    const params = new URLSearchParams(window.location.search)
    const code = params.get('code')
    const error = params.get('error')

    if (code !== null || error !== null) {
      window.history.replaceState({}, '', window.location.pathname)
    }
    if (error !== null) {
      failure.report(
        error === 'no-subject'
          ? 'That provider did not identify the account. Try a different one.'
          : 'Sign-in did not complete. Please try again.',
      )
      return
    }
    if (code === null) return

    setBusy(true)
    void failure
      .attempt('Could not finish signing in. Please try again.', async () => {
        const session = await api.session(code)
        const arrivedAt = sessionStorage.getItem(RETURN_KEY)
        sessionStorage.removeItem(RETURN_KEY)
        if (arrivedAt !== null && arrivedAt !== '') {
          window.history.replaceState({}, '', window.location.pathname + arrivedAt)
        }
        onLogin(session)
      })
      .finally(() => setBusy(false))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const loadOptions = useCallback(() => {
    setOptionsFailed(false)
    void api
      .authOptions()
      .then(setOptions)
      .catch(() => {
        setOptionsFailed(true)
      })
  }, [])

  useEffect(loadOptions, [loadOptions])

  async function submit(event: React.FormEvent) {
    event.preventDefault()
    setBusy(true)
    try {
      await failure.attempt(
        'Could not sign in. The server may be unavailable - try again in a moment.',
        async () => onLogin(await api.login(name)),
      )
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="login">
      <div className="login-card">
        <div className="login-brand">
          <span className="brand-mark" />
          Collaborative Document Editor
        </div>
        <p>A document editor for real-time teamwork.</p>

        {
}
        {notice !== null && <p className="login-hint">{notice}</p>}

        {
}
        {busy ? (
          <p className="login-hint">Signing you in…</p>
        ) : optionsFailed ? (
          <>
            <p className="login-hint">
              Could not reach the server. It may still be starting up.
            </p>
            <button type="button" onClick={loadOptions}>Try again</button>
          </>
        ) : (
          <>
            {options === null && <p className="login-hint">Checking how to sign in…</p>}

            {options?.providers.map((provider) => (
              <a
                key={provider}
                className="login-provider"
                href={`${API_ORIGIN}/oauth2/authorization/${provider}`}
                onClick={() => sessionStorage.setItem(RETURN_KEY, window.location.search)}
              >
                Continue with {PROVIDER_LABELS[provider] ?? provider}
              </a>
            ))}
          </>
        )}

        {!busy && !optionsFailed && options?.devLoginEnabled === true && (
          <form className="login-dev" onSubmit={submit}>
            {options.providers.length > 0 && (
              <p className="login-hint">or, for local development</p>
            )}
            <input
              value={name}
              onChange={(event) => setName(event.target.value)}
              placeholder="Your name"
              aria-label="Your name"
            />
            {
}
            <button type="submit" disabled={name.trim() === ''}>
              Get started
            </button>
          </form>
        )}

        {!busy
          && !optionsFailed
          && options !== null
          && options.providers.length === 0
          && !options.devLoginEnabled && (
          <p className="login-hint">
            No sign-in method is configured on this server. See the README - a provider needs a
            client id and secret, or set COLLABEDIT_DEV_LOGIN=true for local development.
          </p>
        )}

        {failure.message !== null && (
          <FailureNote message={failure.message} onDismiss={failure.dismiss} />
        )}
      </div>
    </div>
  )
}

function Workspace({ session, onSignOut }: {
  session: Session
  onSignOut: (reason?: string) => void
}) {
  const [documents, setDocuments] = useState<DocumentSummary[]>([])
  const [openId, setOpenId] = useState<string | null>(null)

  const refresh = useCallback(async () => {
    const list = await api.listDocuments()
    setDocuments(list)
    return list
  }, [])

  const [bootstrapFailed, setBootstrapFailed] = useState(false)

  useEffect(() => {
    let cancelled = false
    void refresh().then(async (list) => {
      if (cancelled) return
      const params = new URLSearchParams(window.location.search)

      const invite = params.get('invite')
      if (invite !== null) {
        const url = new URL(window.location.href)
        url.searchParams.delete('invite')
        try {
          const redeemed = await api.redeemInvite(invite)
          if (cancelled) return
          url.searchParams.set('doc', redeemed.documentId)
          window.history.replaceState({ doc: redeemed.documentId }, '', url)
          await refresh()
          if (cancelled) return
          setOpenId(redeemed.documentId)
          return
        } catch {
          window.history.replaceState({}, '', url)
        }
      }

      const requested = params.get('doc')
      if (requested !== null) {
        setOpenId(requested)
        return
      }
      if (list.length === 0) {
        const created = await api.createDocument(FIRST_DOCUMENT_TITLE)
        if (cancelled) return
        setDocuments([created])
        setOpenId(created.id)
        return
      }
      setOpenId(list[0].id)
    }).catch((error: unknown) => {
      if (cancelled) return
      if (error instanceof ApiError && error.status === 401) {
        onSignOut('Your session expired. Please sign in again.')
        return
      }
      setBootstrapFailed(true)
    })
    return () => {
      cancelled = true
    }
  }, [refresh, onSignOut])

  const select = useCallback((id: string) => {
    setOpenId(id)
    const url = new URL(window.location.href)
    url.searchParams.set('doc', id)
    window.history.pushState({ doc: id }, '', url)
  }, [])

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
      onSignOut={onSignOut}
    />
  ) : (
    <div className="app">
      {bootstrapFailed ? (
        <div className="loading">
          <p>The document list could not be loaded.</p>
          <button onClick={() => window.location.reload()}>Try again</button>
          {
}
          <button className="linklike" onClick={onSignOut}>Sign out</button>
        </div>
      ): (
        <div className="loading">Loading…</div>
      )}
    </div>
  )
}

function AccountMenu({ session, onSignOut }: { session: Session; onSignOut: () => void }) {
  const [open, setOpen] = useState(false)

  useEffect(() => {
    if (!open) return
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setOpen(false)
    }
    const onPointerDown = (event: PointerEvent) => {
      if (!(event.target as Element | null)?.closest('.account')) setOpen(false)
    }
    window.addEventListener('keydown', onKey)
    window.addEventListener('pointerdown', onPointerDown)
    return () => {
      window.removeEventListener('keydown', onKey)
      window.removeEventListener('pointerdown', onPointerDown)
    }
  }, [open])

  return (
    <div
      className="account"
      onBlur={(event) => {
        if (!event.currentTarget.contains(event.relatedTarget)) setOpen(false)
      }}
    >
      <button
        type="button"
        className="avatar avatar-self"
        style={{ background: session.color }}
        aria-expanded={open}
        aria-label={`Account: ${session.displayName}`}
        onClick={() => setOpen((current) => !current)}
      >
        {initials(session.displayName)}
      </button>
      {open && (
        <div className="account-menu">
          <div className="account-who">
            <strong>{session.displayName}</strong>
            {
}
            <span>Signed in</span>
          </div>
          <button type="button" onClick={onSignOut}>
            Sign out
          </button>
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
  onCreate: () => Promise<void>
  onRefresh: () => void
  onSignOut: () => void
}) {
  const { session, documentId } = props

  const doc = useCollaborativeDocument(documentId, session)

  const [panel, setPanel] = useState<'history' | 'comments' | 'suggestions'>('history')
  const [shared, setShared] = useState(false)
  const [sharing, setSharing] = useState(false)
  const [linkToCopy, setLinkToCopy] = useState<string | null>(null)
  const closeSharing = useCallback(() => setSharing(false), [])
  const failure = useFailure()
  async function share() {
    if (doc.role === 'owner') {
      setSharing(true)
      return
    }
    const address = window.location.href
    if (await copyText(address)) {
      setShared(true)
      setLinkToCopy(null)
      setTimeout(() => setShared(false), 2000)
      return
    }
    setLinkToCopy(address)
  }

  const others = doc.presence.filter((entry) => entry.userId !== session.userId)

  return (
    <div className="app">
      <header className="menubar">
        <div className="brand">
          <span className="brand-mark" />
          Collaborative Document Editor
        </div>
        <span className="menu-divider" />
        <span className="doc-title">{doc.title}</span>

        <div className="menubar-right">
          <SaveIndicator state={doc.connectionState} pending={doc.pendingCount} />
          <div className="presence">
            <AccountMenu session={session} onSignOut={props.onSignOut} />
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
            <span className="role-badge" title="This instance cannot reach the cluster channel">
              List may be incomplete
            </span>
          )}
          {!doc.canWrite && (
            <span className="role-badge" title="Your role in this document">
              {doc.canComment ? 'You can comment' : 'Read-only'}
            </span>
          )}
          {
}
          {doc.canWrite && !doc.replicaAssigned && (
            <span
              className="role-badge"
              title="Waiting for the server to assign this browser a replica identifier - editing
                     starts the moment it arrives"
            >
              Connecting…
            </span>
          )}
          <button className="btn-outline" onClick={() => void share()}>
            {shared ? 'Copied' : 'Share'}
          </button>
          {linkToCopy !== null && (
            <input
              className="share-fallback"
              readOnly
              value={linkToCopy}
              aria-label="Document address - copy it manually"
              autoFocus
              onFocus={(event) => event.target.select()}
            />
          )}
        </div>
      </header>

      {sharing && <SharePanel documentId={documentId} onClose={closeSharing} />}

      {DEBUG && (
        <DiagnosticsBar
          network={doc.network}
          onChange={doc.updateNetwork}
          document={doc.model}
        />
      )}

      <div className="body-row">
        <DocumentList
          documents={props.documents}
          openId={documentId}
          onSelect={props.onSelect}
          onCreate={() =>
            void failure.attempt(
              'Could not create the document. Check your connection and try again.',
              props.onCreate,
            )
          }
          onChanged={props.onRefresh}
        />

        <main className="canvas-col">
          <div className="editor-toolbar">
            <FormatButtons onToggle={doc.toggleMark} disabled={!doc.canWrite} />
            <span className="tb-divider" />
            {
}
            <BlockButtons onToggle={doc.toggleBlock} disabled={!doc.canWrite} />
            <span className="tb-divider" />
            <button
              className={`tb-icon${panel === 'comments' ? ' active' : ''}`}
              title="Comments"
              aria-label="Comments"
              onClick={() => setPanel((current) => (current === 'comments' ? 'history' : 'comments'))}
            >
              💬
            </button>
            {
}
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
              {doc.notice !== null && (
                <div className="notice-banner" role="status">
                  <span>{doc.notice}</span>
                  <button
                    className="notice-dismiss"
                    onClick={doc.dismissNotice}
                    aria-label="Dismiss">
                    ×
                  </button>
                </div>
              )}
              {doc.unavailable && (
                <div className="unavailable-banner">
                  <strong>We cannot open this document.</strong> It may have been deleted, or you
                  may not have access to it - ask whoever sent you the link to share it again.
                </div>
              )}
              {failure.message !== null && (
                <FailureNote message={failure.message} onDismiss={failure.dismiss} />
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
                  readOnly={!doc.canWrite || !doc.replicaAssigned}
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
            captureAnchor={doc.captureAnchor}
            onAnchorAt={doc.anchorCommentAt}
            readOnly={!doc.canComment}
          />
        )}
        {panel === 'suggestions' && (
          <SuggestionsPanel
            documentId={documentId}
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

function FormatButtons(
  { onToggle, disabled }:
    { onToggle: (type: string) => void; disabled: boolean },
) {
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
          onClick={() => onToggle(button.type)}
          disabled={disabled}
        >
          {button.render}
        </button>
      ))}
    </>
  )
}

function BlockButtons(
  { onToggle, disabled }:
    { onToggle: (type: string, value: string | null) => void; disabled: boolean },
) {
  const buttons: { type: string; value: string | null; label: string; render: React.ReactNode }[] = [
    { type: 'heading', value: '1', label: 'Heading', render: <span>H1</span> },
    { type: 'heading', value: '2', label: 'Subheading', render: <span>H2</span> },
    { type: 'quote', value: null, label: 'Quotation', render: <span>&ldquo;</span> },
    { type: 'list', value: null, label: 'List item', render: <span>&#8226;</span> },
  ]

  return (
    <>
      {buttons.map((button) => (
        <button
          key={button.label}
          className="tb-icon tb-format"
          title={button.label}
          aria-label={button.label}
          onMouseDown={(event) => event.preventDefault()}
          onClick={() => onToggle(button.type, button.value)}
          disabled={disabled}
        >
          {button.render}
        </button>
      ))}
    </>
  )
}
