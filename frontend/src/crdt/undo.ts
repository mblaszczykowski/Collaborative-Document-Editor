import type { RgaDocument } from './document'
import { idEquals } from './types'
import type { Id, Op } from './types'

interface UndoableInsert {
  kind: 'insert'
  first: Id
  length: number
}

interface UndoableDelete {
  kind: 'delete'
  after: Id | null
  text: string
}

interface UndoableMark {
  kind: 'mark'
  type: string
  from: Id
  to: Id
  before: string | null | undefined
  after: string | null | undefined
}

type UndoEntry = UndoableInsert | UndoableDelete | UndoableMark

function isUndoEntry(value: unknown): value is UndoEntry {
  if (typeof value !== 'object' || value === null) return false
  const entry = value as Record<string, unknown>
  switch (entry['kind']) {
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
  return typeof candidate['replicaId'] === 'number' && typeof candidate['lamport'] === 'number'
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
      previous !== undefined &&
      previous.kind === 'insert' &&
      this.withinWindow() &&
      previous.first.replicaId === first.replicaId &&
      previous.first.lamport + previous.length === first.lamport
    ) {
      previous.length += length
      this.lastRecordedAt = Date.now()
      this.redoStack.length = 0
      this.onChange?.()
      return
    }

    this.push({ kind: 'insert', first, length })
  }

  recordDelete(after: Id | null, text: string): void {
    if (this.applying) return

    const previous = this.undoStack.at(-1)
    if (previous !== undefined && previous.kind === 'delete' && this.withinWindow()) {
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

    this.push({ kind: 'delete', after, text })
  }

  private withinWindow(): boolean {
    return Date.now() - this.lastRecordedAt < UndoManager.COALESCE_WINDOW_MS
  }

  private push(entry: UndoEntry): void {
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
    const entry = from.pop()
    if (entry === undefined) return []

    this.applying = true
    try {
      const result =
        entry.kind === 'insert'
          ? this.revertInsert(entry)
          : entry.kind === 'delete'
            ? this.revertDelete(entry)
            : this.revertMark(entry)
      if (result === null) {
        return this.step(from, to)
      }
      to.push(result.inverse)
      this.onChange?.()
      return result.ops
    } finally {
      this.applying = false
    }
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

    const text = alive.map((index) => before[index]).join('')
    const anchor = alive[0] > 0 ? this.document.idAtVisible(alive[0] - 1) : null

    const ops: Op[] = []
    for (let end = alive.length; end > 0;) {
      let start = end - 1
      while (start > 0 && alive[start - 1] === alive[start] - 1) {
        start--
      }
      ops.push(this.document.delete(alive[start], end - start))
      end = start
    }

    return { ops, inverse: { kind: 'delete', after: anchor, text } }
  }

  private revertDelete(entry: UndoableDelete): { ops: Op[]; inverse: UndoEntry } | null {
    let position: number
    if (entry.after === null) {
      position = 0
    } else {
      const anchor = this.document.visibleIndexOf(entry.after)
      if (anchor === null) {
        return null
      }
      position = anchor + 1
    }

    const op = this.document.insert(position, entry.text)
    return { ops: [op], inverse: { kind: 'insert', first: op.id, length: entry.text.length } }
  }
}
