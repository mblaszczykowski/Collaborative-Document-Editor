/**
 * Formatting of user-visible text.
 *
 * Pure functions, with no React and no document model — the kind that can be tested without
 * rendering anything. Keeping them together means the whole of what the interface says can be read
 * at a glance.
 */

// `shiftAfter` used to live here: a one-line `index + 1` that turned "the character a cursor sits
// after" into "the offset the caret is at". It is gone because the presence cursor now carries a
// SIDE — a caret can be before a character as well as after one — so the shift is no longer a
// property of every cursor and belongs with the decoding that knows which case it has. See
// `transport/cursorAnchor.ts` and the `resolve` helper in `useCollaborativeDocument`.

export function initials(name: string): string {
  return name
    .split(/\s+/)
    .slice(0, 2)
    .map((part) => part.charAt(0).toUpperCase())
    .join('')
}

/** An approximation: an average English word is about five characters plus a space. */
export function wordCount(characters: number): string {
  const words = Math.max(0, Math.round(characters / 6))
  return words === 1 ? '1 word' : `${words} words`
}

export function peopleLabel(count: number): string {
  return count === 1 ? '1 person' : `${count} people`
}

export function formatTime(iso: string): string {
  const at = new Date(iso)
  const secondsAgo = Math.floor((Date.now() - at.getTime()) / 1000)
  if (secondsAgo < 60) return `${secondsAgo}s ago`
  if (secondsAgo < 3600) return `${Math.floor(secondsAgo / 60)} min ago`
  return at.toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit' })
}
