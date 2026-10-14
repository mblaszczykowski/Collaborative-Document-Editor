import type { RgaDocument } from './document'
import { idEquals } from './types'
import type { Id, Op } from './types'
import type { Span } from './marks'
import { deletePieces, insertPieces } from '../editor/bridge'

interface UndoableInsert {
  kind: 'insert'
  first: Id
  length: number
}

interface UndoableDelete {
  kind: 'delete'
  after: Id | null
  text: string
  marks?: SavedSpan[]
  removedLast?: Id
}

interface UndoableMark {
  kind: 'mark'
  type: string
  from: Id
  to: Id
  before: string | null | undefined
  after: string | null | undefined
}

interface SavedSpan { from: number; to: number; marks: [string, string | null][] }
interface UndoGroup { kind: 'group'; entries: UndoEntry[] }
type UndoEntry = UndoableInsert | UndoableDelete | UndoableMark | UndoGroup

function isUndoEntry(value: unknown): value is UndoEntry {
  if (typeof value !== 'object' || value === null) return false
  const entry = value as Record<string, unknown>
  switch (entry['kind']) {
    case 'group':
      return Array.isArray(entry['entries']) && entry['entries'].length > 0
        && entry['entries'].every(isUndoEntry)
    case 'insert':
      return isId(entry['first'])
        && typeof entry['length'] === 'number'
        && Number.isInteger(entry['length'])
        && entry['length'] > 0
    case 'delete':
      return (entry['after'] === null || isId(entry['after']))
        && typeof entry['text'] === 'string'
        && entry['text'].length > 0
    case 'mark':
      return typeof entry['type'] === 'string'
        && isId(entry['from'])
        && isId(entry['to'])
        && isMarkValue(entry['before'])
        && isMarkValue(entry['after'])
    default:
      return false
  }
}

function isId(value: unknown): value is Id {
  if (typeof value !== 'object' || value === null) return false
  const candidate = value as Record<string, unknown>
  return Number.isSafeInteger(candidate['replicaId']) && Number.isSafeInteger(candidate['lamport'])
    && (candidate['lamport'] as number) >= 0
}

function isMarkValue(value: unknown): value is string | null | undefined {
  return value === undefined || value === null || typeof value === 'string'
}

export class UndoManager {
  private readonly undoStack: UndoEntry[] = []
  private readonly redoStack: UndoEntry[] = []
  private applying = false

  private static readonly LIMIT = 200

  private static readonly COALESCE_WINDOW_MS = 1000

  private lastRecordedAt = 0
  private transaction: UndoEntry[] | null = null

  breakGroup(): void { this.lastRecordedAt = 0 }

  transact(action: () => void): void {
    if (this.transaction !== null) { action(); return }
    this.transaction = []
    try { action() } finally {
      const entries = this.transaction
      this.transaction = null
      if (entries.length > 0) this.push(entries.length === 1 ? entries[0] : { kind: 'group', entries })
    }
  }

  private readonly document: RgaDocument

  private onChange: (() => void) | null = null

  constructor(document: RgaDocument) {
    this.document = document
  }

  observe(onChange: () => void): () => void {
    this.onChange = onChange
    return () => {
      if (this.onChange === onChange) this.onChange = null
    }
  }

  serialise(): { undo: UndoEntry[]; redo: UndoEntry[] } {
    return { undo: [...this.undoStack], redo: [...this.redoStack] }
  }

  restore(state: unknown): void {
    this.undoStack.length = 0
    this.redoStack.length = 0
    const stacks = state as { undo?: unknown; redo?: unknown } | null | undefined
    this.undoStack.push(...UndoManager.usable(stacks?.undo))
    this.redoStack.push(...UndoManager.usable(stacks?.redo))
  }

  private static usable(saved: unknown): UndoEntry[] {
    if (!Array.isArray(saved)) return []
    return (saved as unknown[]).filter(isUndoEntry).slice(-UndoManager.LIMIT)
  }

  get canUndo(): boolean {
    return this.undoStack.length > 0
  }

  get canRedo(): boolean {
    return this.redoStack.length > 0
  }

  recordInsert(first: Id, length: number): void {
    if (this.applying) return

    const previous = this.undoStack.at(-1)
    if (
      this.transaction === null && previous !== undefined &&
      previous.kind === 'insert' &&
      this.withinWindow() &&
      previous.first.replicaId === first.replicaId &&
      previous.first.lamport + previous.length === first.lamport &&
      (this.document.visibleIndicesOf(previous.first, previous.length).at(-1) ?? -2) + 1
        === this.document.visibleIndexOf(first)
    ) {
      previous.length += length
      this.lastRecordedAt = Date.now()
      this.redoStack.length = 0
      this.onChange?.()
      return
    }

    this.push({ kind: 'insert', first, length })
  }

  recordDelete(after: Id | null, text: string, marks?: SavedSpan[], removedLast?: Id): void {
    if (this.applying) return
    removedLast ??= this.document.lastDeletedId

    const previous = this.undoStack.at(-1)
    if (this.transaction === null && !marks?.length && previous !== undefined
      && previous.kind === 'delete' && !previous.marks?.length && this.withinWindow()
      && (idEquals(previous.after, after)
        || (removedLast !== undefined && idEquals(previous.after, removedLast)))) {
      if (idEquals(previous.after, after)) {
        previous.text = previous.text + text
      } else {
        previous.text = text + previous.text
        previous.after = after
      }
      this.lastRecordedAt = Date.now()
      this.redoStack.length = 0
      this.onChange?.()
      return
    }

    this.push({ kind: 'delete', after, text, marks, removedLast })
  }

  private withinWindow(): boolean {
    return Date.now() - this.lastRecordedAt < UndoManager.COALESCE_WINDOW_MS
  }

  private push(entry: UndoEntry): void {
    if (this.transaction !== null) { this.transaction.push(entry); return }
    const previous = this.undoStack.at(-1)
    if (entry.kind === 'delete' && previous?.kind === 'delete' && this.withinWindow()
      && (idEquals(entry.after, previous.after)
        || (entry.removedLast !== undefined && idEquals(previous.after, entry.removedLast)))) {
      const forward = idEquals(entry.after, previous.after)
      const shift = (spans: SavedSpan[] | undefined, amount: number) => (spans ?? [])
        .map(span => ({ ...span, from: span.from + amount, to: span.to + amount }))
      previous.marks = forward ? [...(previous.marks ?? []), ...shift(entry.marks, previous.text.length)]
        : [...(entry.marks ?? []), ...shift(previous.marks, entry.text.length)]
      previous.text = forward ? previous.text + entry.text : entry.text + previous.text
      previous.after = entry.after
      previous.removedLast = entry.removedLast
      this.redoStack.length = 0
      this.lastRecordedAt = Date.now()
      this.onChange?.()
      return
    }
    if (entry.kind === 'insert' && previous?.kind === 'insert' && this.withinWindow()
      && previous.first.replicaId === entry.first.replicaId
      && previous.first.lamport + previous.length === entry.first.lamport
      && (this.document.visibleIndicesOf(previous.first, previous.length).at(-1) ?? -2) + 1
        === this.document.visibleIndexOf(entry.first)) {
      previous.length += entry.length
      this.redoStack.length = 0
      this.lastRecordedAt = Date.now()
      this.onChange?.()
      return
    }
    this.lastRecordedAt = Date.now()
    this.undoStack.push(entry)
    if (this.undoStack.length > UndoManager.LIMIT) {
      this.undoStack.shift()
    }
    this.redoStack.length = 0
    this.onChange?.()
  }

  undo(): Op[] {
    return this.step(this.undoStack, this.redoStack)
  }

  redo(): Op[] {
    return this.step(this.redoStack, this.undoStack)
  }

  private step(from: UndoEntry[], to: UndoEntry[]): Op[] {
    this.applying = true
    try {
      while (from.length > 0) {
        const result = this.revert(from.pop()!)
        if (result === null) continue
        to.push(result.inverse)
        this.onChange?.()
        return result.ops
      }
      this.onChange?.()
      return []
    } finally { this.applying = false }
  }

  private revert(entry: UndoEntry): { ops: Op[]; inverse: UndoEntry } | null {
    if (entry.kind !== 'group') {
      return entry.kind === 'insert' ? this.revertInsert(entry)
        : entry.kind === 'delete' ? this.revertDelete(entry) : this.revertMark(entry)
    }
    const ops: Op[] = []
    const inverses: UndoEntry[] = []
    for (const child of [...entry.entries].reverse()) {
      const result = this.revert(child)
      if (result !== null) { ops.push(...result.ops); inverses.push(result.inverse) }
    }
    return inverses.length > 0 ? { ops, inverse: { kind: 'group', entries: inverses } } : null
  }

  captureMarks(from: number, to: number): SavedSpan[] {
    return this.document.formatting().filter(span => span.from < to && span.to > from)
      .map(span => ({ from: Math.max(from, span.from) - from,
        to: Math.min(to, span.to) - from, marks: [...span.marks] }))
  }

  recordMarkChange(type: string, from: number, to: number, before: Span[], after: string | null | undefined): void {
    const boundaries = new Set([from, to])
    for (const span of before) {
      if (span.from > from && span.from < to) boundaries.add(span.from)
      if (span.to > from && span.to < to) boundaries.add(span.to)
    }
    const points = [...boundaries].sort((a, b) => a - b)
    this.transact(() => {
      for (let i = 0; i < points.length - 1; i++) {
        const start = points[i], end = points[i + 1]
        const previous = before.find(span => span.from <= start && span.to >= end)?.marks.get(type)
        this.recordMark(type, this.document.idAtVisible(start), this.document.idAtVisible(end - 1), previous, after)
      }
    })
  }

  recordMark(
    type: string,
    from: Id,
    to: Id,
    before: string | null | undefined,
    after: string | null | undefined,
  ): void {
    if (this.applying) return
    this.push({ kind: 'mark', type, from, to, before, after })
  }

  private revertMark(entry: UndoableMark): { ops: Op[]; inverse: UndoEntry } | null {
    const from = this.document.visibleIndexOf(entry.from)
    const to = this.document.visibleIndexOf(entry.to)
    if (from === null || to === null || to < from) {
      return null
    }

    const op =
      entry.before === undefined
        ? this.document.removeMark(from, to + 1, entry.type)
        : this.document.addMark(from, to + 1, entry.type, entry.before)
    return {
      ops: [op],
      inverse: { ...entry, before: entry.after, after: entry.before },
    }
  }

  private revertInsert(entry: UndoableInsert): { ops: Op[]; inverse: UndoEntry } | null {
    const before = this.document.text
    const alive = this.document.visibleIndicesOf(entry.first, entry.length)
    if (alive.length === 0) {
      return null
    }

    const ops: Op[] = []
    const inverses: UndoEntry[] = []
    for (let end = alive.length; end > 0;) {
      let start = end - 1
      while (start > 0 && alive[start - 1] === alive[start] - 1) start--
      const from = alive[start], to = alive[end - 1] + 1
      const after = from > 0 ? this.document.idAtVisible(from - 1) : null
      const marks = this.captureMarks(from, to)
      const text = before.slice(from, to)
      ops.push(...deletePieces(this.document, from, to - from))
      inverses.push({ kind: 'delete', after, text, marks })
      end = start
    }
    return { ops, inverse: { kind: 'group', entries: inverses } }
  }

  private revertDelete(entry: UndoableDelete): { ops: Op[]; inverse: UndoEntry } | null {
    let position: number
    if (entry.after === null) {
      position = 0
    } else {
      const anchor = this.document.anchorPosition(entry.after, 'after')
      if (anchor === null) {
        return null
      }
      position = anchor
    }

    const ops: Op[] = []
    const entries: UndoEntry[] = []
    let offset = 0
    for (const piece of insertPieces(entry.text)) {
      const op = this.document.insert(position + offset, piece)
      ops.push(op)
      entries.push({ kind: 'insert', first: op.id, length: piece.length })
      offset += piece.length
    }
    for (const span of entry.marks ?? []) {
      for (const [type, value] of span.marks) {
        ops.push(this.document.addMark(position + span.from, position + span.to, type, value))
      }
    }
    return { ops, inverse: { kind: 'group', entries } }
  }
}
