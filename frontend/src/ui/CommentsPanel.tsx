import { useCallback, useEffect, useRef, useState } from 'react'
import { offlineStore } from '../transport/offlineStore'
import { ApiError, api, type CommentThread } from '../api'
import type { Id } from '../crdt/types'
import type { AnchorOutcome } from '../editor/useCollaborativeDocument'
import { FailureNote } from './FailureNote'
import { clientId } from './browser'
import { formatTime, initials } from './format'
import { usePanelData } from './usePanelData'

function permanentlyRefused(error: unknown): boolean {
  if (!(error instanceof ApiError)) return false
  return error.status >= 400 && error.status < 500 && error.status !== 408 && error.status !== 429
}

const MAX_QUOTE_LENGTH = 8_192

function quoteOf(selection: string): string {
  return selection.length <= MAX_QUOTE_LENGTH ? selection : `${selection.slice(0, MAX_QUOTE_LENGTH - 1)}…`
}

const NO_THREADS: CommentThread[] = []

export function CommentsPanel(props: {
  documentId: string
  selection: string
  revision: number
  onAnchor: (threadId: string) => void
  captureAnchor: () => { from: Id; to: Id } | null
  onAnchorAt: (threadId: string, range: { from: Id; to: Id }) => AnchorOutcome
  readOnly?: boolean
}) {
  const [showResolved, setShowResolved] = useState(false)
  const [draft, setDraft] = useState('')
  const [replyTo, setReplyTo] = useState<string | null>(null)
  const [replyDraft, setReplyDraft] = useState('')
  const [queued, setQueued] = useState(0)
  const [neverAnchored, setNeverAnchored] = useState<ReadonlySet<string>>(new Set)

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

  async function open() {
    if (draft.trim() === '') return
    const body = draft.trim()
    const quotedText = quoteOf(props.selection)
    setDraft('')
    try {
      const key = clientId
      const anchor = props.captureAnchor()
      try {
        const created = await api.openThread(props.documentId, quotedText, body, key())
        props.onAnchor(created.id)
      } catch {
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
      console.error('the comment could not be queued', error)
      setDraft(body)
      failure.report('This browser will not let the editor keep unsent comments, so this one '
        + 'could not be saved. It is back in the box - try again when you are connected.')
    }
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
      console.error('the reply could not be queued', error)
      setReplyDraft(body)
      setReplyTo(threadId)
      failure.report('This browser will not let the editor keep unsent comments, so this reply '
        + 'could not be saved. It is back in the box - try again when you are connected.')
    }
    await panel.refresh()
  }

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
          if (entry.anchor !== undefined) {
            if (live.current.onAnchorAt(created.id, entry.anchor) === 'gone') {
              unanchored.push(created.id)
            }
          } else {
            unanchored.push(created.id)
          }
        } else if (entry.threadId !== undefined) {
          await api.replyToThread(entry.threadId, entry.body, entry.clientId)
        }
        await offlineStore.forgetComment(entry.clientId)
        sent++
      } catch (error) {
        if (permanentlyRefused(error)) {
          await offlineStore.forgetComment(entry.clientId).catch(() => {})
          const status = error instanceof ApiError ? error.status : 0
          live.current.report(status === 403 || status === 404
            ? 'One comment could not be posted - you no longer have access to this document.'
: 'One comment could not be posted: the server refused it and will refuse it again, '
              + 'so it has not been kept. Nothing else in the queue is affected.')
          dropped++
          continue
        }
        break
      }
    }
    if (unanchored.length > 0) {
      setNeverAnchored((current) => new Set([...current,...unanchored]))
    }
    setQueued(waiting.length - sent - dropped)
    if (sent > 0) await live.current.refresh()
  }, [props.documentId])

  useEffect(() => {
    const run = () => void flush().catch((error: unknown) => {
      console.error('the comment outbox could not be drained', error)
    })
    run()
    window.addEventListener('online', run)
    return () => window.removeEventListener('online', run)
  }, [flush])

  async function toggleResolved(thread: CommentThread) {
    try {
      if (thread.resolvedAt === null) {
        await api.resolveThread(thread.id)
      } else {
        await api.reopenThread(thread.id)
      }
    } catch (error) {
      console.error('could not change the state of the thread', error)
      failure.report(thread.resolvedAt === null
        ? 'Could not resolve that conversation - try again.'
: 'Could not reopen that conversation - try again.')
    }
    await panel.refresh()
  }

  return (
    <aside className="rightpanel">
      <div className="rp-header">
        Comments
        {queued > 0 && (
          <span className="rp-queued" title="Waiting for a connection">
            {queued} unsent
          </span>
        )}
        <button className="rp-toggle" onClick={() => setShowResolved((on) => !on)}>
          {showResolved ? 'Hide resolved' : 'Show resolved'}
        </button>
      </div>

      <div className="rp-body">
        {
}
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
