import { Annotation, EditorState } from '@codemirror/state'
import { EditorView, keymap, drawSelection } from '@codemirror/view'
import { defaultKeymap } from '@codemirror/commands'
import { useEffect, useRef } from 'react'
import type { RgaDocument } from '../crdt/document'
import type { UndoManager } from '../crdt/undo'
import type { Id, Op } from '../crdt/types'
import { changesToOps, minimalChange } from './bridge'
import { formattingField, setFormatting } from './formatting'
import { remoteCursorsField, setRemoteCursors, type RemoteCursor } from './remoteCursors'
import type { Span } from '../crdt/marks'

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

/** Najkrótszy odstęp między raportami pozycji kursora — uzasadnienie przy scheduleCursor. */
const CURSOR_REPORT_INTERVAL_MS = 100

export interface EditorProps {
  document: RgaDocument
  undo: UndoManager
  remoteCursors: RemoteCursor[]
  /** Odcinki formatowania wyliczone przez warstwę CRDT (CP13). */
  formatting: Span[]
  onLocalOps: (ops: Op[]) => void
  /** Nadanie albo zdjęcie formatowania na aktualnym zaznaczeniu. */
  onToggleMark: (type: string, from: number, to: number) => void
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

    // Ograniczenie tempa raportowania kursora.
    //
    // Bez tego pozycja leci przy KAŻDYM zdarzeniu edytora: każdym klawiszu, a przy trzymanej
    // strzałce ~30 razy na sekundę (auto-powtarzanie systemu). Serwer odsyła to wszystkim
    // uczestnikom, więc ruch rośnie z kwadratem liczby osób — i to na zdarzeniu, które nie
    // niesie żadnej treści.
    //
    // 100 ms, bo kursor innego użytkownika nie musi być płynniejszy niż 10 klatek na sekundę,
    // żeby wyglądać na żywy; poniżej tego progu dokładamy ruchu bez różnicy widocznej gołym
    // okiem. Wariant TRAILING, nie leading: ostatnia pozycja zawsze zostaje wysłana, więc
    // kursor nie zatrzymuje się w miejscu, w którym akurat wypadł koniec okna.
    let cursorTimer: number | null = null
    let pendingCursor: { head: Id | null; anchor: Id | null } | null = null

    const flushCursor = () => {
      cursorTimer = null
      if (pendingCursor === null) return
      const { head, anchor } = pendingCursor
      pendingCursor = null
      latest.current.onCursorMove(head, anchor)
    }

    const scheduleCursor = (head: Id | null, anchor: Id | null) => {
      pendingCursor = { head, anchor }
      if (cursorTimer === null) {
        cursorTimer = window.setTimeout(flushCursor, CURSOR_REPORT_INTERVAL_MS)
      }
    }

    /**
     * Przełącza formatowanie na zaznaczeniu.
     *
     * Zwraca `true` również wtedy, gdy nie ma czego formatować — bo skrót ma być OBSŁUŻONY
     * niezależnie od tego, czy coś zmienił. Zwrócenie `false` przekazałoby Ctrl+B dalej,
     * do przeglądarki, która ma na to własną obsługę.
     */
    const toggleMark = (type: string): boolean => {
      const selection = view.current?.state.selection.main
      if (selection === undefined || selection.empty) return true
      latest.current.onToggleMark(type, selection.from, selection.to)
      return true
    }

    const state = EditorState.create({
      // Dokument startuje pusty i wypełnia się z operacji przysłanych przez serwer.
      doc: '',
      extensions: [
        // history() świadomie POMINIĘTE (patrz komentarz nad komponentem), ale skróty
        // MUSZĄ działać — edytor, w którym Ctrl+Z nic nie robi, jest zepsuty.
        // Kierujemy je do UndoManagera, który cofa WYŁĄCZNIE własne operacje użytkownika.
        keymap.of([
          // Skróty formatowania. Bez nich pasek narzędzi jest jedyną drogą, a edytor tekstu,
          // w którym Ctrl+B nic nie robi, sprawia wrażenie zepsutego niezależnie od tego,
          // ile przycisków ma na wierzchu.
          { key: 'Mod-b', run: () => toggleMark('bold'), preventDefault: true },
          { key: 'Mod-i', run: () => toggleMark('italic'), preventDefault: true },
          { key: 'Mod-u', run: () => toggleMark('underline'), preventDefault: true },
          { key: 'Mod-z', run: () => { latest.current.onUndo(); return true }, preventDefault: true },
          { key: 'Mod-Shift-z', run: () => { latest.current.onRedo(); return true }, preventDefault: true },
          { key: 'Mod-y', run: () => { latest.current.onRedo(); return true }, preventDefault: true },
        ]),
        keymap.of(defaultKeymap.filter((binding) => binding.key !== 'Mod-z' && binding.key !== 'Mod-y')),
        drawSelection(),
        formattingField,
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
            reportCursor(update.state, current, scheduleCursor)
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
      if (cursorTimer !== null) window.clearTimeout(cursorTimer)
      editor.destroy()
      view.current = null
    }
    // Edytor tworzymy raz na dokument; zmiany propsów obsługuje `latest`.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useEffect(() => {
    view.current?.dispatch({ effects: setRemoteCursors.of(props.remoteCursors) })
  }, [props.remoteCursors])

  useEffect(() => {
    view.current?.dispatch({ effects: setFormatting.of(props.formatting) })
  }, [props.formatting])

  return <div className="editor-host" ref={host} />
}

/**
 * Zgłasza pozycję kursora jako **identyfikatory znaków CRDT**, nie offsety (ADR-0006).
 *
 * Kursor na końcu dokumentu nie ma znaku, na którym mógłby siedzieć — wtedy zgłaszamy
 * null i druga strona rysuje go na końcu. To jedyny przypadek, w którym pozycja kursora
 * nie jest zakotwiczona w tekście i może „odpłynąć" przy zdalnej edycji.
 */
function reportCursor(
  state: EditorState,
  props: EditorProps,
  emit: (head: Id | null, anchor: Id | null) => void,
): void {
  const selection = state.selection.main
  const documentLength = props.document.visibleLength

  const toId = (position: number): Id | null =>
    position > 0 && position <= documentLength ? props.document.idAtVisible(position - 1) : null

  // Mapowanie offsetu na identyfikator dzieje się TUTAJ, przed ograniczeniem tempa,
  // a nie w momencie wysyłki. Gdyby odłożyć je do końca okna, dokument mógłby się w tym
  // czasie zmienić i ten sam offset wskazywałby już inny znak — czyli kursor lądowałby
  // w cudzym tekście. To jest dokładnie ten błąd, przed którym chroni ADR-0006.
  emit(toId(selection.head), selection.empty ? null : toId(selection.anchor))
}
