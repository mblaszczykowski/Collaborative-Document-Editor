/**
 * Formatowanie tekstu widocznego dla użytkownika.
 *
 * Czyste funkcje, bez Reacta i bez modelu dokumentu — czyli takie, które da się przetestować
 * bez renderowania czegokolwiek. Polska odmiana liczebników jest tu jedyną nietrywialną
 * rzeczą i zasługuje na miejsce, w którym widać ją całą naraz.
 */

export function shiftAfter(index: number | null): number | null {
  return index === null ? null : index + 1
}

export function initials(name: string): string {
  return name
    .split(/\s+/)
    .slice(0, 2)
    .map((part) => part.charAt(0).toUpperCase())
    .join('')
}

/** Przybliżenie: średnie polskie słowo to około sześciu znaków plus spacja. */
export function wordCount(characters: number): string {
  const words = Math.max(0, Math.round(characters / 7))
  if (words === 1) return '1 słowo'
  const last = words % 10
  const lastTwo = words % 100
  if (last >= 2 && last <= 4 && (lastTwo < 12 || lastTwo > 14)) return `${words} słowa`
  return `${words} słów`
}

/** Polska odmiana: 1 osoba, 2–4 osoby, 5+ osób, ale 12–14 osób. */
export function peopleLabel(count: number): string {
  const lastTwo = count % 100
  const last = count % 10
  if (count === 1) return '1 osoba'
  if (last >= 2 && last <= 4 && (lastTwo < 12 || lastTwo > 14)) return `${count} osoby`
  return `${count} osób`
}

export function formatTime(iso: string): string {
  const at = new Date(iso)
  const secondsAgo = Math.floor((Date.now() - at.getTime()) / 1000)
  if (secondsAgo < 60) return `${secondsAgo} s temu`
  if (secondsAgo < 3600) return `${Math.floor(secondsAgo / 60)} min temu`
  return at.toLocaleTimeString('pl-PL', { hour: '2-digit', minute: '2-digit' })
}
