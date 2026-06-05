import { StateEffect, StateField } from '@codemirror/state'
import { Decoration, EditorView, WidgetType, type DecorationSet } from '@codemirror/view'

/**
 * Kursory i zaznaczenia innych użytkowników.
 *
 * Kluczowa decyzja jest gdzie indziej — w tym, że presence przenosi **identyfikatory znaków
 * CRDT, a nie offsety** (ADR-0006). Gdyby kursor Marka był „kolumną 42", wstawienie znaku
 * przez Annę przed tą pozycją przesunęłoby tekst, ale nie kursor, i Marek pokazywałby cudzy
 * znak. Identyfikator przesuwa się razem z tekstem, bo jest przyczepiony do znaku.
 *
 * Tutaj identyfikatory są już zamienione na offsety — na ostatnią możliwą chwilę, tuż przed
 * rysowaniem, na podstawie aktualnego stanu CRDT.
 */

export interface RemoteCursor {
  replicaId: number
  displayName: string
  color: string
  /** Pozycja kursora w widocznym tekście; null gdy znak został właśnie usunięty. */
  head: number | null
  /** Początek zaznaczenia; null gdy brak zaznaczenia. */
  anchor: number | null
  typing: boolean
}

export const setRemoteCursors = StateEffect.define<RemoteCursor[]>()

/**
 * Chorągiewka z imieniem nad kursorem — wygląd wprost z mockupu
 * (`docs/design/mockup-inkline.html`, klasa `.cursor-caret`).
 */
class CaretWidget extends WidgetType {
  private readonly name: string
  private readonly color: string
  private readonly typing: boolean

  constructor(name: string, color: string, typing: boolean) {
    super()
    this.name = name
    this.color = color
    this.typing = typing
  }

  eq(other: CaretWidget): boolean {
    return other.name === this.name && other.color === this.color && other.typing === this.typing
  }

  toDOM(): HTMLElement {
    const caret = document.createElement('span')
    caret.className = 'remote-caret'
    caret.style.color = this.color
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
        widget: new CaretWidget(cursor.displayName, cursor.color, cursor.typing),
        // side: 1 stawia widget PO pozycji, dzięki czemu kursor własny użytkownika
        // i cudzy w tym samym miejscu nie nachodzą na siebie wizualnie.
        side: 1,
      }).range(cursor.head),
    )
  }

  // Dekoracje muszą być posortowane rosnąco po pozycji — CodeMirror tego wymaga
  // i przy naruszeniu rzuca wyjątkiem dopiero w czasie renderowania.
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
