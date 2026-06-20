import type { RgaDocument } from './document'
import type { Id, Op } from './types'

/**
 * Undo and redo — CRDT-aware.
 *
 * CodeMirror's history is disabled ([ADR-0006](../../docs/adr/0006-warstwa-edytora.md)) because it
 * works on the text: an undo would also take back other people's changes that landed in the
 * meantime. In a shared editor that is not an inconvenience but the deletion of somebody else's
 * work.
 *
 * Undo therefore has to operate on **one's own operations**, and that is the whole difficulty: in a
 * CRDT an operation cannot be "inverted". A deleted character has a tombstone and does not come
 * back to life; restoring text means inserting **new** characters with new identifiers. The visible
 * effect is the same, the characters' identity is not. Yjs does the same.
 *
 * ### Anchors, not positions
 *
 * A stack entry remembers a **character identifier**, not an offset. An offset recorded at edit
 * time is stale by the time of the undo, because others have been typing. An identifier moves with
 * the text.
 *
 * ### What this implementation does NOT do
 *
 * If the anchor has meanwhile been deleted by somebody else, the entry is **skipped**, not guessed
 * at. Inserting the text "roughly there" would be worse than doing nothing: the user would see
 * their text in a random place with no idea why.
 */

interface UndoableInsert {
  kind: 'insert'
  /** The first inserted character — the anchor for finding the fragment when undoing. */
  first: Id
  /** Grows as consecutive keystrokes are coalesced into one step. */
  length: number
}

interface UndoableDelete {
  kind: 'delete'
  /** The character immediately BEFORE the deleted fragment; null when deleting from the document's start. */
  after: Id | null
  text: string
}

type UndoEntry = UndoableInsert | UndoableDelete

export class UndoManager {
  private readonly undoStack: UndoEntry[] = []
  private readonly redoStack: UndoEntry[] = []
  private applying = false

  /**
   * The stack depth.
   *
   * Bounded, because the stack grows with every edit and a document edited for hours would keep the
   * whole session in memory. 200 steps is well beyond what anyone undoes in practice.
   */
  private static readonly LIMIT = 200

  /**
   * The window for coalescing consecutive keystrokes into one undo step.
   *
   * Without it every letter is a separate entry and Ctrl+Z removes one character at a time —
   * behaviour no editor has and which a user reads as broken. A second's pause ends a group,
   * because that is roughly how long the pause between "thoughts" lasts while writing.
   */
  private static readonly COALESCE_WINDOW_MS = 1000

  private lastRecordedAt = 0

  private readonly document: RgaDocument

  constructor(document: RgaDocument) {
    this.document = document
  }

  get canUndo(): boolean {
    return this.undoStack.length > 0
  }

  get canRedo(): boolean {
    return this.redoStack.length > 0
  }

  /**
   * Records a local change.
   *
   * Called ONLY for one's own operations. Recording other people's would mean Ctrl+Z undoing
   * somebody else's work — exactly what disabling CodeMirror's history protects against.
   */
  recordInsert(first: Id, length: number): void {
    if (this.applying) return

    // Coalesce with the previous entry when this is a continuation of the same typing: the same
    // replica, immediately consecutive clock values, a short pause.
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
      return
    }

    this.push({ kind: 'insert', first, length })
  }

  recordDelete(after: Id | null, text: string): void {
    if (this.applying) return

    // Likewise for deleting with backspace: successive characters disappear right to left, so the
    // new fragment is prepended BEFORE the previously recorded one, and the anchor becomes the one
    // from the more recent deletion.
    const previous = this.undoStack.at(-1)
    if (previous !== undefined && previous.kind === 'delete' && this.withinWindow()) {
      previous.text = text + previous.text
      previous.after = after
      this.lastRecordedAt = Date.now()
      this.redoStack.length = 0
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
    // A new change invalidates the redo branch — standard behaviour for any editor.
    this.redoStack.length = 0
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
      const result = entry.kind === 'insert' ? this.revertInsert(entry) : this.revertDelete(entry)
      if (result === null) {
        // The anchor is gone — skip the entry and try the next one. Without this, one
        // irreversible step would block undoing everything beneath it.
        return this.step(from, to)
      }
      to.push(result.inverse)
      return result.ops
    } finally {
      this.applying = false
    }
  }

  /** Undoing an insertion = deleting the characters that are still alive. */
  private revertInsert(entry: UndoableInsert): { ops: Op[]; inverse: UndoEntry } | null {
    const start = this.document.visibleIndexOf(entry.first)
    if (start === null) {
      return null
    }
    // Somebody may already have deleted part of this fragment — we undo only what is left.
    const available = Math.min(entry.length, this.document.visibleLength - start)
    if (available <= 0) {
      return null
    }

    const text = this.document.text.slice(start, start + available)
    const anchor = start > 0 ? this.document.idAtVisible(start - 1) : null
    const op = this.document.delete(start, available)
    return { ops: [op], inverse: { kind: 'delete', after: anchor, text } }
  }

  /** Undoing a deletion = inserting the text back, as NEW characters. */
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
