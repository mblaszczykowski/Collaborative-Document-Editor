import type { ConnectionState } from '../transport/connection'

export function SaveIndicator({ state, pending }: { state: ConnectionState; pending: number }) {
  if (state === 'offline') {
    return (
      <span className="save-state offline">
        <span className="save-dot" />
        Working offline
      </span>
    )
  }
  if (pending > 0) {
    return (
      <span className="save-state pending">
        <span className="save-dot" />
        Saving…
      </span>
    )
  }
  if (state === 'connecting' || state === 'resyncing') {
    return (
      <span className="save-state pending">
        <span className="save-dot" />
        Connecting…
      </span>
    )
  }
  return (
    <span className="save-state saved">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3">
        <polyline points="20 6 9 17 4 12" />
      </svg>
      Saved
    </span>
  )
}
