import { describe, expect, it } from 'vitest'
import { EditorState } from '@codemirror/state'
import { formattingField, setFormatting } from './formatting'
import type { Span } from '../crdt/marks'

const italic = (from: number, to: number): Span => ({
  from,
  to,
  marks: new Map([['italic', null]]),
})

describe('formatting decorations', () => {
  const withField = (doc: string) =>
    EditorState.create({ doc, extensions: [formattingField] })

  it('renders runs that arrive after the text', () => {
    let state = withField('alfa beta gamma')
    state = state.update({ effects: setFormatting.of([italic(0, 4)]) }).state
    expect(state.field(formattingField).decorations.size).toBe(1)
  })

  it('renders runs that arrived BEFORE the text, once the text catches up', () => {
    let state = withField('')
    state = state.update({ effects: setFormatting.of([italic(0, 4)]) }).state
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
