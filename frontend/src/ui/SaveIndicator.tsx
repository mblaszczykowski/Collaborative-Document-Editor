/**
 * Stan zapisu — jedyny element interfejsu, który mówi użytkownikowi, czy jego praca jest
 * bezpieczna. Kolejność warunków nie jest dowolna: „offline" wygrywa z „zapisywanie",
 * bo obietnica trwającego zapisu przy braku łączności jest kłamstwem dokładnie w tej
 * sytuacji, w której użytkownik musi znać prawdę.
 */
import type { ConnectionState } from '../transport/connection'

export function SaveIndicator({ state, pending }: { state: ConnectionState; pending: number }) {
  if (state === 'offline') {
    return (
      <span className="save-state offline">
        <span className="save-dot" />
        Praca offline
      </span>
    )
  }
  if (pending > 0) {
    return (
      <span className="save-state pending">
        <span className="save-dot" />
        Zapisywanie…
      </span>
    )
  }
  if (state === 'connecting' || state === 'resyncing') {
    return (
      <span className="save-state pending">
        <span className="save-dot" />
        Łączenie…
      </span>
    )
  }
  return (
    <span className="save-state saved">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3">
        <polyline points="20 6 9 17 4 12" />
      </svg>
      Zapisano
    </span>
  )
}
