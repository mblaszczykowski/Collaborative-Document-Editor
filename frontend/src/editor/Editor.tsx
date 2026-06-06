import { Annotation, EditorState } from '@codemirror/state'
import { EditorView, keymap, drawSelection } from '@codemirror/view'
import { defaultKeymap } from '@codemirror/commands'
import { useEffect, useRef } from 'react'
import type { RgaDocument } from '../crdt/document'
import type { UndoManager } from '../crdt/undo'
import type { Id, Op } from '../crdt/types'
import { changesToOps, minimalChange } from './bridge'
import { remoteCursorsField, setRemoteCursors, type RemoteCursor } from './remoteCursors'

/**
 * Edytor: CodeMirror jako **warstwa widoku**, model dokumentu własny (ADR-0006).
 *
 * Przepływ jest jednokierunkowy i to jest jedyna rzecz, którą trzeba tu rozumieć:
 *
 *   klawisz → transakcja CM → operacja CRDT → scalenie → tekst CRDT → z powrotem do CM
 *
 * Nigdy „zmieniamy CM i osobno CRDT". Gdyby oba modele były aktualizowane niezależnie,
 * ich rozjazd byłby kwestią czasu i objawiłby się jako tekst na ekranie niezgodny ze
 * stanem wysyłanym do innych — czyli błąd, którego użytkownik nie umie zgłosić.
 *
 * Historia CodeMirrora (`history()`) jest **wyłączona**. Cofanie musi działać na operacjach
 * CRDT, bo undo na tekście zdejmowałoby również zmiany innych osób.
 */

/** Znacznik transakcji pochodzącej ze scalenia zdalnego — nie wolno z niej robić operacji. */
const remoteChange = Annotation.define<boolean>()

export interface EditorProps {
  document: RgaDocument
  undo: UndoManager
  remoteCursors: RemoteCursor[]
  onLocalOps: (ops: Op[]) => void
  onCursorMove: (head: Id | null, anchor: Id | null) => void
  /** Cofnięcie i ponowienie — obsługiwane wyżej, bo generują operacje do rozesłania. */
  onUndo: () => void
  onRedo: () => void
  /** Rejestruje funkcję, którą warstwa wyżej wywoła po scaleniu operacji zdalnych. */
  registerRemoteApply: (apply: () => void) => void
  readOnly?: boolean
}

export function Editor(props: EditorProps) {
  const host = useRef<HTMLDivElement>(null)
  const view = useRef<EditorView | null>(null)
  const latest = useRef(props)
  latest.current = props

  useEffect(() => {
    if (!host.current) return

    const state = EditorState.create({
      // Dokument startuje pusty i wypełnia się z operacji przysłanych przez serwer.
      doc: '',
      extensions: [
        // history() świadomie POMINIĘTE (patrz komentarz nad komponentem), ale skróty
        // MUSZĄ działać — edytor, w którym Ctrl+Z nic nie robi, jest zepsuty.
        // Kierujemy je do UndoManagera, który cofa WYŁĄCZNIE własne operacje użytkownika.
        keymap.of([
          { key: 'Mod-z', run: () => { latest.current.onUndo(); return true }, preventDefault: true },
          { key: 'Mod-Shift-z', run: () => { latest.current.onRedo(); return true }, preventDefault: true },
          { key: 'Mod-y', run: () => { latest.current.onRedo(); return true }, preventDefault: true },
        ]),
        keymap.of(defaultKeymap.filter((binding) => binding.key !== 'Mod-z' && binding.key !== 'Mod-y')),
        drawSelection(),
        remoteCursorsField,
        EditorView.lineWrapping,
        EditorState.readOnly.of(props.readOnly ?? false),
        EditorView.updateListener.of((update) => {
          const current = latest.current

          if (update.docChanged && !update.transactions.some((t) => t.annotation(remoteChange))) {
            const ops = changesToOps(current.document, update.changes, current.undo)

            // Niezmiennik: po każdej zmianie lokalnej tekst w CodeMirrorze i tekst w CRDT
            // muszą być identyczne. To jest ASERCJA, nie nadzieja — bez niej rozjazd obu
            // modeli objawiłby się dopiero u innego użytkownika, jako „widzę co innego".
            if (import.meta.env.DEV) {
              const inEditor = update.state.doc.toString()
              const inCrdt = current.document.text
              if (inEditor !== inCrdt) {
                console.error(
                  'ROZJAZD modeli po zmianie lokalnej:\n  CodeMirror: %o\n  CRDT:       %o',
                  inEditor,
                  inCrdt,
                )
              }
            }

            if (ops.length > 0) current.onLocalOps(ops)
          }

          if (update.selectionSet || update.docChanged) {
            reportCursor(update.state, current)
          }
        }),
      ],
    })

    const editor = new EditorView({ state, parent: host.current })
    view.current = editor

    // Scalenie zdalne: przenosimy do CodeMirrora MINIMALNĄ zmianę, a nie cały dokument.
    // Podmiana całości byłaby poprawna, ale zrzucałaby kursor i zaznaczenie użytkownika —
    // czyli psułaby pisanie dokładnie wtedy, gdy ktoś inny edytuje ten sam akapit.
    props.registerRemoteApply(() => {
      const current = latest.current
      const target = current.document.text
      const change = minimalChange(editor.state.doc.toString(), target)
      if (change === null) return
      editor.dispatch({ changes: change, annotations: remoteChange.of(true) })
    })

    return () => {
      editor.destroy()
      view.current = null
    }
    // Edytor tworzymy raz na dokument; zmiany propsów obsługuje `latest`.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useEffect(() => {
    view.current?.dispatch({ effects: setRemoteCursors.of(props.remoteCursors) })
  }, [props.remoteCursors])

  return <div className="editor-host" ref={host} />
}

/**
 * Zgłasza pozycję kursora jako **identyfikatory znaków CRDT**, nie offsety (ADR-0006).
 *
 * Kursor na końcu dokumentu nie ma znaku, na którym mógłby siedzieć — wtedy zgłaszamy
 * null i druga strona rysuje go na końcu. To jedyny przypadek, w którym pozycja kursora
 * nie jest zakotwiczona w tekście i może „odpłynąć" przy zdalnej edycji.
 */
function reportCursor(state: EditorState, props: EditorProps): void {
  const selection = state.selection.main
  const documentLength = props.document.visibleLength

  const toId = (position: number): Id | null =>
    position > 0 && position <= documentLength ? props.document.idAtVisible(position - 1) : null

  props.onCursorMove(toId(selection.head), selection.empty ? null : toId(selection.anchor))
}
