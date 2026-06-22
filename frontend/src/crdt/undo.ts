import type { RgaDocument } from './document'
import { idEquals } from './types'
import type { Id, Op } from './types'

/**
 * Undo and redo — CRDT-aware.
 *
 * CodeMirror's history is disabled (the editor-layer decision) because it
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

    // Coalescing a run of deletions, and the DIRECTION decides how. The two keys behave
    // differently and the anchor is what tells them apart:
    //
    // Backspace — characters disappear right to left, so each deletion has a NEW anchor further
    // left, and the fragment belongs BEFORE the one already recorded.
    // Delete — characters disappear left to right from a fixed point, so the anchor does not
    // move, and the fragment belongs AFTER the one already recorded.
    //
    // Prepending unconditionally (which is what this did) reverses the text of every forward
    // Delete: "abcd" deleted three times from position 0 undid to "cbad".
    //
    // What this still does NOT separate: two backspaces a second apart at unrelated places in the
    // document. Both take the second branch and merge into one entry, so undo re-inserts them
    // together at the later anchor. Telling that apart needs to know that the new fragment ends
    // exactly where the previous one began, and the identifiers that would prove it are not in
    // this layer — `recordDelete` receives the text and the anchor, not the deleted characters'
    // ids. Left as a known limit rather than guessed at, because guessing wrong here re-inserts
    // the user's text somewhere they did not delete it from.
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

  /**
   * Undoing an insertion = deleting the characters that are still alive — **found by identity**.
   *
   * This used to resolve the anchor to a position and then delete `entry.length` characters from
   * it. RGA guarantees the identifier still points at the right character; it guarantees nothing
   * about the run still being contiguous. A remote insertion landing inside the fragment pushes
   * somebody else's characters into that range, and deleting by length took them with it:
   * typing "hello", a collaborator wedging "XX" at index 3, then Ctrl+Z left "lo" and destroyed
   * their "XX". That is precisely what this module's header says undo must never do.
   *
   * Every character of the run is therefore resolved separately. The ones a collaborator has
   * already deleted resolve to `null` and are skipped, which is also the "undo only what is left"
   * case the old comment described and did not implement correctly either.
   */
  private revertInsert(entry: UndoableInsert): { ops: Op[]; inverse: UndoEntry } | null {
    // Resolved BEFORE any deletion: every index below is an index into this state.
    //
    // One sweep for the whole run, not `visibleIndexOf` per character: that walked the block list
    // once per character, so undoing a 50 000-character paste on a fragmented document took half a
    // second of blocked main thread — for a gesture the user expects to be instant. The result is
    // ascending by construction, which the run-splitting loop below depends on.
    const before = this.document.text
    const alive = this.document.visibleIndicesOf(entry.first, entry.length)
    if (alive.length === 0) {
      return null
    }

    const text = alive.map((index) => before[index]).join('')
    const anchor = alive[0] > 0 ? this.document.idAtVisible(alive[0] - 1) : null

    // Contiguous runs, deleted HIGHEST FIRST so that the indices of the runs still to come are not
    // shifted by the deletions already made.
    const ops: Op[] = []
    for (let end = alive.length; end > 0;) {
      let start = end - 1
      while (start > 0 && alive[start - 1] === alive[start] - 1) {
        start--
      }
      ops.push(this.document.delete(alive[start], end - start))
      end = start
    }

    // Redo re-inserts the fragment as one contiguous block at the anchor. When the run had been
    // split by somebody else's text there is no non-arbitrary way to interleave it again, and
    // putting the user's own text back in one piece is the answer least likely to surprise them.
    return { ops, inverse: { kind: 'delete', after: anchor, text } }
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
