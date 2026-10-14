export function initials(name: string): string {
  return name
    .split(/\s+/)
    .slice(0, 2)
    .map((part) => part.charAt(0).toUpperCase())
    .join('')
}

export function wordCount(text: string): string {
  let words = 0
  for (const part of new Intl.Segmenter(undefined, { granularity: 'word' }).segment(text)) {
    if (part.isWordLike) words++
  }
  return words === 1 ? '1 word' : `${words} words`
}

export function peopleLabel(count: number): string {
  return count === 1 ? '1 person' : `${count} people`
}

export function characterCount(text: string): number {
  let count = 0
  for (const _part of new Intl.Segmenter(undefined, { granularity: 'grapheme' }).segment(text)) count++
  return count
}

export function formatTime(iso: string): string {
  const at = new Date(iso)
  const secondsAgo = Math.max(0, Math.floor((Date.now() - at.getTime()) / 1000))
  if (secondsAgo < 60) return `${secondsAgo}s ago`
  if (secondsAgo < 3600) return `${Math.floor(secondsAgo / 60)} min ago`
  return secondsAgo < 86400
    ? at.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' })
    : at.toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' })
}
