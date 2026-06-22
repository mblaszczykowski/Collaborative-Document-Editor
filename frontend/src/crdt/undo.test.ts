import { describe, expect, it } from 'vitest'
import { RgaDocument } from './document'
import { UndoManager } from './undo'

/**
 * Undo (CP4, extended in CP25).
 *
 * There was no test here until CP25, which is worth saying out loud: undo is the one feature in
 * this project whose failures are invisible in a screenshot and permanent in the document.
 *
 * | Test | What it catches |
 * |---|---|
 * | `undoingBoldRemovesIt` | Mark operations never entering the stack — Ctrl+Z after Ctrl+B undoing the previous TEXT change instead. |
 * | `undoingRestoresThePreviousValue` | Undo performing the opposite gesture rather than restoring the previous state. Removing a mark that was already there before takes away formatting the user never applied. |
 * | `anEntryWhoseTextIsGoneIsSkipped` | Guessing. Re-formatting a range that no longer exists puts a mark somewhere arbitrary — worse than doing nothing, because nobody can see why. |
 * | `theStackSurvivesSerialisation` | The stacks dying with the tab, which is what the README listed as a limit before CP25. |
 */

function documentWith(text: string): RgaDocument {
  const document = new RgaDocument(1)
  document.insert(0, text)
  return document
}

/** Records a mark change the way the editor does, and returns the manager. */
function boldFirstWord(document: RgaDocument, undo: UndoManager): void {
  const from = document.idAtVisible(0)!
  const to = document.idAtVisible(2)!
  document.addMark(0, 3, 'bold', null)
  undo.recordMark('bold', from, to, undefined, null)
}

describe('undo of formatting', () => {
  it('undoes a bolding rather than the text change before it', () => {
    const document = documentWith('Ala ma kota')
    const undo = new UndoManager(document)

    boldFirstWord(document, undo)
    expect(document.formatting()).toHaveLength(1)

    undo.undo()

    expect(document.formatting()).toHaveLength(0)
    expect(document.text)
      .toBe('Ala ma kota')
  })

  it('restores the previous value instead of clearing the mark', () => {
    const document = documentWith('Ala ma kota')
    const undo = new UndoManager(document)

    document.addMark(0, 3, 'link', 'https://first.test')
    const from = document.idAtVisible(0)!
    const to = document.idAtVisible(2)!
    document.addMark(0, 3, 'link', 'https://second.test')
    undo.recordMark('link', from, to, 'https://first.test', 'https://second.test')

    undo.undo()

    // Not "no link" — the range HAD a link before, and undoing somebody's change to it must not
    // remove a mark they did not add.
    const marks = document.formatting()[0].marks
    expect(marks.get('link')).toBe('https://first.test')
  })

  it('redoes what it undid', () => {
    const document = documentWith('Ala ma kota')
    const undo = new UndoManager(document)

    boldFirstWord(document, undo)
    undo.undo()
    expect(document.formatting()).toHaveLength(0)

    undo.redo()
    expect(document.formatting()[0].marks.has('bold')).toBe(true)
  })

  it('skips an entry whose anchored text is gone instead of guessing', () => {
    const document = documentWith('Ala ma kota')
    const undo = new UndoManager(document)

    boldFirstWord(document, undo)
    document.delete(0, 4)

    // Nothing to put the formatting back on, so nothing is emitted — and no exception either.
    expect(undo.undo()).toEqual([])
    expect(document.text).toBe('ma kota')
  })

  it('survives serialisation, which is what a reload is', () => {
    const document = documentWith('Ala ma kota')
    const first = new UndoManager(document)
    boldFirstWord(document, first)

    const saved = JSON.parse(JSON.stringify(first.serialise())) as ReturnType<
      UndoManager['serialise']
    >

    const afterReload = new UndoManager(document)
    afterReload.restore(saved)
    afterReload.undo()

    expect(document.formatting()).toHaveLength(0)
  })
})

describe('undo of text', () => {
  it('undoes an insertion by deleting exactly what was inserted', () => {
    const document = documentWith('Ala ')
    const undo = new UndoManager(document)

    const op = document.insert(4, 'ma kota')
    undo.recordInsert(op.id, 'ma kota'.length)
    expect(document.text).toBe('Ala ma kota')

    undo.undo()
    expect(document.text).toBe('Ala ')
  })

  it('undoes a deletion by inserting NEW characters, and says so', () => {
    const document = documentWith('Ala ma kota')
    const undo = new UndoManager(document)

    const anchor = document.idAtVisible(2)!
    const removed = document.text.slice(3)
    document.delete(3, removed.length)
    undo.recordDelete(anchor, removed)

    undo.undo()

    expect(document.text).toBe('Ala ma kota')
    // The identifiers differ from the originals: a CRDT cannot resurrect a tombstone, so the
    // visible effect is the same and the identity is not. Yjs does the same thing.
    expect(document.idAtVisible(3)).not.toEqual(anchor)
  })

  /**
   * Undo of an insertion a collaborator has interleaved.
   *
   * The entry records an identifier and a COUNT, and the old implementation resolved the anchor to
   * a position and deleted that many characters from it. RGA guarantees the identifier still points
   * at the right character; it guarantees nothing about the run still being contiguous — so a
   * remote insertion inside the fragment made undo delete somebody else's text. That is the one
   * thing this module's header says undo exists to prevent.
   *
   * Every shape a fragmented run can take, because the fix walks the run by identity and then
   * deletes contiguous groups highest-index-first, and the index arithmetic is where it would go
   * wrong quietly.
   */
  describe('undoing an insertion a collaborator has interleaved', () => {
    it('removes only my characters when theirs sit between each of mine', () => {
      const me = new RgaDocument(1)
      const undo = new UndoManager(me)
      const insert = me.insert(0, 'abc')
      undo.recordInsert(insert.id, 3)

      const other = new RgaDocument(2)
      other.apply(insert)
      me.apply(other.insert(1, 'X'))
      me.apply(other.insert(3, 'Y'))
      expect(me.text).toBe('aXbYc')

      undo.undo()
      expect(me.text).toBe('XY')
    })

    it('removes only my characters when the run is split into two groups', () => {
      const me = new RgaDocument(1)
      const undo = new UndoManager(me)
      const insert = me.insert(0, 'abcd')
      undo.recordInsert(insert.id, 4)

      const other = new RgaDocument(2)
      other.apply(insert)
      me.apply(other.insert(2, 'ZZ'))
      expect(me.text).toBe('abZZcd')

      undo.undo()
      expect(me.text).toBe('ZZ')
    })

    it('skips the characters a collaborator has already deleted', () => {
      const me = new RgaDocument(1)
      const undo = new UndoManager(me)
      const insert = me.insert(0, 'hello')
      undo.recordInsert(insert.id, 5)

      const other = new RgaDocument(2)
      other.apply(insert)
      me.apply(other.delete(1, 2))
      expect(me.text).toBe('hlo')

      undo.undo()
      expect(me.text).toBe('')
    })

    it('resolves a large run in one sweep rather than one walk per character', () => {
      // A long document that a collaborator has fragmented into thousands of blocks — which is
      // what a shared document looks like after an afternoon, not an exotic construction — and a
      // paste at the end of it.
      const me = new RgaDocument(1)
      const other = new RgaDocument(2)
      const undo = new UndoManager(me)
      other.apply(me.insert(0, 'x'.repeat(2000)))
      for (let i = 0; i < 2000; i++) {
        me.apply(other.insert(i * 2, '.'))
      }

      const paste = me.insert(me.visibleLength, 'p'.repeat(50_000))
      undo.recordInsert(paste.id, 50_000)

      const started = performance.now()
      undo.undo()
      const elapsed = performance.now() - started

      expect(me.text.length).toBe(4000)
      // Resolving each character separately walked the block list from the start every time, and
      // the run sits at the END of it: 653 ms on this machine, on the main thread, for a gesture
      // the user expects to be instant. One sweep does the same work in 4 ms. The bound is loose
      // on purpose — it is here to catch a return to per-character resolution, not to police
      // milliseconds on somebody else's CI machine.
      expect(elapsed).toBeLessThan(150)
    })

    it('redo puts my text back, in one piece', () => {
      const me = new RgaDocument(1)
      const undo = new UndoManager(me)
      const insert = me.insert(0, 'abc')
      undo.recordInsert(insert.id, 3)
      const other = new RgaDocument(2)
      other.apply(insert)
      me.apply(other.insert(1, 'X'))

      undo.undo()
      expect(me.text).toBe('X')
      // Contiguously, at the anchor. A run that somebody else split has no non-arbitrary way to be
      // interleaved again, and putting the author's own text back whole is the least surprising
      // answer — see revertInsert.
      undo.redo()
      expect(me.text).toContain('abc')
    })
  })

  /**
   * The Delete key and Backspace coalesce in opposite directions.
   *
   * The guard used to check only kind and time, and prepend unconditionally — which is right for
   * Backspace and reverses the text of every forward Delete.
   */
  describe('coalescing a run of deletions', () => {
    it('restores the original order after repeated Delete', () => {
      const document = documentWith('abcd')
      const undo = new UndoManager(document)
      for (let i = 0; i < 3; i++) {
        const removed = document.text.slice(0, 1)
        document.delete(0, 1)
        // Deleting forward from a fixed point: the anchor does not move.
        undo.recordDelete(null, removed)
      }
      expect(document.text).toBe('d')

      undo.undo()
      expect(document.text).toBe('abcd')
    })

    it('still restores the original order after repeated Backspace', () => {
      const document = documentWith('abcd')
      const undo = new UndoManager(document)
      for (let i = 0; i < 3; i++) {
        const from = document.visibleLength - 1
        const removed = document.text.slice(from, from + 1)
        // Deleting backwards: each deletion has a new anchor, further left.
        const anchor = from > 0 ? document.idAtVisible(from - 1) : null
        document.delete(from, 1)
        undo.recordDelete(anchor, removed)
      }
      expect(document.text).toBe('a')

      undo.undo()
      expect(document.text).toBe('abcd')
    })
  })
})
