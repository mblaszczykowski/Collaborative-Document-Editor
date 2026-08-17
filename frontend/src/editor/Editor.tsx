import { Annotation, Compartment, EditorState } from '@codemirror/state'
import { EditorView, keymap, drawSelection } from '@codemirror/view'
import { defaultKeymap } from '@codemirror/commands'
import { useEffect, useRef } from 'react'
import type { RgaDocument } from '../crdt/document'
import type { UndoManager } from '../crdt/undo'
import type { Op } from '../crdt/types'
import type { CursorAnchor } from '../transport/cursorAnchor'
import { changesToOps, minimalChange } from './bridge'
import { formattingField, setFormatting } from './formatting'
import { remoteCursorsField, setRemoteCursors, type RemoteCursor } from './remoteCursors'
import type { Span } from '../crdt/marks'

const remoteChange = Annotation.define<boolean>()

const CURSOR_REPORT_INTERVAL_MS = 100

export interface EditorProps {
  document: RgaDocument
  undo: UndoManager
  remoteCursors: RemoteCursor[]
  formatting: Span[]
  onLocalOps: (ops: Op[]) => void
  onToggleMark: (type: string, from: number, to: number) => void
  onSelectionChange: (from: number, to: number, text: string) => void
  onCursorMove: (head: CursorAnchor | null, anchor: CursorAnchor | null) => void
  onUndo: () => void
  onRedo: () => void
  registerRemoteApply: (apply: () => void) => void
  readOnly?: boolean
}

export function Editor(props: EditorProps) {
  const host = useRef<HTMLDivElement>(null)
  const view = useRef<EditorView | null>(null)
  const readOnlyCompartment = useRef(new Compartment)
  const latest = useRef(props)
  latest.current = props

  useEffect(() => {
    if (!host.current) return

    let cursorTimer: number | null = null
    let pendingCursor: { head: CursorAnchor | null; anchor: CursorAnchor | null } | null = null

    const flushCursor = () => {
      cursorTimer = null
      if (pendingCursor === null) return
      const { head, anchor } = pendingCursor
      pendingCursor = null
      latest.current.onCursorMove(head, anchor)
    }

    const scheduleCursor = (head: CursorAnchor | null, anchor: CursorAnchor | null) => {
      pendingCursor = { head, anchor }
      if (cursorTimer === null) {
        cursorTimer = window.setTimeout(flushCursor, CURSOR_REPORT_INTERVAL_MS)
      }
    }

    const toggleMark = (type: string): boolean => {
      const selection = view.current?.state.selection.main
      if (selection === undefined || selection.empty) return true
      latest.current.onToggleMark(type, selection.from, selection.to)
      return true
    }

    const state = EditorState.create({
      doc: '',
      extensions: [
        keymap.of([
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
        readOnlyCompartment.current.of(
          EditorState.readOnly.of(latest.current.readOnly ?? false),
        ),
        EditorView.updateListener.of((update) => {
          const current = latest.current

          if (update.docChanged && !update.transactions.some((t) => t.annotation(remoteChange))) {
            const ops = changesToOps(current.document, update.changes, current.undo)

            if (import.meta.env.DEV) {
              const inEditor = update.state.doc.toString()
              const inCrdt = current.document.text
              if (inEditor !== inCrdt) {
                console.error(
                  'MODEL DIVERGENCE after a local change:\n  CodeMirror: %o\n  CRDT:       %o',
                  inEditor,
                  inCrdt,
                )
              }
            }

            if (ops.length > 0) current.onLocalOps(ops)
          }

          if (update.selectionSet || update.docChanged) {
            reportCursor(update.state, current, scheduleCursor)

            const selection = update.state.selection.main
            current.onSelectionChange(
              selection.from,
              selection.to,
              selection.empty ? '' : update.state.sliceDoc(selection.from, selection.to),
            )
          }
        }),
      ],
    })

    const editor = new EditorView({ state, parent: host.current })
    view.current = editor

    latest.current.registerRemoteApply(() => {
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
  }, [])

  useEffect(() => {
    view.current?.dispatch({ effects: setRemoteCursors.of(props.remoteCursors) })
  }, [props.remoteCursors])

  useEffect(() => {
    view.current?.dispatch({ effects: setFormatting.of(props.formatting) })
  }, [props.formatting])

  useEffect(() => {
    view.current?.dispatch({
      effects: readOnlyCompartment.current.reconfigure(
        EditorState.readOnly.of(props.readOnly ?? false),
      ),
    })
  }, [props.readOnly])

  return <div className="editor-host" ref={host} />
}

function reportCursor(
  state: EditorState,
  props: EditorProps,
  emit: (head: CursorAnchor | null, anchor: CursorAnchor | null) => void,
): void {
  const selection = state.selection.main
  const documentLength = props.document.visibleLength

  const toAnchor = (position: number): CursorAnchor | null => {
    if (documentLength === 0 || position > documentLength || position < 0) return null
    return position === 0
      ? { charId : props.document.idAtVisible(0), side: 'before' }
: { charId: props.document.idAtVisible(position - 1), side: 'after' }
  }

  emit(toAnchor(selection.head), selection.empty ? null : toAnchor(selection.anchor))
}
