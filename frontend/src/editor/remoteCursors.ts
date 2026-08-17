import { StateEffect, StateField } from '@codemirror/state'
import { Decoration, EditorView, WidgetType, type DecorationSet } from '@codemirror/view'

export interface RemoteCursor {
  replicaId: number
  displayName: string
  color: string
  head: number | null
  anchor: number | null
  typing: boolean
}

export const setRemoteCursors = StateEffect.define<RemoteCursor[]>()

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
        side: 1,
      }).range(cursor.head),
    )
  }

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
