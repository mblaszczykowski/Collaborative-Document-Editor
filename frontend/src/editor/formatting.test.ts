import { describe, expect, it } from 'vitest'
import { EditorState } from '@codemirror/state'
import { formattingField, setFormatting } from './formatting'
import type { Span } from '../crdt/marks'

const italic = (from: number, to: number): Span => ({
  from,
  to,
  marks: new Map([['italic', null]]),
})

/**
 * The decorations depend on the spans AND on the document, and only one of them arrives as an
 * effect. These pin the orderings.
 */
describe('formatting decorations', () => {
  const withField = (doc: string) =>
    EditorState.create({ doc, extensions: [formattingField] })

  it('renders runs that arrive after the text', () => {
    let state = withField('alfa beta gamma')
    state = state.update({ effects: setFormatting.of([italic(0, 4)]) }).state
    expect(state.field(formattingField).decorations.size).toBe(1)
  })

  /**
   * The snapshot-open path (CP24): the view is created with an empty document and fills from the
   * operations that follow, so the first `setFormatting` lands before the text. Every run clamps
   * to nothing, and the effect does NOT arrive a second time — `refreshStats` returns the same
   * spans and the `Editor` effect is keyed on identity, so it never re-runs.
   *
   * Before the field kept its spans this left a document restored from a snapshot with its
   * formatting silently missing until the next edit. It is the exact sequence `formatting
   * survives a page reload` exercises through the browser.
   */
  it('renders runs that arrived BEFORE the text, once the text catches up', () => {
    let state = withField('')
    state = state.update({ effects: setFormatting.of([italic(0, 4)]) }).state
    // Clamped to nothing against an empty document — that part was always correct.
    expect(state.field(formattingField).decorations.size).toBe(0)

    state = state.update({ changes: { from: 0, insert: 'alfa beta gamma' } }).state
    expect(state.field(formattingField).decorations.size).toBe(1)
    const ranges: number[][] = []
    state.field(formattingField).decorations.between(0, 100, (from, to) => {
      ranges.push([from, to])
    })
    expect(ranges).toEqual([[0, 4]])
  })

  it('maps existing runs through a keystroke rather than recomputing stale ones', () => {
    let state = withField('alfa beta gamma')
    state = state.update({ effects: setFormatting.of([italic(0, 4)]) }).state
    // Typing at the very start: mapping moves the run, which is what stops it flickering while
    // the CRDT layer recomputes.
    state = state.update({ changes: { from: 0, insert: 'X' } }).state
    const ranges: number[][] = []
    state.field(formattingField).decorations.between(0, 100, (from, to) => {
      ranges.push([from, to])
    })
    expect(ranges).toEqual([[1, 5]])
  })

  it('keeps runs empty when there are none', () => {
    let state = withField('alfa')
    state = state.update({ effects: setFormatting.of([]) }).state
    state = state.update({ changes: { from: 4, insert: ' beta' } }).state
    expect(state.field(formattingField).decorations.size).toBe(0)
  })
})
