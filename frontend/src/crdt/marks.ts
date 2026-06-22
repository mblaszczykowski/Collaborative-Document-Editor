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

/**
 * Resolves an anchor to a position in the visible text.
 *
 * This replaced a `(anchor) => number | null` callback that walked **every block** on each call —
 * twice per mark, on the keystroke path. See `RgaDocument.anchorIndex` for the measurements and
 * for the structure that answers it in logarithmic time instead.
 *
 * Returns `undefined` when the character is not in the structure, which is not an error: the mark
 * covers nothing until its anchor arrives.
 */
export type AnchorPositions = (anchor: Anchor) => number | undefined

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

  /** Drops every mark — see `RgaDocument.restoreFrom`, which replaces the whole state. */
  clear(): void {
    this.operations.clear()
  }

  // all() used to sit here, mirroring MarkSet.all() in Java. It exists there because the
  // server WRITES snapshots; the client only reads them, so nothing ever called this one.

  /**
   * The visible text's formatting as disjoint, ordered runs.
   *
   * Runs with no formatting at all are omitted — the view layer receives only what it has to draw.
   */
  spans(positions: AnchorPositions): Span[] {
    const resolved = this.resolveAll(positions)
    if (resolved.length === 0) return []

    // The boundaries: the start and end of every range. Between two consecutive boundaries the
    // formatting is constant by definition, because no range begins or ends there.
    const boundaries = new Set<number>()
    for (const entry of resolved) {
      boundaries.add(entry.from)
      boundaries.add(entry.to)
    }
    const points = [...boundaries].sort((a, b) => a - b)

    // The ranges starting and ending at each boundary, so the sweep does not have to walk every
    // mark at every boundary.
    //
    // This is the CP20 rewrite from `MarkSet.java`, ported. The version it replaces re-scanned
    // every resolved mark at every boundary — O(marks^2) — and combined with a per-anchor linear
    // block scan it made `formatting()` cost 31 ms on a document with 10 000 blocks and 800 marks,
    // on the keystroke path. The server's own header had already argued this exact case with
    // numbers; this file said it was "kept close to the structure of the Java version" and was
    // three checkpoints behind it.
    const startingAt = new Map<number, Resolved[]>()
    const endingAt = new Map<number, Resolved[]>()
    for (const entry of resolved) {
      const starts = startingAt.get(entry.from)
      if (starts === undefined) startingAt.set(entry.from, [entry])
      else starts.push(entry)
      const ends = endingAt.get(entry.to)
      if (ends === undefined) endingAt.set(entry.to, [entry])
      else ends.push(entry)
    }

    // Active ranges per type, kept sorted DESCENDING by (lamport, replicaId) — the same total
    // order RGA uses for siblings, so no new arbiter appears in the mark layer. The first element
    // is the winner for that type on the current segment.
    //
    // A sorted array rather than a tree: the active set for one type is the number of marks of
    // that type OVERLAPPING at a single position, which is a handful even in a heavily annotated
    // document. A binary insert into a short array beats a tree here and needs no dependency.
    const activeByType = new Map<string, Resolved[]>()

    const spans: Span[] = []
    for (let i = 0; i + 1 < points.length; i++) {
      // Strictly increasing: `points` comes from a Set that was sorted, so `from < to` always
      // holds and the guard that used to stand here was unreachable.
      const from = points[i]
      const to = points[i + 1]

      // Remove the ranges ending at this boundary FIRST, then add the ones starting here. The
      // reverse order would briefly have [a,b) and [b,c) of the same type active together.
      for (const ending of endingAt.get(from) ?? []) {
        const active = activeByType.get(ending.type)
        if (active === undefined) continue
        const at = active.indexOf(ending)
        if (at >= 0) active.splice(at, 1)
        if (active.length === 0) activeByType.delete(ending.type)
      }
      for (const starting of startingAt.get(from) ?? []) {
        const active = activeByType.get(starting.type)
        if (active === undefined) {
          activeByType.set(starting.type, [starting])
        } else {
          insertByPriority(active, starting)
        }
      }

      const marks = new Map<string, string | null>()
      for (const [type, active] of activeByType) {
        const winner = active[0]
        if (winner.adding) marks.set(type, winner.value)
      }
      if (marks.size > 0) spans.push({ from, to, marks })
    }
    return mergeAdjacent(spans)
  }

  private resolveAll(positions: AnchorPositions): Resolved[] {
    const resolved: Resolved[] = []
    for (const op of this.operations.values()) {
      if (op.kind !== 'addMark' && op.kind !== 'removeMark') continue

      const from = positions(op.start)
      const to = positions(op.end)
      if (from === undefined || to === undefined || from >= to) {
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

/** Keeps the active list sorted descending by (lamport, replicaId) — the winner is index 0. */
function insertByPriority(active: Resolved[], entry: Resolved): void {
  let low = 0
  let high = active.length
  while (low < high) {
    const mid = (low + high) >>> 1
    if (compareIds(active[mid].op.id, entry.op.id) > 0) low = mid + 1
    else high = mid
  }
  active.splice(low, 0, entry)
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
