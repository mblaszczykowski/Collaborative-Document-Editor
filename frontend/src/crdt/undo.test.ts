import { describe, expect, it } from 'vitest'
import { RgaDocument } from './document'
import { UndoManager } from './undo'

function documentWith(text: string): RgaDocument {
  const document = new RgaDocument(1)
  document.insert(0, text)
  return document
}

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

describe('undo state restored from an older format', () => {
  it('drops an entry whose kind this version does not know', () => {
    const document = documentWith('Ala ma kota')
    const undo = new UndoManager(document)

    undo.restore({ undo: [{ kind: 'annotate', note: 'from a future version' }], redo: [] })

    expect(undo.canUndo).toBe(false)
    expect(() => undo.undo()).not.toThrow()
    expect(undo.undo()).toEqual([])
    expect(document.text).toBe('Ala ma kota')
  })

  it('drops a KNOWN kind whose anchor is missing', () => {
    const document = documentWith('Ala ma kota')
    const undo = new UndoManager(document)

    undo.restore({ undo: [{ kind: 'mark', type: 'bold', before: undefined }], redo: [] })

    expect(undo.canUndo).toBe(false)
    expect(() => undo.undo()).not.toThrow()
  })

  it('keeps the entries it does understand alongside the ones it drops', () => {
    const document = documentWith('Ala ma kota')
    const recorder = new UndoManager(document)
    boldFirstWord(document, recorder)
    const good = recorder.serialise().undo[0]

    const undo = new UndoManager(document)
    undo.restore({ undo: [{ kind: 'annotate' }, good, { kind: 'insert', first: null, length: 3 }],
      redo: [] })

    expect(undo.canUndo).toBe(true)
    undo.undo()
    expect(document.formatting()).toHaveLength(0)
  })

  it('survives storage holding something that is not a pair of stacks at all', () => {
    const document = documentWith('Ala ma kota')
    const undo = new UndoManager(document)

    expect(() => undo.restore(null)).not.toThrow()
    expect(() => undo.restore({ undo: 'not an array', redo: 7 })).not.toThrow()
    expect(undo.canUndo).toBe(false)
    expect(undo.canRedo).toBe(false)
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
    expect(document.idAtVisible(3)).not.toEqual(anchor)
  })

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
      undo.redo()
      expect(me.text).toContain('abc')
    })
  })

  describe('coalescing a run of deletions', () => {
    it('restores the original order after repeated Delete', () => {
      const document = documentWith('abcd')
      const undo = new UndoManager(document)
      for (let i = 0; i < 3; i++) {
        const removed = document.text.slice(0, 1)
        document.delete(0, 1)
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
