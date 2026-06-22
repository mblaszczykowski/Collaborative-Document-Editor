import { StateEffect, StateField } from '@codemirror/state'
import { Decoration, EditorView, WidgetType, type DecorationSet } from '@codemirror/view'

/**
 * Other users' cursors and selections.
 *
 * The key decision lives elsewhere — in the fact that presence carries **CRDT character
 * identifiers, not offsets**. If Marek's cursor were "column 42", Anna inserting a
 * character before that position would move the text but not the cursor, and Marek would be
 * pointing at someone else's character. An identifier moves with the text, because it is attached
 * to a character.
 *
 * Here the identifiers have already been turned into offsets — at the last possible moment, just
 * before drawing, from the current CRDT state.
 */

export interface RemoteCursor {
  replicaId: number
  displayName: string
  color: string
  /**
   * The cursor's position in the visible text; null when its character has just been deleted.
   *
   * **Zero is a position, not an absence**, and the code below has always treated it as one — the
   * reason a caret at the start of the document used to vanish for everybody was upstream, in the
   * editor reporting `null` for a place it had no way to express (see `Editor.reportCursor` and
   * `transport/cursorAnchor.ts`). Worth stating here because the guard on the next lines is one
   * `!cursor.head` away from reintroducing it.
   */
  head: number | null
  /** The selection's start; null when there is no selection. */
  anchor: number | null
  typing: boolean
}

export const setRemoteCursors = StateEffect.define<RemoteCursor[]>()

/**
 * The name flag above the caret — the look comes straight from the mockup
 * (the mockup described in the original UI scope, its `.cursor-caret` class — the mockup itself is not in
 * the repository).
 */
class CaretWidget extends WidgetType {
  private readonly name: string
  private readonly color: string
  private readonly typing: boolean
  private readonly position: number

  constructor(name: string, color: string, typing: boolean, position: number) {
    super()
    this.name = name
    this.color = color
    this.typing = typing
    this.position = position
  }

  /**
   * The position IS part of the widget's identity.
   *
   * Without it here, CodeMirror would treat a moved caret as the same widget and reuse the same DOM
   * node — and then the name's appearance animation would not restart and the flag would not show
   * when the cursor moves.
   */
  eq(other: CaretWidget): boolean {
    return (
      other.name === this.name &&
      other.color === this.color &&
      other.typing === this.typing &&
      other.position === this.position
    )
  }

  toDOM(): HTMLElement {
    const caret = document.createElement('span')
    caret.className = 'remote-caret'
    // A custom property, not `color` — see the comment at .remote-caret in styles.css.
    caret.style.setProperty('--caret', this.color)
    const flag = document.createElement('span')
    flag.className = 'remote-caret-flag'
    flag.textContent = this.typing ? `${this.name} …` : this.name
    caret.appendChild(flag)
    return caret
  }

  ignoreEvent(): boolean {
    return true
  }
}

function buildDecorations(cursors: RemoteCursor[], documentLength: number): DecorationSet {
  const decorations = []

  for (const cursor of cursors) {
    if (cursor.head === null || cursor.head > documentLength) continue

    if (cursor.anchor !== null && cursor.anchor !== cursor.head && cursor.anchor <= documentLength) {
      const from = Math.min(cursor.anchor, cursor.head)
      const to = Math.max(cursor.anchor, cursor.head)
      decorations.push(
        Decoration.mark({
          class: 'remote-selection',
          attributes: {
            style: `background:${cursor.color}1F; border-bottom:1.5px solid ${cursor.color}`,
          },
        }).range(from, to),
      )
    }

    decorations.push(
      Decoration.widget({
        widget: new CaretWidget(cursor.displayName, cursor.color, cursor.typing, cursor.head),
        // side: 1 places the widget AFTER the position, so that the user's own caret and someone
        // else's in the same place do not overlap visually.
        side: 1,
      }).range(cursor.head),
    )
  }

  // The decorations have to be sorted ascending by position — CodeMirror requires it and throws
  // only at render time when it is violated.
  decorations.sort((a, b) => a.from - b.from || a.value.startSide - b.value.startSide)
  return Decoration.set(decorations, true)
}

export const remoteCursorsField = StateField.define<DecorationSet>({
  create: () => Decoration.none,

  update(decorations, transaction) {
    let next = decorations.map(transaction.changes)
    for (const effect of transaction.effects) {
      if (effect.is(setRemoteCursors)) {
        next = buildDecorations(effect.value, transaction.state.doc.length)
      }
    }
    return next
  },

  provide: (field) => EditorView.decorations.from(field),
})
