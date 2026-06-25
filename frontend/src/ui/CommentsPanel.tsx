import { useCallback, useEffect, useRef, useState } from 'react'
import { offlineStore } from '../transport/offlineStore'
import { ApiError, api, type CommentThread } from '../api'
import type { Id } from '../crdt/types'
import type { AnchorOutcome } from '../editor/useCollaborativeDocument'
import { FailureNote } from './FailureNote'
import { clientId } from './browser'
import { formatTime, initials } from './format'
import { usePanelData } from './usePanelData'

/**
 * The comments panel (CP14).
 *
 * **A detached thread does not disappear.** When the anchored text is deleted, the anchor stops
 * covering anything — but the conversation stays, shown with the fragment quoted at the time it was
 * opened. Deleting a conversation in response to a text edit is behaviour nobody expects, and the
 * context behind a decision is usually the only reason anyone comes back to comments months later.
 *
 * **Resolving is not deletion either** — the thread leaves the default list and comes back when the
 * view is toggled.
 */
/**
 * Whether the server refused in a way that repeating will not fix (CP27 review).
 *
 * 4xx other than 408 (timeout) and 429 (too many requests) is the server saying "not this, ever" —
 * access was revoked while the comment sat in the outbox, or the thread it replies to is gone.
 * Anything else, including a failure that never reached a server at all, is worth retrying.
 *
 * This used to recover the status by running a regular expression over the error's MESSAGE — a
 * parser for a format nothing declared, one string edit away from silently answering "not
 * permanent" to everything and turning the outbox into a queue that retries a refusal for ever.
 * `ApiError` carries the status, so the question is now asked of the thing that knows the answer.
 */
function permanentlyRefused(error: unknown): boolean {
  if (!(error instanceof ApiError)) return false
  return error.status >= 400 && error.status < 500 && error.status !== 408 && error.status !== 429
}

/**
 * The longest quotation the server will store — `CommentController.MAX_QUOTE_LENGTH`.
 *
 * Truncated HERE rather than left to be refused there. The panel used to send the raw selection,
 * so quoting more than eight thousand characters produced a 400, and `permanentlyRefused` reads
 * every 4xx as final: the queued comment was deleted and the user was told they no longer had
 * access to the document. Losing somebody's writing and giving the wrong reason for it, over a
 * limit neither side showed them.
 *
 * Truncating loses nothing that matters: the quotation is an excerpt for the panel to display, and
 * WHAT the comment is attached to is the character range, which is sent separately and is not
 * bounded by this.
 */
const MAX_QUOTE_LENGTH = 8_192

function quoteOf(selection: string): string {
  return selection.length <= MAX_QUOTE_LENGTH ? selection : `${selection.slice(0, MAX_QUOTE_LENGTH - 1)}…`
}

/** Module-level, so the initial value is not a new array on every render. */
const NO_THREADS: CommentThread[] = []

export function CommentsPanel(props: {
  documentId: string
  /** The selected fragment of text; empty when nothing is selected. */
  selection: string
  /** Increments on every document change — forces the anchoring state to refresh. */
  revision: number
  onAnchor: (threadId: string) => void
  /** The selection as character identifiers, captured while the selection still exists (CP27). */
  captureAnchor: () => { from: Id; to: Id } | null
  /** Places the anchor for a comment that waited in the outbox; false when the text is gone. */
  onAnchorAt: (threadId: string, range: { from: Id; to: Id }) => AnchorOutcome
  /**
   * The role does not permit commenting (`viewer`).
   *
   * The panel stays visible — a reader is entitled to READ the conversation, just not add to it.
   * Hiding the panel would take away the context it is usually opened for.
   */
  readOnly?: boolean
}) {
  const [showResolved, setShowResolved] = useState(false)
  const [draft, setDraft] = useState('')
  const [replyTo, setReplyTo] = useState<string | null>(null)
  const [replyDraft, setReplyDraft] = useState('')
  /** Comments written with no connection, waiting in the outbox (CP27). */
  const [queued, setQueued] = useState(0)
  /**
   * Threads that were sent from the outbox and could not be anchored (CP27).
   *
   * The server computes `thread.anchored` from the `comment` mark, so a thread with no mark comes
   * back as `false` — indistinguishable, from the outside, from a thread whose anchored text
   * somebody deleted. Those are different things to tell a person: one is "the document changed
   * under your comment", the other is "the text was already gone when this was finally sent". The
   * panel used to say the first about both, which reports a change to the document that did not
   * happen.
   *
   * Held in component state, so the distinction is lost on reload and the badge falls back to
   * "detached". That is a real limit and it is deliberate: persisting it would mean a second
   * durable record of something the server already half-knows, and being vague after a reload is
   * much cheaper than being wrong now.
   */
  const [neverAnchored, setNeverAnchored] = useState<ReadonlySet<string>>(new Set)

  /**
   * The list, the debounce, the stale guard, the swallowed 404 and the failure message.
   *
   * This panel previously had the debounce and the 404 rule and **not** the stale guard, so a slow
   * answer for revision N landing after the answer for N+1 showed a thread list that does not
   * match the document — and the panel's own state (`neverAnchored`, the unsent badge) makes that
   * look authoritative. `useDebouncedValue` says in its own documentation that debouncing does not
   * cover this; two of the three panels had read it.
   *
   * `showResolved` is part of `load`, so toggling it re-fetches — that is the same effect the
   * separate `reload` dependency used to have, expressed once.
   */
  const panel = usePanelData({
    load: useCallback(
      () => api.listComments(props.documentId, showResolved),
      [props.documentId, showResolved],
    ),
    revision: props.revision,
    initial: NO_THREADS,
    describe: 'the comments',
  })
  const threads = panel.data
  const { failure } = panel

  /**
   * Posts a comment, or keeps it until there is a connection (CP27).
   *
   * The offline buffer built in CP6 covers CRDT operations, because those travel the WebSocket.
   * A comment travels REST, so before CP27 one written with no connection simply vanished —
   * a limit the README named and nobody had hit in a demo, because demos have a network.
   *
   * The `clientId` is generated here and repeated on every retry, which is the whole mechanism:
   * what an offline comment needs is not merging but exactly-once, and the server turns a
   * repeated key into a no-op (holds — comments still are not a CRDT).
   */
  async function open() {
    if (draft.trim() === '') return
    const body = draft.trim()
    const quotedText = quoteOf(props.selection)
    setDraft('')
    // Inside the try, and that is finding 5 rather than a tidy-up. `crypto.randomUUID` exists only
    // in a secure context, and `config.ts` deliberately supports the `http:` shape, so on
    // `http://<lan-ip>` — the obvious way to show two browsers editing one document — this call
    // threw a `TypeError` from the line ABOVE the try. The draft had already been cleared, the
    // rejection went nowhere, and the comment vanished with no message and no queue entry: the one
    // path in this file whose entire purpose is not losing the comment. `clientId` in
    // `ui/browser.ts` does not throw.
    try {
      const key = clientId
      // Captured INSIDE the try, for the same reason `clientId` is — and it is the same shape of
      // bug, one line apart. `captureAnchor` reaches `idAtVisible`, which throws rather than
      // returning null on a position it cannot resolve; a selection that CodeMirror and the CRDT
      // disagree about would have thrown from ABOVE the try, after the draft was cleared, and the
      // comment would have gone with no message and no queue entry. I could not construct a
      // reachable case — `Editor` asserts the two agree — but "unreachable" is what the line above
      // was believed to be until `http://<lan-ip>` made it reachable.
      //
      // Identifiers rather than offsets, so that the anchor survives other people typing before it
      // while this sits in the outbox.
      const anchor = props.captureAnchor()
      try {
        const created = await api.openThread(props.documentId, quotedText, body, key())
        // The anchor is created AFTER the thread, because it needs the thread's identifier as the
        // mark's value. It travels by a different route — the WebSocket, as an ordinary CRDT
        // operation.
        props.onAnchor(created.id)
      } catch {
        // The anchor is deliberately NOT placed here. It would need a thread identifier that does
        // not exist yet, and inventing one would put a mark in the document pointing at a
        // conversation that may never be created. It is REMEMBERED instead, and `flush` places it
        // once the thread exists.
        await offlineStore.queueComment({
          clientId: key(),
          documentId: props.documentId,
          kind: 'thread',
          quotedText,
          anchor: anchor ?? undefined,
          body,
          queuedAt: Date.now(),
        })
        setQueued((current) => current + 1)
      }
    } catch (error) {
      // The outbox itself refused — a browser blocking IndexedDB, a private window, storage that
      // is full. Before this the rejection escaped `void open` unhandled, and the comment was
      // gone with the interface showing nothing at all. The text is put back in the draft, because
      // the only copy of it left is the one the user typed.
      console.error('the comment could not be queued', error)
      setDraft(body)
      failure.report('This browser will not let the editor keep unsent comments, so this one '
        + 'could not be saved. It is back in the box — try again when you are connected.')
    }
    // `panel.refresh()`, with the parentheses. This read `await refresh` — awaiting the FUNCTION,
    // which resolves immediately to the function object and refreshes nothing. So a posted comment
    // did not appear until the debounced revision reload happened to fire, which on a document
    // nobody is typing in is never.
    await panel.refresh()
  }

  async function reply(threadId: string) {
    if (replyDraft.trim() === '') return
    const body = replyDraft.trim()
    setReplyDraft('')
    setReplyTo(null)
    try {
      const key = clientId
      try {
        await api.replyToThread(threadId, body, key())
      } catch {
        await offlineStore.queueComment({
          clientId: key(),
          documentId: props.documentId,
          kind: 'reply',
          threadId,
          body,
          queuedAt: Date.now(),
        })
        setQueued((current) => current + 1)
      }
    } catch (error) {
      // As in `open` — the reply is handed back rather than dropped.
      console.error('the reply could not be queued', error)
      setReplyDraft(body)
      setReplyTo(threadId)
      failure.report('This browser will not let the editor keep unsent comments, so this reply '
        + 'could not be saved. It is back in the box — try again when you are connected.')
    }
    await panel.refresh()
  }

  /**
   * Sends what is waiting, oldest first.
   *
   * Retried on every panel refresh and when the browser reports it is back online. A comment that
   * already arrived is recognised by its key and does not become a duplicate, so retrying costs
   * nothing and missing an opportunity to retry costs a comment.
   */
  /**
   * The current refresh, reporting and anchoring callbacks, reachable without depending on them.
   *
   * `flush` used to list `reload` in its dependencies, and the list fetch depends on
   * `showResolved`. So pressing "Show resolved" rebuilt `flush`, which tore down and re-registered
   * the `online` listener and re-read the whole IndexedDB outbox — a storage sweep triggered by a
   * display toggle. The effect below depends on the document alone, which is the only thing that
   * genuinely changes what is in the outbox.
   *
   * A ref rather than a dependency, because `flush` still has to call the CURRENT versions: it
   * runs long after the render that created it, and calling a stale refresh would list comments
   * for whichever setting was showing when the listener was registered.
   */
  const live = useRef({ refresh: panel.refresh, report: failure.report,
    onAnchorAt: props.onAnchorAt })
  live.current = { refresh: panel.refresh, report: failure.report,
    onAnchorAt: props.onAnchorAt }

  const flush = useCallback(async () => {
    const waiting = await offlineStore.pendingComments(props.documentId).catch(() => [])
    if (waiting.length === 0) {
      setQueued(0)
      return
    }
    let sent = 0
    let dropped = 0
    const unanchored: string[] = []
    for (const entry of waiting) {
      try {
        if (entry.kind === 'thread') {
          const created = await api.openThread(
            entry.documentId,
            entry.quotedText ?? '',
            entry.body,
            entry.clientId,
          )
          // The thread's identifier is USED now rather than thrown away with a `void created`.
          // This is the half of CP27 that did not land: the thread was created and no `comment`
          // mark was ever placed, so the server reported the comment as unanchored and the panel
          // told the user the text it referred to had been deleted — about text nobody had
          // touched. The anchor was remembered at queue time as two character identifiers, so it
          // still points at the right characters however much was typed around them.
          if (entry.anchor !== undefined) {
            // ONLY 'gone' is recorded. A `not-ready` answer says this replica cannot place the
            // mark yet — the document is still opening, or the role has not come back — and
            // treating that as "the text was deleted" told the user their document had changed
            // when nothing had. The thread exists either way; what it must not do is claim a
            // reason it does not have.
            if (live.current.onAnchorAt(created.id, entry.anchor) === 'gone') {
              unanchored.push(created.id)
            }
          } else {
            // Written with nothing selected, so there never was an anchor to place. Recorded all
            // the same, so the badge says "never anchored" rather than "detached" — the second
            // claims the document changed.
            unanchored.push(created.id)
          }
        } else if (entry.threadId !== undefined) {
          await api.replyToThread(entry.threadId, entry.body, entry.clientId)
        }
        await offlineStore.forgetComment(entry.clientId)
        sent++
      } catch (error) {
        if (permanentlyRefused(error)) {
          // Not a connection problem: the server will answer the same way forever. Access was
          // revoked while this sat in the outbox, or the thread it replies to is gone. Keeping
          // it means retrying on every panel refresh and a badge that never clears — a queue
          // that cannot drain is worse than a lost comment, because it also hides the ones that
          // could still be sent.
          await offlineStore.forgetComment(entry.clientId).catch(() => {})
          // The reason is read from the STATUS rather than assumed. Every 4xx used to be reported
          // as lost access, which was the wrong sentence for the one that actually happened: a
          // 400 from a length the interface never showed anybody. Telling a user their access was
          // revoked when it was not sends them to ask the wrong person about the wrong thing.
          // Said out loud at all, because a permanent refusal removes the comment from the outbox
          // — so without a message the work disappears between two renders. The badge goes from
          // "1 unsent" to nothing, which is exactly what a queue that DRAINED looks like, and only
          // one of those two is good news.
          //
          // Through `useFailure` rather than a `failed` state of this panel's own. That state
          // rendered as a `<button>` whose label was the entire error message: a control whose
          // accessible name is two sentences, and whose only affordance is that clicking the
          // sentence dismisses it. `FailureNote` is what the rest of the interface uses.
          const status = error instanceof ApiError ? error.status : 0
          live.current.report(status === 403 || status === 404
            ? 'One comment could not be posted — you no longer have access to this document.'
: 'One comment could not be posted: the server refused it and will refuse it again, '
              + 'so it has not been kept. Nothing else in the queue is affected.')
          dropped++
          continue
        }
        // Still no connection. Stop rather than hammering the rest of the queue.
        break
      }
    }
    if (unanchored.length > 0) {
      setNeverAnchored((current) => new Set([...current,...unanchored]))
    }
    setQueued(waiting.length - sent - dropped)
    // With the parentheses. `await live.current.reload` awaited the FUNCTION — it resolved to the
    // function object and refreshed nothing, so a comment that finally reached the server on a
    // reconnect stayed invisible until something else moved the revision.
    if (sent > 0) await live.current.refresh()
  }, [props.documentId])

  useEffect(() => {
    // A failure here is not silently dropped either: `flush` swallows a missing outbox on purpose
    // (`.catch(() => [])`), and anything else that escapes is a bug worth seeing in the console
    // rather than an unhandled rejection.
    const run = () => void flush().catch((error: unknown) => {
      console.error('the comment outbox could not be drained', error)
    })
    run()
    window.addEventListener('online', run)
    return () => window.removeEventListener('online', run)
  }, [flush])

  async function toggleResolved(thread: CommentThread) {
    // Caught, because without it the button silently did nothing on a failure and the thread
    // stayed where it was — which looks exactly like a resolve that worked and a list that has not
    // refreshed yet. Not queued for later, unlike a comment: resolving is a decision about a
    // conversation, and replaying it after a reconnect could reopen something somebody else
    // deliberately closed in the meantime.
    try {
      if (thread.resolvedAt === null) {
        await api.resolveThread(thread.id)
      } else {
        await api.reopenThread(thread.id)
      }
    } catch (error) {
      console.error('could not change the state of the thread', error)
      failure.report(thread.resolvedAt === null
        ? 'Could not resolve that conversation — try again.'
: 'Could not reopen that conversation — try again.')
    }
    await panel.refresh()
  }

  return (
    <aside className="rightpanel">
      <div className="rp-header">
        Comments
        {queued > 0 && (
          // Said out loud, like the save indicator: work that is only in this browser has to
          // look different from work that is on the server.
          <span className="rp-queued" title="Waiting for a connection">
            {queued} unsent
          </span>
        )}
        <button className="rp-toggle" onClick={() => setShowResolved((on) => !on)}>
          {showResolved ? 'Hide resolved' : 'Show resolved'}
        </button>
      </div>

      <div className="rp-body">
        {/* `FailureNote`, like every other panel, instead of a `<button>` whose entire label was
            the error message — a control whose accessible name was a two-sentence paragraph, and
            whose only affordance was that clicking the message dismissed it. */}
        {failure.message !== null && (
          <FailureNote message={failure.message} onDismiss={failure.dismiss} />
        )}
        <div className="comment-new">
          {props.readOnly ? (
            <div className="rp-empty">Your role lets you read comments, but not add them.</div>
          ) : props.selection === '' ? (
            <div className="rp-empty">Select a fragment of text to comment on it.</div>
          ) : (
            <>
              <div className="comment-quote">“{props.selection}”</div>
              <textarea
                className="comment-draft"
                value={draft}
                placeholder="Your comment"
                onChange={(event) => setDraft(event.target.value)}
              />
              <button className="comment-submit" onClick={() => void open()} disabled={draft.trim() === ''}>
                Comment
              </button>
            </>
          )}
        </div>

        {threads.length === 0 && <div className="rp-empty">No comments.</div>}

        {threads.map((thread) => (
          <div
            key={thread.id}
            className={`comment-thread${thread.resolvedAt !== null ? ' resolved' : ''}${
              thread.anchored ? '' : ' detached'
            }`}
          >
            <div className="comment-quote">
              “{thread.quotedText}”
              {!thread.anchored && (neverAnchored.has(thread.id) ? (
                // Two different things, said as two different things. "Detached" is a claim about
                // the DOCUMENT — somebody deleted the text this comment was about — and saying it
                // when the text was never touched tells the reader their document changed when it
                // did not. A comment written offline whose anchor could not be placed on the way
                // out has a different story, and it is this one.
                <span
                  className="comment-detached-mark"
                  title="This comment was written without a connection. By the time it was sent it
                         had no text to attach to, so it stands on its own."
                >
                  never anchored
                </span>
              ): (
                <span className="comment-detached-mark" title="The anchored text was deleted">
                  detached
                </span>
              ))}
            </div>

            {thread.messages.map((message) => (
              <div key={message.id} className="comment-message">
                <span className="comment-avatar" style={{ background: message.authorColor }}>
                  {initials(message.authorName)}
                </span>
                <div className="comment-body">
                  <div className="comment-meta">
                    {message.authorName}
                    <span className="h-time">{formatTime(message.createdAt)}</span>
                  </div>
                  {message.body}
                </div>
              </div>
            ))}

            <div className="comment-actions">
              {props.readOnly ? null : replyTo === thread.id ? (
                // No fragment around this: it wraps one element, which is what a fragment exists
                // to avoid needing.
                <input
                  className="comment-reply"
                  value={replyDraft}
                  autoFocus
                  placeholder="Reply"
                  onChange={(event) => setReplyDraft(event.target.value)}
                  onKeyDown={(event) => {
                    if (event.key === 'Enter') void reply(thread.id)
                    if (event.key === 'Escape') setReplyTo(null)
                  }}
                />
              ) : (
                <button className="comment-action" onClick={() => setReplyTo(thread.id)}>
                  Reply
                </button>
              )}
              {!props.readOnly && (
                <button className="comment-action" onClick={() => void toggleResolved(thread)}>
                  {thread.resolvedAt === null ? 'Resolve' : 'Reopen'}
                </button>
              )}
            </div>
          </div>
        ))}
      </div>
    </aside>
  )
}
