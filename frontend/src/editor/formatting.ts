import { StateEffect, StateField, type Text } from '@codemirror/state'
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

function decorationsFor(spans: Span[], doc: Text): DecorationSet {
  const documentLength = doc.length
  const ranges = []
  const lines = new Map<number, { classes: Set<string>; level?: string }>()
  for (const span of spans) {
    const from = Math.max(0, Math.min(span.from, documentLength))
    const to = Math.max(0, Math.min(span.to, documentLength))
    if (from >= to) continue

    const classes = [...span.marks.keys()].filter(type => !['heading', 'quote', 'list'].includes(type))
      .map(classOf).join(' ')
    for (const type of ['heading', 'quote', 'list']) {
      if (!span.marks.has(type)) continue
      let line = doc.lineAt(from)
      while (line.from < to) {
        const entry = lines.get(line.from) ?? { classes: new Set<string>() }
        entry.classes.add(classOf(type))
        if (type === 'heading') entry.level = span.marks.get(type) ?? undefined
        lines.set(line.from, entry)
        if (line.number === doc.lines) break
        line = doc.line(line.number + 1)
      }
    }
    const href = span.marks.get('link')
    const level = span.marks.get('heading')
    const attributes: Record<string, string> = {}
    if (href) attributes.title = href
    if (level) attributes['data-level'] = level

    if (classes) ranges.push(
      Decoration.mark({
        class: classes,
        attributes: Object.keys(attributes).length > 0 ? attributes : undefined,
      }).range(from, to),
    )
  }
  for (const [from, entry] of lines) {
    ranges.push(Decoration.line({ attributes: { class: [...entry.classes].join(' '),
      ...(entry.level ? { 'data-level': entry.level } : {}) } }).range(from))
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
          decorations: decorationsFor(effect.value, transaction.state.doc),
        }
      }
    }
    if (!transaction.docChanged) {
      return formatting
    }
    if (formatting.spans.length > 0 && formatting.decorations.size === 0) {
      return {
        spans: formatting.spans,
        decorations: decorationsFor(formatting.spans, transaction.state.doc),
      }
    }
    return {
      spans: formatting.spans,
      decorations: formatting.decorations.map(transaction.changes),
    }
  },

  provide: (field) => EditorView.decorations.from(field, (value) => value.decorations),
})
