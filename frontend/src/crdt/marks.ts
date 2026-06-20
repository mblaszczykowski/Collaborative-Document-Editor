import { compareIds, idToString, type Anchor, type Id, type Op } from './types'

/**
 * The formatting layer — the twin of `MarkSet.java` (CP13).
 *
 * The model: a mark is NOT an attribute of a character but a range between two anchors. The full
 * justification lives on the Java side; here it is abbreviated, because repeating it in two places
 * means two descriptions drift apart instead of one.
 *
 * Conflict resolution: for a given character and type, the operation with the highest
 * `(lamport, replicaId)` among those covering that character wins — the same total order RGA's
 * insertion rule uses.
 *
 * The code is kept close to the structure of the Java version (the same names, the same order), so
 * that comparing them on a change is mechanical.
 */

/** A resolved mark over a run of visible text [from, to). */
export interface Span {
  readonly from: number
  readonly to: number
  readonly marks: ReadonlyMap<string, string | null>
}

/** An anchor's position in visible coordinates; null when the character is not in the structure. */
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
   * The mark operations, keyed by identifier.
   *
   * `Map` preserves insertion order, but the result does not depend on it — the resolution order is
   * explicit and computed on every render.
   */
  private readonly operations = new Map<string, Op>()

  apply(op: Op): boolean {
    if (op.kind !== 'addMark' && op.kind !== 'removeMark') {
      throw new Error(`not a mark operation: ${op.kind}`)
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
   * The visible text's formatting as disjoint, ordered runs.
   *
   * Runs with no formatting at all are omitted — the view layer receives only what it has to draw.
   */
  spans(resolve: AnchorResolver): Span[] {
    const resolved = this.resolveAll(resolve)
    if (resolved.length === 0) return []

    // The boundaries: the start and end of every range. Between two consecutive boundaries the
    // formatting is constant by definition.
    const boundaries = new Set<number>()
    for (const entry of resolved) {
      boundaries.add(entry.from)
      boundaries.add(entry.to)
    }
    const points = [...boundaries].sort((a, b) => a - b)

    // Descending by (lamport, replicaId): the first matching operation of a given type wins.
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
        // The anchor is outside the structure, or the range is empty after its content was
        // deleted. The mark covers nothing — which is not an error.
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
 * Merges adjacent runs with identical formatting.
 *
 * The boundaries come from EVERY range, so two bold marks meeting end to end would give two runs
 * with the same content — that is, two DOM elements where one suffices. When rendering on every
 * keystroke, that is a difference.
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
