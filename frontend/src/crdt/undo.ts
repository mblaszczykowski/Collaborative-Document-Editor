import type { RgaDocument } from './document'
import type { Id, Op } from './types'

/**
 * Undo and redo — CRDT-aware.
 *
 * CodeMirror's history is disabled ([ADR-0006](../../docs/adr/0006-editor-layer.md)) because it
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

/**
 * A formatting change (CP25).
 *
 * The interesting field is `before`, and it is why undoing a mark is not "remove the mark". The
 * inverse of "make this bold" is "put back whatever this range had before", which is not the same
 * thing whenever somebody else's mark of the same type was already there and losing the MarkSet
 * resolution: removing would take away their formatting too, and they never touched anything.
 *
 * `undefined` means the type was absent over the range; `null` means it was present with no value,
 * which is what every mark that carries no payload looks like (bold, italic, underline, strike).
 * The two are different states and the distinction is load-bearing — the same one that made a
 * restore silently keep formatting it had been asked to remove, in CP22.
 */
interface UndoableMark {
  kind: 'mark'
  type: string
  /** First and last character of the range, as anchors — positions drift, identifiers do not. */
  from: Id
  to: Id
  before: string | null | undefined
  after: string | null | undefined
}

type UndoEntry = UndoableInsert | UndoableDelete | UndoableMark

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

  /**
   * Called after every change to the stacks, so they can be persisted (CP25).
   *
   * A callback rather than the manager writing to storage itself: this class is part of the CRDT
   * layer and knows nothing about IndexedDB, and the one test that matters here — that undo puts
   * the right state back — should not need a database.
   */
  private onChange: (() => void) | null = null

  constructor(document: RgaDocument) {
    this.document = document
  }

  observe(onChange: () => void): void {
    this.onChange = onChange
  }

  /**
   * The stacks, in a form that survives a reload (CP25).
   *
   * Every entry is already made of things that outlive a session: character identifiers and text.
   * Positions would not be — which is the same reason the entries hold anchors in the first place.
   */
  serialise(): { undo: UndoEntry[]; redo: UndoEntry[] } {
    return { undo: [...this.undoStack], redo: [...this.redoStack] }
  }

  restore(state: { undo: UndoEntry[]; redo: UndoEntry[] }): void {
    this.undoStack.length = 0
    this.redoStack.length = 0
    this.undoStack.push(...state.undo.slice(-UndoManager.LIMIT))
    this.redoStack.push(...state.redo.slice(-UndoManager.LIMIT))
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
      this.onChange?.()
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
    // A new change invalidates the redo branch — standard behaviour for any editor.
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
        // The anchor is gone — skip the entry and try the next one. Without this, one
        // irreversible step would block undoing everything beneath it.
        return this.step(from, to)
      }
      to.push(result.inverse)
      this.onChange?.()
      return result.ops
    } finally {
      this.applying = false
    }
  }

  /**
   * Records a formatting change so that Ctrl+Z can put the previous state back (CP25).
   *
   * Until CP25 mark operations never entered the stack at all, so Ctrl+Z after Ctrl+B undid **the
   * previous text change** — predictable once you know, and surprising every time until then. The
   * README listed it as a known limit.
   *
   * @param before what the range carried before, `undefined` when the type was absent
   */
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

  /**
   * Undoing a formatting change = putting the previous STATE back, not performing the opposite
   * gesture.
   */
  private revertMark(entry: UndoableMark): { ops: Op[]; inverse: UndoEntry } | null {
    const from = this.document.visibleIndexOf(entry.from)
    const to = this.document.visibleIndexOf(entry.to)
    if (from === null || to === null || to < from) {
      // The anchored text is gone. Skipped rather than guessed, exactly as for text entries:
      // re-formatting a range that is not there any more would put a mark somewhere arbitrary.
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
