export function initials(name: string): string {
  return name
    .split(/\s+/)
    .slice(0, 2)
    .map((part) => part.charAt(0).toUpperCase())
    .join('')
}

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
