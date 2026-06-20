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
 * The editor: CodeMirror as a **view layer**, with our own document model (ADR-0006).
 *
 * The flow is one-directional, and that is the only thing to understand here:
 *
 *   keystroke -> CM transaction -> CRDT operation -> merge -> CRDT text -> back into CM
 *
 * Never "change CM and separately the CRDT". If both models were updated independently, their
 * divergence would be a matter of time and would manifest as text on screen inconsistent with the
 * state sent to others — a bug the user cannot report.
 *
 * CodeMirror's history (`history()`) is **disabled**. Undo has to operate on CRDT operations,
 * because undo on the text would also take back other people's changes.
 */

/** Marks a transaction originating from a remote merge — it must not produce operations. */
const remoteChange = Annotation.define<boolean>()

/** The shortest interval between cursor position reports — justified at scheduleCursor. */
const CURSOR_REPORT_INTERVAL_MS = 100

export interface EditorProps {
  document: RgaDocument
  undo: UndoManager
  remoteCursors: RemoteCursor[]
  /** The formatting runs computed by the CRDT layer (CP13). */
  formatting: Span[]
  onLocalOps: (ops: Op[]) => void
  /** Applying or removing formatting on the current selection. */
  onToggleMark: (type: string, from: number, to: number) => void
  /**
   * A selection change, in OFFSETS into the visible text.
   *
   * Separate from `onCursorMove`, which carries CRDT identifiers. The reason is concrete: an
   * identifier cannot express position ZERO — there is no character for it to sit on — so a
   * selection from the start of the document looked like no selection at all. The comment panel
   * needs a range, not anchors, so it gets offsets.
   */
  onSelectionChange: (from: number, to: number, text: string) => void
  onCursorMove: (head: Id | null, anchor: Id | null) => void
  /** Undo and redo — handled a layer up, because they produce operations to broadcast. */
  onUndo: () => void
  onRedo: () => void
  /** Registers the function the layer above calls after merging remote operations. */
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

    // Throttling of cursor reporting.
    //
    // Without it the position goes out on EVERY editor event: every keystroke, and ~30 times a
    // second with an arrow key held (the system's auto-repeat). The server relays that to every
    // participant, so traffic grows with the square of the number of people — for an event that
    // carries no content at all.
    //
    // 100 ms, because another user's cursor does not have to be smoother than 10 frames per second
    // to look alive; below that threshold we add traffic with no difference visible to the naked
    // eye. The TRAILING variant, not leading: the last position is always sent, so the cursor does
    // not stop wherever the end of the window happened to fall.
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
     * Toggles formatting on the selection.
     *
     * Returns `true` even when there is nothing to format — because the shortcut is to be HANDLED
     * regardless of whether it changed anything. Returning `false` would pass Ctrl+B on to the
     * browser, which has its own handling for it.
     */
    const toggleMark = (type: string): boolean => {
      const selection = view.current?.state.selection.main
      if (selection === undefined || selection.empty) return true
      latest.current.onToggleMark(type, selection.from, selection.to)
      return true
    }

    const state = EditorState.create({
      // The document starts empty and fills up from the operations the server sends.
      doc: '',
      extensions: [
        // history() deliberately OMITTED (see the comment above the component), but the shortcuts
        // MUST work — an editor where Ctrl+Z does nothing is broken. They are routed to the
        // UndoManager, which undoes ONLY the user's own operations.
        keymap.of([
          // The formatting shortcuts. Without them the toolbar is the only route, and a text
          // editor where Ctrl+B does nothing feels broken no matter how many buttons it shows.
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

            // The invariant: after every local change the text in CodeMirror and the text in the
            // CRDT have to be identical. This is an ASSERTION, not a hope — without it, the two
            // models diverging would only surface for another user, as "I see something else".
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

    // A remote merge: we move the MINIMAL change into CodeMirror, not the whole document.
    // Replacing everything would be correct, but it would drop the user's cursor and selection —
    // that is, break typing exactly when somebody else is editing the same paragraph.
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
    // The editor is created once per document; prop changes are handled by `latest`.
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
 * Reports the cursor position as **CRDT character identifiers**, not offsets (ADR-0006).
 *
 * A cursor at the end of the document has no character to sit on — we report null then, and the
 * other side draws it at the end. That is the only case where a cursor position is not anchored in
 * the text and can "drift" under remote editing.
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

  // The offset-to-identifier mapping happens HERE, before the throttle, not at send time. Deferred
  // to the end of the window, the document could change in the meantime and the same offset would
  // point at a different character — that is, the cursor would land in somebody else's text. That
  // is exactly the bug ADR-0006 guards against.
  emit(toId(selection.head), selection.empty ? null : toId(selection.anchor))
}
