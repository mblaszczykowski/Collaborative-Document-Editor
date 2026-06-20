import { StateEffect, StateField } from '@codemirror/state'
import { Decoration, EditorView, type DecorationSet } from '@codemirror/view'
import type { Span } from '../crdt/marks'

/**
 * Drawing text formatting (CP13).
 *
 * This layer is **purely a view**, exactly like the rest of the CodeMirror integration (ADR-0006).
 * It does not decide what is bold — it receives finished runs computed by the CRDT layer and turns
 * them into decorations. If it decided for itself, it would be a third model of the document beside
 * the CRDT and CodeMirror, that is, a third thing to drift apart.
 *
 * The runs arrive in visible coordinates, computed at the last moment before drawing — for the same
 * reason cursors work that way: the document may have changed between computation and paint.
 */

export const setFormatting = StateEffect.define<Span[]>()

/**
 * A CSS class per mark type.
 *
 * An unknown type gets a generic class rather than being skipped. A mark applied by a newer client
 * version should be visible as SOMETHING — no distinction at all would mean a user on an older
 * version does not know the text is formatted, and would delete the formatting without knowing it
 * existed.
 */
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
      // A comment anchor is a mark like any other — the only difference is that its value is a
      // thread identifier rather than formatting content.
      return 'cm-mark-comment'
    // Suggestions (CP23) are the third thing built out of this layer, and they need nothing
    // here beyond a class: the proposed text is ordinary text, and the mark is what says it is
    // a proposal. Deleting is shown struck through and inserting underlined, because that is
    // how every editor with track changes has shown it for thirty years.
    case 'suggest-insert':
      return 'cm-mark-suggest-insert'
    case 'suggest-delete':
      return 'cm-mark-suggest-delete'
    default:
      return 'cm-mark-unknown'
  }
}

function decorationsFor(spans: Span[], documentLength: number): DecorationSet {
  const ranges = []
  for (const span of spans) {
    // Clamping to the document length: a run computed before the latest change could extend past
    // the text, and CodeMirror then throws while painting. It is the same race that clamping
    // cursor positions guards against.
    const from = Math.max(0, Math.min(span.from, documentLength))
    const to = Math.max(0, Math.min(span.to, documentLength))
    if (from >= to) continue

    const classes = [...span.marks.keys()].map(classOf).join(' ')
    const href = span.marks.get('link')
    ranges.push(
      Decoration.mark({
        class: classes,
        // The link address as an attribute rather than an <a> element: the document is editable,
        // and a clickable link inside an editing surface would capture clicks instead of placing
        // the cursor. The address is visible in the tooltip.
        attributes: href ? { title: href } : undefined,
      }).range(from, to),
    )
  }
  return Decoration.set(ranges, true)
}

export const formattingField = StateField.define<DecorationSet>({
  create() {
    return Decoration.none
  },

  update(decorations, transaction) {
    // First map the existing decorations through the document's changes. Without this the
    // formatting would "flicker" on every keystroke: disappearing for one frame and coming back
    // only once the CRDT layer had recomputed the runs.
    let updated = decorations.map(transaction.changes)
    for (const effect of transaction.effects) {
      if (effect.is(setFormatting)) {
        updated = decorationsFor(effect.value, transaction.state.doc.length)
      }
    }
    return updated
  },

  provide: (field) => EditorView.decorations.from(field),
})
