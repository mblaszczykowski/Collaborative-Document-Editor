import { useCallback, useEffect, useRef, useState } from 'react'
import { useScopedDraft } from './scopedDraft'
import { offlineStore, type QueuedComment } from '../transport/offlineStore'
import { api, type CommentThread } from '../api'
import type { Id } from '../crdt/types'
import type { AnchorOutcome } from '../editor/useCollaborativeDocument'
import { FailureNote } from './FailureNote'
import { COMMENTS_CHANGED, commentsChanged } from '../transport/commentOutbox'
import { clientId } from './browser'
import { formatTime, initials } from './format'
import { usePanelData } from './usePanelData'

const MAX_QUOTE_LENGTH = 8_192

function quoteOf(selection: string): string {
  return selection.length <= MAX_QUOTE_LENGTH ? selection : `${selection.slice(0, MAX_QUOTE_LENGTH - 1)}…`
}

const NO_THREADS: CommentThread[] = []

export function CommentsPanel(props: {
  documentId: string
  userId: string
  ready?: boolean
  selection: string
  revision: number
  onAnchor: (threadId: string) => void
  captureAnchor: () => { from: Id; to: Id } | null
  onAnchorAt: (threadId: string, range: { from: Id; to: Id }) => Promise<AnchorOutcome>
  readOnly?: boolean
}) {
  const [showResolved, setShowResolved] = useState(false)
  const [draft, setDraft] = useScopedDraft(props.userId, props.documentId, 'comment')
  const [replyTo, setReplyTo] = useScopedDraft(props.userId, props.documentId, 'reply-thread')
  const [replyDraft, setReplyDraft] = useScopedDraft(props.userId, props.documentId, `reply:${replyTo || 'none'}`)
  const [blocked, setBlocked] = useState<QueuedComment[]>([])
  const queueing = useRef(false)
  const [queued, setQueued] = useState(0)

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
  const { failure, refresh } = panel

  async function open() {
    const body = draft.trim()
    if (!body || body.length > 4000 || !props.selection || queueing.current) return
    queueing.current = true
    try {
      await offlineStore.queueComment({ clientId: clientId(), userId: props.userId,
        documentId: props.documentId, kind: 'thread', quotedText: quoteOf(props.selection),
        anchor: props.captureAnchor() ?? undefined, body, queuedAt: Date.now() })
      setDraft(current => current.trim() === body ? '' : current)
      commentsChanged()
    } catch {
      failure.report('The comment could not be saved locally. Its text is still in the box; try again when browser storage is available.')
    } finally { queueing.current = false }
  }

  async function reply(threadId: string) {
    const body = replyDraft.trim()
    if (!body || body.length > 4000 || queueing.current) return
    queueing.current = true
    try {
      await offlineStore.queueComment({ clientId: clientId(), userId: props.userId,
        documentId: props.documentId, kind: 'reply', threadId, body, queuedAt: Date.now() })
      setReplyDraft(current => current.trim() === body ? '' : current)
      setReplyTo('')
      commentsChanged()
    } catch {
      failure.report('The reply could not be saved locally. Its text is still in the box; try again when browser storage is available.')
    } finally { queueing.current = false }
  }

  useEffect(() => {
    let cancelled = false
    const update = () => {
      void offlineStore.pendingComments(props.documentId, props.userId).then(entries => {
        if (!cancelled) { setQueued(entries.length); setBlocked(entries.filter(entry => entry.blocked)) }
      }).catch(() => {})
      void refresh()
    }
    update()
    window.addEventListener(COMMENTS_CHANGED, update)
    return () => { cancelled = true; window.removeEventListener(COMMENTS_CHANGED, update) }
  }, [props.documentId, props.userId, refresh])

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
          <span className="rp-queued" title="Saved unsent comments, including any refused posts">
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
          ) : props.selection === '' && draft === '' ? (
            <div className="rp-empty">Select a fragment of text to comment on it.</div>
          ) : (
            <>
              {props.selection ? <div className="comment-quote">“{props.selection}”</div>
                : <p className="suggest-hint">Select text to attach this saved draft before posting.</p>}
              <textarea
                className="comment-draft"
                value={draft}
                placeholder="Your comment"
                aria-label="Your comment"
                maxLength={4000}
                onChange={(event) => setDraft(event.target.value)}
              />
              <button className="comment-submit" onClick={() => void open()} disabled={draft.trim() === '' || draft.trim().length > 4000 || props.selection === '' || props.ready === false}>
                Comment
              </button>
            </>
          )}
        </div>

        {blocked.map(entry => (
          <div className="comment-thread" key={entry.clientId}>
            <strong>Unsent comment saved for recovery</strong>
            <p className="suggest-hint">{entry.blocked}</p>
            <textarea className="comment-draft" readOnly value={entry.body}
              aria-label="Unsent comment text — copy to recover" />
            <button className="comment-submit" disabled={props.readOnly} onClick={() => {
              setDraft(current => current ? `${current}\n${entry.body}` : entry.body)
            }}>Copy to draft</button>
            <p className="suggest-hint">The backup stays saved here until you choose to recover it.</p>
          </div>
        ))}

        {threads.length === 0 && !failure.message && <div className="rp-empty">{panel.loading ? 'Loading comments…' : 'No comments.'}</div>}

        {threads.map((thread) => (
          <div
            key={thread.id}
            className={`comment-thread${thread.resolvedAt !== null ? ' resolved' : ''}${
              thread.anchored ? '' : ' detached'
            }`}
          >
            <div className="comment-quote">
              “{thread.quotedText}”
              {!thread.anchored && (
                <span className="comment-detached-mark" title="The original text is no longer available">
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
                  aria-label="Reply to comment"
                  maxLength={4000}
                  onChange={(event) => setReplyDraft(event.target.value)}
                  onKeyDown={(event) => {
                    if (event.key === 'Enter' && !event.nativeEvent.isComposing) void reply(thread.id)
                    if (event.key === 'Escape') setReplyTo('')
                  }}
                />
                <button className="comment-submit" onClick={() => void reply(thread.id)}
                  disabled={!replyDraft.trim() || replyDraft.trim().length > 4000}>Post reply</button>
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
