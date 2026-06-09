import { compareIds, idToString, type Anchor, type Id, type Op } from './types'

/**
 * Warstwa formatowania — bliźniak `MarkSet.java` (CP13).
 *
 * Model: znacznik NIE jest atrybutem znaku, tylko zakresem między dwiema kotwicami.
 * Pełne uzasadnienie po stronie Javy; tutaj skrótowo, bo powtarzanie go w dwóch miejscach
 * znaczy, że rozjadą się dwa opisy zamiast jednego.
 *
 * Rozstrzyganie konfliktów: dla danego znaku i typu wygrywa operacja o najwyższym
 * `(lamport, replicaId)` spośród obejmujących ten znak — ten sam porządek totalny, którego
 * używa reguła wstawiania RGA.
 *
 * Kod trzymany blisko struktury wersji w Javie (te same nazwy, ta sama kolejność), żeby
 * porównanie przy zmianie było mechaniczne.
 */

/** Rozwiązany znacznik na odcinku widocznego tekstu [from, to). */
export interface Span {
  readonly from: number
  readonly to: number
  readonly marks: ReadonlyMap<string, string | null>
}

/** Pozycja kotwicy w widocznych współrzędnych; null gdy znaku nie ma w strukturze. */
export type AnchorResolver = (anchor: Anchor) => number | null

interface Resolved {
  readonly op: Op
  readonly type: string
  readonly value: string | null
  readonly adding: boolean
  readonly from: number
  readonly to: number
}

export class MarkSet {
  /**
   * Operacje znacznikowe, kluczowane identyfikatorem.
   *
   * `Map` zachowuje kolejność wstawiania, ale wynik od niej nie zależy — porządek
   * rozstrzygania jest jawny i wyliczany przy każdym renderowaniu.
   */
  private readonly operations = new Map<string, Op>()

  apply(op: Op): boolean {
    if (op.kind !== 'addMark' && op.kind !== 'removeMark') {
      throw new Error(`to nie jest operacja znacznikowa: ${op.kind}`)
    }
    const key = idToString(op.id)
    if (this.operations.has(key)) return false
    this.operations.set(key, op)
    return true
  }

  has(opId: Id): boolean {
    return this.operations.has(idToString(opId))
  }

  get size(): number {
    return this.operations.size
  }

  all(): Op[] {
    return [...this.operations.values()]
  }

  /**
   * Formatowanie widocznego tekstu jako rozłączne, uporządkowane odcinki.
   *
   * Odcinki bez żadnego formatowania są pomijane — warstwa widoku dostaje wyłącznie to,
   * co ma narysować.
   */
  spans(resolve: AnchorResolver): Span[] {
    const resolved = this.resolveAll(resolve)
    if (resolved.length === 0) return []

    // Granice: początek i koniec każdego zakresu. Między dwiema kolejnymi granicami
    // formatowanie jest z definicji stałe.
    const boundaries = new Set<number>()
    for (const entry of resolved) {
      boundaries.add(entry.from)
      boundaries.add(entry.to)
    }
    const points = [...boundaries].sort((a, b) => a - b)

    // Malejąco po (lamport, replicaId): pierwsza pasująca operacja danego typu wygrywa.
    const byPriority = [...resolved].sort((a, b) => compareIds(b.op.id, a.op.id))

    const spans: Span[] = []
    for (let i = 0; i + 1 < points.length; i++) {
      const from = points[i]
      const to = points[i + 1]
      if (from >= to) continue

      const marks = new Map<string, string | null>()
      const decided = new Set<string>()
      for (const entry of byPriority) {
        if (from < entry.from || from >= entry.to) continue
        if (decided.has(entry.type)) continue
        decided.add(entry.type)
        if (entry.adding) marks.set(entry.type, entry.value)
      }
      if (marks.size > 0) spans.push({ from, to, marks })
    }
    return mergeAdjacent(spans)
  }

  private resolveAll(resolve: AnchorResolver): Resolved[] {
    const resolved: Resolved[] = []
    for (const op of this.operations.values()) {
      if (op.kind !== 'addMark' && op.kind !== 'removeMark') continue

      const from = resolve(op.start)
      const to = resolve(op.end)
      if (from === null || to === null || from >= to) {
        // Kotwica poza strukturą albo zakres pusty po skasowaniu zawartości.
        // Znacznik nic nie obejmuje — nie jest to błąd.
        continue
      }
      resolved.push({
        op,
        type: op.type,
        value: op.kind === 'addMark' ? op.value : null,
        adding: op.kind === 'addMark',
        from,
        to,
      })
    }
    return resolved
  }
}

/**
 * Skleja sąsiadujące odcinki o identycznym formatowaniu.
 *
 * Granice pochodzą od WSZYSTKICH zakresów, więc dwa pogrubienia stykające się końcami dałyby
 * dwa odcinki o tej samej treści — czyli dwa elementy DOM tam, gdzie wystarczy jeden.
 * Przy renderowaniu na każde naciśnięcie klawisza to jest różnica.
 */
function mergeAdjacent(spans: Span[]): Span[] {
  if (spans.length < 2) return spans
  const merged: Span[] = []
  let current = spans[0]
  for (let i = 1; i < spans.length; i++) {
    const next = spans[i]
    if (next.from === current.to && sameMarks(current.marks, next.marks)) {
      current = { from: current.from, to: next.to, marks: current.marks }
    } else {
      merged.push(current)
      current = next
    }
  }
  merged.push(current)
  return merged
}

function sameMarks(
  a: ReadonlyMap<string, string | null>,
  b: ReadonlyMap<string, string | null>,
): boolean {
  if (a.size !== b.size) return false
  for (const [key, value] of a) {
    if (!b.has(key) || b.get(key) !== value) return false
  }
  return true
}
