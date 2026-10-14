import { ChangeSet, Text } from '@codemirror/state'
import { describe, expect, it } from 'vitest'
import { RgaDocument } from '../crdt/document'
import { UndoManager } from '../crdt/undo'
import { changesToOps, minimalChange } from './bridge'

describe('minimalChange', () => {
  it('an insertion in the middle touches only the inserted fragment', () => {
    expect(minimalChange('abcdef', 'abcXYdef')).toEqual({ from: 3, to: 3, insert: 'XY' })
  })

  it('a deletion in the middle leaves its surroundings alone', () => {
    expect(minimalChange('abcdef', 'abef')).toEqual({ from: 2, to: 4, insert: '' })
  })

  it('identical text produces no change', () => {
    expect(minimalChange('abc', 'abc')).toBeNull()
  })

  it('repeated characters do not confuse the suffix computation', () => {
    const change = minimalChange('aaa', 'aa')!
    expect(change.to - change.from).toBe(1)
    expect(change.insert).toBe('')
    expect('aaa'.slice(0, change.from) + change.insert + 'aaa'.slice(change.to)).toBe('aa')
  })

  it('a whole-text replacement when nothing is in common', () => {
    expect(minimalChange('abc', 'xyz')).toEqual({ from: 0, to: 3, insert: 'xyz' })
  })
})

describe('changesToOps', () => {
  function applyLocal(initial: string, changes: ChangeSet): RgaDocument {
    const document = new RgaDocument(1)
    if (initial.length > 0) document.insert(0, initial)
    changesToOps(document, changes)
    return document
  }

  it('a single insertion', () => {
    const changes = ChangeSet.of([{ from: 3, insert: 'XY' }], 6)
    expect(applyLocal('abcdef', changes).text).toBe('abcXYdef')
  })

  it('a single deletion', () => {
    const changes = ChangeSet.of([{ from: 2, to: 4 }], 6)
    expect(applyLocal('abcdef', changes).text).toBe('abef')
  })

  it('SEVERAL changes at once - a multiple selection', () => {
    const changes = ChangeSet.of(
      [
        { from: 1, insert: 'X' },
        { from: 4, insert: 'Y' },
      ],
      6,
    )
    expect(applyLocal('abcdef', changes).text).toBe('aXbcdYef')
  })

  it('replacing selected text in several places', () => {
    const changes = ChangeSet.of(
      [
        { from: 0, to: 2, insert: 'Z' },
        { from: 4, to: 6, insert: 'W' },
      ],
      6,
    )
    expect(applyLocal('abcdef', changes).text).toBe('ZcdW')
  })

  it('the result agrees with what CodeMirror itself computed', () => {
    const before = 'The quick brown fox'
    const changes = ChangeSet.of(
      [
        { from: 4, to: 9, insert: 'slow' },
        { from: 16, insert: 'es' },
      ],
      before.length,
    )
    const expected = changes.apply(Text.of([before])).toString()
    expect(applyLocal(before, changes).text).toBe(expected)
  })
})

describe('pasting something large', () => {
  it('splits one paste into operations that each fit in a frame', () => {
    const document = new RgaDocument(1)
    const pasted = 'x'.repeat(20_000)

    const ops = changesToOps(document, ChangeSet.of([{ from: 0, insert: pasted }], 0))

    expect(ops).toHaveLength(3)
    for (const op of ops) {
      expect(op.kind).toBe('insert')
      if (op.kind === 'insert') expect(op.text.length).toBeLessThanOrEqual(8192)
    }
    expect(document.text).toBe(pasted)
  })

  const surrogatePairAtTheBoundary = 'x'.repeat(8191) + '😀'.repeat(500)

  function endsMidCharacter(piece: string): boolean {
    const last = piece.charCodeAt(piece.length - 1)
    return last >= 0xd800 && last <= 0xdbff
  }

  function beginsMidCharacter(piece: string): boolean {
    const first = piece.charCodeAt(0)
    return first >= 0xdc00 && first <= 0xdfff
  }

  it('never cuts a surrogate pair across two operations', () => {
    const document = new RgaDocument(1)

    const ops = changesToOps(
      document,
      ChangeSet.of([{ from: 0, insert: surrogatePairAtTheBoundary }], 0),
    )

    let reassembled = ''
    for (const op of ops) {
      expect(op.kind).toBe('insert')
      if (op.kind !== 'insert') continue
      expect(endsMidCharacter(op.text)).toBe(false)
      expect(beginsMidCharacter(op.text)).toBe(false)
      expect(op.text.length).toBeLessThanOrEqual(8192)
      reassembled += op.text
    }
    expect(reassembled).toBe(surrogatePairAtTheBoundary)
    expect(document.text).toBe(surrogatePairAtTheBoundary)
  })

  it('still undoes an emoji paste in one step', () => {
    const document = new RgaDocument(1)
    const undo = new UndoManager(document)
    changesToOps(
      document,
      ChangeSet.of([{ from: 0, insert: surrogatePairAtTheBoundary }], 0),
      undo,
    )

    undo.undo()

    expect(document.text).toBe('')
  })

  it('undoes a paste in one step, not three', () => {
    const document = new RgaDocument(1)
    const undo = new UndoManager(document)
    changesToOps(document, ChangeSet.of([{ from: 0, insert: 'y'.repeat(20_000) }], 0), undo)

    undo.undo()

    expect(document.text).toBe('')
  })
})

describe('collaborative undo transactions', () => {
  it('undoes replacement text and its formatting in one action', () => {
    const model = new RgaDocument(1)
    model.insert(0, 'abc')
    model.addMark(0, 3, 'bold', null)
    const undo = new UndoManager(model)
    changesToOps(model, ChangeSet.of({ from: 0, to: 3, insert: 'xyz' }, 3), undo)
    undo.undo()
    expect(model.text).toBe('abc')
    expect(model.formatting()[0].marks.has('bold')).toBe(true)
    undo.redo()
    expect(model.text).toBe('xyz')
  })

  it('keeps a large Unicode paste chunked when it is redone', () => {
    const text = '🙂'.repeat(40_000)
    const model = new RgaDocument(1)
    const undo = new UndoManager(model)
    changesToOps(model, ChangeSet.of({ from: 0, insert: text }, 0), undo)
    undo.undo()
    const ops = undo.redo()
    expect(model.text).toBe(text)
    expect(ops.filter(op => op.kind === 'insert').length).toBeGreaterThan(1)
    for (const op of ops) {
      expect(new TextEncoder().encode(JSON.stringify(op)).length).toBeLessThan(60_000)
      if (op.kind === 'insert') expect(new TextDecoder().decode(new TextEncoder().encode(op.text))).toBe(op.text)
    }
  })

  it('does not merge deletions at unrelated cursor positions', () => {
    const model = new RgaDocument(1)
    model.insert(0, 'abcdef')
    const undo = new UndoManager(model)
    changesToOps(model, ChangeSet.of({ from: 1, to: 2 }, 6), undo)
    changesToOps(model, ChangeSet.of({ from: 3, to: 4 }, 5), undo)
    undo.undo()
    expect(model.text).toBe('acdef')
    undo.undo()
    expect(model.text).toBe('abcdef')
  })

  it('groups contiguous backspaces but preserves their formatting', () => {
    const model = new RgaDocument(1)
    model.insert(0, 'abcd')
    model.addMark(1, 4, 'italic', null)
    const undo = new UndoManager(model)
    for (let length = 4; length > 1; length--) {
      changesToOps(model, ChangeSet.of({ from: length - 1, to: length }, length), undo)
    }
    undo.undo()
    expect(model.text).toBe('abcd')
    expect(model.formatting()[0]).toMatchObject({ from: 1, to: 4 })
  })

  it('resolves a caret anchor through distant remote inserts', () => {
    const model = new RgaDocument(1)
    const initial = model.insert(0, 'abcdef')
    const caretCharacter = model.idAtVisible(2)
    const other = new RgaDocument(2)
    other.apply(initial)
    model.apply(other.insert(1, 'X'))
    model.apply(other.insert(7, 'Y'))
    expect(model.text).toBe('aXbcdefY')
    expect(model.anchorPosition(caretCharacter, 'after')).toBe(4)
  })
})

describe('fragmented deletion frame bounds', () => {
  it('splits a deletion of many replica runs without changing its undo boundary', () => {
    const model = new RgaDocument(1)
    for (let i = 0; i < 2500; i++) {
      model.apply({ kind: 'insert', id: { replicaId: i + 2, lamport: i },
        originLeft: i === 0 ? null : model.idAtVisible(model.visibleLength - 1), text: 'x' })
    }
    const undo = new UndoManager(model)
    const ops = changesToOps(model, ChangeSet.of({ from: 0, to: 2500 }, 2500), undo)
    expect(ops).toHaveLength(3)
    expect(model.text).toBe('')
    for (const op of ops) expect(new TextEncoder().encode(JSON.stringify(op)).length).toBeLessThan(60_000)
    undo.undo()
    expect(model.text).toBe('x'.repeat(2500))
  })
})
