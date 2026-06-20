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
})
