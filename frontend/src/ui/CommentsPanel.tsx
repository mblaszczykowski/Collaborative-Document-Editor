import { useCallback, useEffect, useState } from 'react'
import { offlineStore } from '../transport/offlineStore'
import { api, type CommentThread } from '../api'
import { formatTime, initials } from './format'

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
export function CommentsPanel(props: {
  documentId: string
  /** The selected fragment of text; empty when nothing is selected. */
  selection: string
  /** Increments on every document change — forces the anchoring state to refresh. */
  revision: number
  onAnchor: (threadId: string) => void
  /**
   * The role does not permit commenting (`viewer`).
   *
   * The panel stays visible — a reader is entitled to READ the conversation, just not add to it.
   * Hiding the panel would take away the context it is usually opened for.
   */
  readOnly?: boolean
}) {
  const [threads, setThreads] = useState<CommentThread[]>([])
  const [showResolved, setShowResolved] = useState(false)
  const [draft, setDraft] = useState('')
  const [replyTo, setReplyTo] = useState<string | null>(null)
  const [replyDraft, setReplyDraft] = useState('')
  /** Comments written with no connection, waiting in the outbox (CP27). */
  const [queued, setQueued] = useState(0)

  const reload = useCallback(async () => {
    setThreads(await api.listComments(props.documentId, showResolved))
  }, [props.documentId, showResolved])

  useEffect(() => {
    void reload()
  }, [reload, props.revision])

  /**
   * Posts a comment, or keeps it until there is a connection (CP27).
   *
   * The offline buffer built in CP6 covers CRDT operations, because those travel the WebSocket.
   * A comment travels REST, so before CP27 one written with no connection simply vanished —
   * a limit the README named and nobody had hit in a demo, because demos have a network.
   *
   * The `clientId` is generated here and repeated on every retry, which is the whole mechanism:
   * what an offline comment needs is not merging but exactly-once, and the server turns a
   * repeated key into a no-op (ADR-0011 holds — comments still are not a CRDT).
   */
  async function open() {
    if (draft.trim() === '') return
    const clientId = crypto.randomUUID()
    const body = draft.trim()
    const quotedText = props.selection
    setDraft('')
    try {
      const created = await api.openThread(props.documentId, quotedText, body, clientId)
      // The anchor is created AFTER the thread, because it needs the thread's identifier as the
      // mark's value. It travels by a different route — the WebSocket, as an ordinary CRDT
      // operation.
      props.onAnchor(created.id)
    } catch {
      // The anchor is deliberately NOT placed here. It would need a thread identifier that does
      // not exist yet, and inventing one would put a mark in the document pointing at a
      // conversation that may never be created.
      await offlineStore.queueComment({
        clientId,
        documentId: props.documentId,
        kind: 'thread',
        quotedText,
        body,
        queuedAt: Date.now(),
      })
      setQueued((current) => current + 1)
    }
    await reload()
  }

  async function reply(threadId: string) {
    if (replyDraft.trim() === '') return
    const clientId = crypto.randomUUID()
    const body = replyDraft.trim()
    setReplyDraft('')
    setReplyTo(null)
    try {
      await api.replyToThread(threadId, body, clientId)
    } catch {
      await offlineStore.queueComment({
        clientId,
        documentId: props.documentId,
        kind: 'reply',
        threadId,
        body,
        queuedAt: Date.now(),
      })
      setQueued((current) => current + 1)
    }
    await reload()
  }

  /**
   * Sends what is waiting, oldest first.
   *
   * Retried on every panel refresh and when the browser reports it is back online. A comment that
   * already arrived is recognised by its key and does not become a duplicate, so retrying costs
   * nothing and missing an opportunity to retry costs a comment.
   */
  const flush = useCallback(async () => {
    const waiting = await offlineStore.pendingComments(props.documentId).catch(() => [])
    if (waiting.length === 0) {
      setQueued(0)
      return
    }
    let sent = 0
    for (const entry of waiting) {
      try {
        if (entry.kind === 'thread') {
          const created = await api.openThread(
            entry.documentId,
            entry.quotedText ?? '',
            entry.body,
            entry.clientId,
          )
          void created
        } else if (entry.threadId !== undefined) {
          await api.replyToThread(entry.threadId, entry.body, entry.clientId)
        }
        await offlineStore.forgetComment(entry.clientId)
        sent++
      } catch {
        // Still no connection. Stop rather than hammering the rest of the queue.
        break
      }
    }
    setQueued(waiting.length - sent)
    if (sent > 0) await reload()
  }, [props.documentId, reload])

  useEffect(() => {
    void flush()
    const onOnline = () => void flush()
    window.addEventListener('online', onOnline)
    return () => window.removeEventListener('online', onOnline)
  }, [flush])

  async function toggleResolved(thread: CommentThread) {
    if (thread.resolvedAt === null) {
      await api.resolveThread(thread.id)
    } else {
      await api.reopenThread(thread.id)
    }
    await reload()
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
        <div className="comment-new">
          {props.readOnly ? (
            <div className="rp-empty">Your role lets you read comments, but not add them.</div>
          ) : props.selection === '' ? (
            <div className="rp-empty">Select a fragment of text to comment on it.</div>
          ) : (
            <>
              <div className="comment-quote">„{props.selection}"</div>
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
              „{thread.quotedText}"
              {!thread.anchored && (
                <span className="comment-detached-mark" title="The anchored text was deleted">
                  detached
                </span>
              )}
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
                <>
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
                </>
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
