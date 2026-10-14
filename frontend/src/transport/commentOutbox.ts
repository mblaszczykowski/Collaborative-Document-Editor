import { ApiError, api } from '../api'
import { offlineStore } from './offlineStore'
import type { Id } from '../crdt/types'

export const COMMENTS_CHANGED = 'collabedit:comments-changed'
const flights = new Map<string, Promise<void>>()

export function commentsChanged(): void {
  window.dispatchEvent(new Event(COMMENTS_CHANGED))
}

export function drainComments(documentId: string, userId: string,
  anchor: (threadId: string, range: { from: Id; to: Id }) => Promise<'anchored' | 'gone' | 'not-ready'> | 'anchored' | 'gone' | 'not-ready',
  report: (message: string) => void,
  active: () => boolean = () => true,
): Promise<void> {
  const key = JSON.stringify([userId, documentId])
  const previous = flights.get(key)
  if (previous) return previous
  const flight = (async () => {
    const waiting = await offlineStore.pendingComments(documentId, userId)
    for (const entry of waiting) {
      if (!active()) return
      if (entry.blocked) continue
      try {
        if (entry.kind === 'thread') {
          const created = await api.openThread(documentId, entry.quotedText ?? '', entry.body, entry.clientId)
          if (!active()) return
          if (entry.anchor && await anchor(created.id, entry.anchor) === 'not-ready') break
        } else if (entry.threadId) {
          await api.replyToThread(entry.threadId, entry.body, entry.clientId)
        }
        await offlineStore.forgetComment(entry.clientId)
        commentsChanged()
      } catch (error) {
        if (!(error instanceof ApiError) || error.status < 400 || error.status >= 500
          || error.status === 408 || error.status === 429 || error.status === 401) break
        const reason = error.status === 403 || error.status === 404
          ? 'Access was removed, so this comment could not be posted.'
          : 'The server refused this comment. Check its length and content before posting again.'
        await offlineStore.queueComment({ ...entry, blocked: reason })
        report(`${reason} Its text is saved in Comments for recovery.`)
        commentsChanged()
      }
    }
  })().finally(() => flights.delete(key))
  flights.set(key, flight)
  return flight
}
