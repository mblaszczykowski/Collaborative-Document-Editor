import { useCallback, useEffect, useState } from 'react'
import { API_ORIGIN } from './config'
import { api, setAuthToken, type DocumentSummary, type Session, type AuthOptions } from './api'
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
import { DEBUG, SESSION_KEY, loadSession } from './ui/session'
import { useFailure } from './ui/useFailure'

/**
 * The application shell.
 *
 * **The interface shows only things that are a feature of the product.** The network simulator and
 * the CRDT state view — two tools that were invaluable while working on this project — are hidden
 * behind the `?debug=1` parameter and are seen by nobody who simply opened the editor (a
 * revised decision).
 *
 * The reason: a user has no idea what a tombstone or a version vector is, and a "packet loss"
 * slider above the document makes the product look like a laboratory rig. The diagnostics do not
 * disappear — they move to where they belong.
 */

export default function App() {
  const [session, setSession] = useState<Session | null>(loadSession)

  function remember(next: Session): void {
    sessionStorage.setItem(SESSION_KEY, JSON.stringify(next))
    setAuthToken(next.token)
    setSession(next)
  }

  return session ? <Workspace session={session} /> : <Login onLogin={remember} />
}

/** Where the API lives, for the sign-in links — which are full-page navigations, not fetches. */
const PROVIDER_LABELS: Record<string, string> = { google: 'Google', github: 'GitHub' }

/**
 * The sign-in screen.
 *
 * Two things reach it. Ordinarily it asks the server how sign-in works and renders a button per
 * configured provider. But it is also where the browser LANDS after a provider redirect, carrying
 * either `?code=` (a finished sign-in, to be exchanged for a token) or `?error=` (one that was not).
 * Both are handled before anything is drawn, so a returning user never sees the buttons flash.
 */
function Login({ onLogin }: { onLogin: (session: Session) => void }) {
  const [name, setName] = useState('')
  const [busy, setBusy] = useState(false)
  const [options, setOptions] = useState<AuthOptions | null>(null)
  const failure = useFailure()

  // The redirect handler. It runs once, before the options are fetched, because a code is only
  // ever valid for a moment and exchanging it is more urgent than drawing a screen the user is
  // about to leave.
  useEffect(() => {
    const params = new URLSearchParams(window.location.search)
    const code = params.get('code')
    const error = params.get('error')

    // The parameters come off the address bar either way. A spent code left in the URL survives a
    // refresh and produces a second, failing exchange — which looks to the user like the sign-in
    // broke rather than like it already worked.
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
      .attempt('Could not finish signing in. Please try again.', async () =>
        onLogin(await api.session(code)),
      )
      .finally(() => setBusy(false))
    // Deliberately once, on mount: `failure` and `onLogin` are stable, and re-running this would
    // re-exchange a code that is by then spent.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useEffect(() => {
    let cancelled = false
    void api
      .authOptions()
      .then((fetched) => {
        if (!cancelled) setOptions(fetched)
      })
      .catch(() => {
        // A server that cannot say how to sign in is a server that cannot sign anybody in. Showing
        // the buttons anyway would offer a door that leads nowhere; this at least says so.
        if (!cancelled) {
          setOptions({ providers: [], devLoginEnabled: false })
          failure.report('Could not reach the server. It may be starting up — try again shortly.')
        }
      })
    return () => {
      cancelled = true
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  async function submit(event: React.FormEvent) {
    event.preventDefault()
    setBusy(true)
    // There was a `finally` here and no `catch`, so a login that failed — a backend that is down,
    // which is the ordinary way to arrive at this screen and get nothing — re-enabled the button
    // and said nothing at all. The user presses it again, and again, with the interface behaving
    // exactly as it does when nothing is wrong. This is the FIRST screen of the application; it
    // is the worst possible place to be silent about a failure.
    try {
      await failure.attempt(
        'Could not sign in. The server may be unavailable — try again in a moment.',
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
          Inkline
        </div>
        <p>A document editor for real-time teamwork.</p>

        {options === null && <p className="login-hint">Checking how to sign in…</p>}

        {options?.providers.map((provider) => (
          <a
            key={provider}
            className="login-provider"
            // A full-page navigation rather than fetch: the whole point of the redirect flow is
            // that the browser visits the provider, and XHR cannot show somebody a consent screen.
            href={`${API_ORIGIN}/oauth2/authorization/${provider}`}
          >
            {busy ? 'Signing in…' : `Continue with ${PROVIDER_LABELS[provider] ?? provider}`}
          </a>
        ))}

        {options?.devLoginEnabled === true && (
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
            <button type="submit" disabled={busy}>
              {busy ? 'Connecting…' : 'Get started'}
            </button>
          </form>
        )}

        {options !== null
          && options.providers.length === 0
          && !options.devLoginEnabled && (
          <p className="login-hint">
            No sign-in method is configured on this server. See the README — a provider needs a
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

function Workspace({ session }: { session: Session }) {
  const [documents, setDocuments] = useState<DocumentSummary[]>([])
  const [openId, setOpenId] = useState<string | null>(null)

  const refresh = useCallback(async () => {
    const list = await api.listDocuments()
    setDocuments(list)
    return list
  }, [])

  const [bootstrapFailed, setBootstrapFailed] = useState(false)

  useEffect(() => {
    /**
     * Set by the cleanup, and CHECKED after every await below — which it was not.
     *
     * The flag existed and only the `.catch` consulted it, so the success path ran to completion
     * on an effect that had already torn down. Under `<StrictMode>` React deliberately mounts,
     * unmounts and remounts every effect in development, so two of these run concurrently — and
     * if the account has no documents yet, BOTH reach `api.createDocument('Q3 Product Roadmap')`
     * and two real documents are created on the server, on a first visit, every time. That is not
     * a development-only artifact: it leaves the user with a duplicate they have to delete.
     *
     * Guarding after each await also stops the rest: `setOpenId` on a workspace that is gone,
     * an invitation redeemed and then its token stripped from an address bar the next run has
     * already rewritten.
     */
    let cancelled = false
    void refresh().then(async (list) => {
      if (cancelled) return
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
          if (cancelled) return
          url.searchParams.set('doc', redeemed.documentId)
          window.history.replaceState({ doc: redeemed.documentId }, '', url)
          await refresh()
          if (cancelled) return
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
        if (cancelled) return
        setDocuments([created])
        setOpenId(created.id)
        return
      }
      setOpenId(list[0].id)
    }).catch(() => {
      // Without this the failure was an uncaught rejection in the console and a page stuck on
      // "Loading…" for ever: `openId` stayed null, so Workspace rendered the placeholder and
      // nothing ever replaced it. A list that cannot be fetched is a normal thing to survive —
      // an expired token, a backend that is down — and the user needs a way out of it.
      if (!cancelled) setBootstrapFailed(true)
    })
    return () => {
      cancelled = true
    }
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

  /**
   * Creates a document, and lets the failure travel.
   *
   * It deliberately does NOT catch: the button that triggers it lives inside `DocumentWorkspace`,
   * which is where a message about it has to appear, so the error is reported there. Before this
   * change nobody caught it at all — the button simply did nothing, no document appeared, and
   * nothing said why. Creating a document is a gesture people repeat when it looks like it did not
   * work, so silence here also risks a handful of real documents arriving at once when the
   * connection comes back.
   */
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
      {bootstrapFailed ? (
        <div className="loading">
          <p>The document list could not be loaded.</p>
          <button onClick={() => window.location.reload()}>Try again</button>
        </div>
      ): (
        <div className="loading">Loading…</div>
      )}
    </div>
  )
}

function DocumentWorkspace(props: {
  session: Session
  documentId: string
  documents: DocumentSummary[]
  onSelect: (id: string) => void
  /** Rejects on failure — this component owns the message, because it owns the screen. */
  onCreate: () => Promise<void>
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
   * The address, shown when the clipboard could not be reached.
   *
   * Not a failure message: nothing went wrong that the user needs explaining, they simply have to
   * copy it themselves. See `ui/browser.ts` for why a plain-`http:` deployment — which
   * `config.ts` supports on purpose, and which is how two browsers on a LAN demonstrate this
   * product — has no clipboard API at all.
   */
  const [linkToCopy, setLinkToCopy] = useState<string | null>(null)
  /**
   * Stable across renders, because `SharePanel` keys two effects on it.
   *
   * Written inline as `onClose={() => setSharing(false)}` it was a new function on every render of
   * this component — and this component re-renders on **every character typed**, because
   * `doc.characters` is state here. So while the share dialog was open, every keystroke behind it
   * tore down and re-registered the panel's Escape listener and its focus trap. It worked, and it
   * did a window-level listener swap per character for nothing.
   */
  const closeSharing = useCallback(() => setSharing(false), [])
  const failure = useFailure()
  /**
   * Sharing (CP21).
   *
   * The owner gets the panel; everybody else gets a copy of the address, which is what this
   * button did for everyone before. The difference is not cosmetic: since CP21 a new document
   * is private, so the address alone lets nobody in. Copying it is still a sensible gesture
   * for somebody without access management ("send me that again"), but it is the invitation
   * that opens the door — and only the owner can issue one.
   *
   * The copy used to be a bare `await navigator.clipboard.writeText(...)` in a function nothing
   * awaited. On any page that is not `https:` or `localhost` that property does not exist, so the
   * call threw a `TypeError` into an unhandled rejection: no copy, no "Copied", no message.
   */
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
          {/* An editor whose replica has no identifier yet cannot type, and is told so.
              The alternative considered was leaving only the "Connecting…" save indicator to
              carry it: rejected, because a caret blinking in a document that silently discards
              keystrokes is the interface telling somebody their work is being taken when it is
              not. It is a fraction of a second on a live connection and permanent on a dead one,
              and the second case is exactly the one worth naming. See `replicaAssigned`. */}
          {doc.canWrite && !doc.replicaAssigned && (
            <span
              className="role-badge"
              title="Waiting for the server to assign this browser a replica identifier — editing
                     starts the moment it arrives"
            >
              Connecting…
            </span>
          )}
          <button className="btn-outline" onClick={() => void share()}>
            {shared ? 'Copied' : 'Share'}
          </button>
          {linkToCopy !== null && (
            // The clipboard was not available, so the address goes on screen instead, selected,
            // for the user to copy by hand. A worse gesture than a working button and a far
            // better one than a button that quietly does nothing.
            <input
              className="share-fallback"
              readOnly
              value={linkToCopy}
              aria-label="Document address — copy it manually"
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
            {/* Block structure (CP30). The command and its styling both existed and nothing
                called it, so heading, quotation and list were unreachable through the product —
                the same shape of gap CP21 found in sharing, where grant and revoke were called
                only from tests. The original UI scope's rule cuts both ways: a button that does nothing is a
                lie in the interface, and a working gesture with no button is a feature that
                is not there. */}
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
                  may not have access to it — ask whoever sent you the link to share it again.
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
                  // Two conditions, not one. The role is the obvious half. The other is that this
                  // browser has not yet been told which replica it is, and until it has been, a
                  // keystroke mints a character identity from the placeholder id 0 — which a
                  // second browser in the same window mints too, so the merge algorithm treats two
                  // different characters as one and one author's text disappears with nothing said
                  // to either of them. `useCollaborativeDocument`'s `replicaAssigned` documents the
                  // whole path; the short version is that `ready` fires before the socket is even
                  // built, and it was the only gate here.
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
            // The character count alone would not do here: accepting a proposed INSERTION is the
            // only decision that alters marks without altering the text, and the list has to
            // follow it.
            //
            // This said DELETION, which is backwards, and the expression below it was right the
            // whole time. A proposed insertion is already IN the document carrying a suggestion
            // mark, so accepting it only takes the mark off — the server computes exactly that,
            // `textStays = accept == (kind == INSERT)` in `SuggestionService`. The other three
            // combinations all move characters and would have moved `doc.characters` on their own.
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
 * The formatting buttons.
 *
 * They act on the selection CodeMirror last reported, which is the same source the keyboard
 * shortcuts use. Clicking a button would normally take focus away from the editor and destroy the
 * selection first, which is what `onMouseDown` with `preventDefault` prevents — the selection is
 * still live when the click handler runs, and the hook's tracked range is still current.
 *
 * **This used to measure the range out of the DOM**, by summing the length of `Range.toString()`
 * over `.cm-content`. CodeMirror renders one `<div class="cm-line">` per line and
 * `Range.toString` concatenates only text-node data — it synthesises nothing for a block
 * boundary — so newlines were never counted and every offset was short by the number of lines
 * above the selection. On a document long enough to be virtualised only the lines near the caret
 * are in the DOM at all, so it was worse still. The bold landed on the wrong words, which reads as
 * a decision the author made.
 */
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

/**
 * Heading, quotation and list (CP30).
 *
 * Separate from `FormatButtons` because the gesture is different in kind: these act on the LINE the
 * caret is on rather than on the selection. The `preventDefault` on mousedown is still needed and
 * for the same reason — without it the button takes the focus, the editor loses the caret, and
 * there is no line to act on. The heading level travels as the mark's value, which is why
 * `heading` carries one and the other two do not.
 */
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
          // Called, unlike the reference that used to stand here. Without the call the button
          // takes focus on mousedown, CodeMirror loses the caret, and `toggleBlock` has no line to
          // act on — so heading, quotation and list did nothing at all when clicked, which is the
          // exact failure the comment above says this handler exists to prevent. `FormatButtons`
          // two functions up has always called it.
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
