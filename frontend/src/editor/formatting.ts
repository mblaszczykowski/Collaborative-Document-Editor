import { StateEffect, StateField } from '@codemirror/state'
import { Decoration, EditorView, type DecorationSet } from '@codemirror/view'
import type { Span } from '../crdt/marks'

export const setFormatting = StateEffect.define<Span[]>()

function classOf(type: string): string {
  switch (type) {
    case 'bold':
      return 'cm-mark-bold'
    case 'italic':
      return 'cm-mark-italic'
    case 'underline':
      return 'cm-mark-underline'
    case 'strike':
      return 'cm-mark-strike'
    case 'code':
      return 'cm-mark-code'
    case 'link':
      return 'cm-mark-link'
    case 'comment':
      return 'cm-mark-comment'
    case 'suggest-insert':
      return 'cm-mark-suggest-insert'
    case 'suggest-delete':
      return 'cm-mark-suggest-delete'
    case 'heading':
      return 'cm-mark-heading'
    case 'quote':
      return 'cm-mark-quote'
    case 'list':
      return 'cm-mark-list'
    default:
      return 'cm-mark-unknown'
  }
}

function decorationsFor(spans: Span[], documentLength: number): DecorationSet {
  const ranges = []
  for (const span of spans) {
    const from = Math.max(0, Math.min(span.from, documentLength))
    const to = Math.max(0, Math.min(span.to, documentLength))
    if (from >= to) continue

    const classes = [...span.marks.keys()].map(classOf).join(' ')
    const href = span.marks.get('link')
    const level = span.marks.get('heading')
    const attributes: Record<string, string> = {}
    if (href) attributes.title = href
    if (level) attributes['data-level'] = level

    ranges.push(
      Decoration.mark({
        class: classes,
        attributes: Object.keys(attributes).length > 0 ? attributes : undefined,
      }).range(from, to),
    )
  }
  return Decoration.set(ranges, true)
}

interface Formatting {
  readonly spans: Span[]
  readonly decorations: DecorationSet
}

export const formattingField = StateField.define<Formatting>({
  create() {
    return { spans: [], decorations: Decoration.none }
  },

  update(formatting, transaction) {
    for (const effect of transaction.effects) {
      if (effect.is(setFormatting)) {
        return {
          spans: effect.value,
          decorations: decorationsFor(effect.value, transaction.state.doc.length),
        }
      }
    }
    if (!transaction.docChanged) {
      return formatting
    }
    if (formatting.spans.length > 0 && formatting.decorations.size === 0) {
      return {
        spans: formatting.spans,
        decorations: decorationsFor(formatting.spans, transaction.state.doc.length),
      }
    }
    return {
      spans: formatting.spans,
      decorations: formatting.decorations.map(transaction.changes),
    }
  },

  provide: (field) => EditorView.decorations.from(field, (value) => value.decorations),
})
