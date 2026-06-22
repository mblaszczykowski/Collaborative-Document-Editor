import { StateEffect, StateField } from '@codemirror/state'
import { Decoration, EditorView, type DecorationSet } from '@codemirror/view'
import type { Span } from '../crdt/marks'

/**
 * Drawing text formatting (CP13).
 *
 * This layer is **purely a view**, exactly like the rest of the CodeMirror integration.
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
    // Block structure (CP30). These are marks over a LINE rather than nodes in a tree, because the
    // document is a flat character sequence and a paragraph is the text between two newlines —
    // which is why they belong in this switch alongside bold, and not in a separate mechanism.
    //
    // They were missing here while `styles.css` already carried their rules and
    // `conformance/schema/mark-types.json` already listed them as first-class types, so a heading
    // arriving from another client — or generated server-side — rendered as `cm-mark-unknown` and
    // `.cm-mark-heading[data-level='1']` could never match anything.
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
    // Clamping to the document length: a run computed before the latest change could extend past
    // the text, and CodeMirror then throws while painting. It is the same race that clamping
    // cursor positions guards against.
    const from = Math.max(0, Math.min(span.from, documentLength))
    const to = Math.max(0, Math.min(span.to, documentLength))
    if (from >= to) continue

    const classes = [...span.marks.keys()].map(classOf).join(' ')
    const href = span.marks.get('link')
    const level = span.marks.get('heading')
    // The heading level (CP30) travels as the mark's VALUE, and it is rendered as a data
    // attribute rather than a class per level: values are open, so a level this build has never
    // heard of should still render as a heading rather than as nothing.
    const attributes: Record<string, string> = {}
    if (href) attributes.title = href
    if (level) attributes['data-level'] = level

    ranges.push(
      Decoration.mark({
        class: classes,
        // The link address as an attribute rather than an <a> element: the document is editable,
        // and a clickable link inside an editing surface would capture clicks instead of placing
        // the cursor. The address is visible in the tooltip.
        attributes: Object.keys(attributes).length > 0 ? attributes : undefined,
      }).range(from, to),
    )
  }
  return Decoration.set(ranges, true)
}

/**
 * The formatting runs and the decorations derived from them.
 *
 * ## Why the spans are kept, and not only the decorations
 *
 * `decorationsFor` clamps every run to the CURRENT document length, because a run computed
 * before the latest change can extend past the text and CodeMirror throws while painting. That
 * makes the decorations a function of TWO inputs — the spans and the document — while the effect
 * that carries them only announces one.
 *
 * The two arrive in the wrong order on the path that opens a document from a server snapshot
 * (CP24). The view is created with `doc: ''` and fills from the operations that follow, so the
 * first `setFormatting` lands while the document is still empty: every run clamps to nothing and
 * the decorations become `Decoration.none`. The text arrives immediately afterwards, and mapping
 * an empty set through those changes leaves it empty.
 *
 * That used to heal itself by accident. `refreshStats` built a fresh array on every call, so the
 * effect in `Editor` — keyed on the array's IDENTITY — re-ran on the next refresh and recomputed
 * the runs against a document that by then had the text. Adding a structural comparison to skip
 * redundant state updates removed the redundancy and, with it, the accident: the second refresh
 * produced equal spans, the identity stayed the same, the effect never re-ran, and formatting
 * restored from a snapshot was silently missing until the next edit.
 *
 * Keeping the spans makes the field recompute when EITHER input changes, so the ordering stops
 * mattering. Mapping is still what happens on an ordinary keystroke — the runs are one frame
 * stale there and mapping is the more accurate answer, which is what stops the flicker.
 */
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
    // The document changed and no new runs came with it. Normally mapping is the right answer:
    // the runs are one frame behind a local keystroke, and mapping keeps them where the user can
    // see they belong.
    //
    // The exception is the case above — runs we hold that produced NOTHING, because the document
    // was shorter than they described when they arrived. There is nothing to map, and the runs
    // are the only truth available, so they are recomputed against the document that has now
    // caught up.
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
