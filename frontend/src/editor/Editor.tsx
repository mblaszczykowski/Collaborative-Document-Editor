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

/**
 * The editor: CodeMirror as a **view layer**, with our own document model.
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
  /** Where the caret and the selection's other end are — a character and a side, see reportCursor. */
  onCursorMove: (head: CursorAnchor | null, anchor: CursorAnchor | null) => void
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
  /**
   * Lets `readOnly` be reconfigured after the editor exists.
   *
   * The facet used to be baked into `EditorState.create()` inside a `[]`-dependency effect, so it
   * was read exactly once, at mount. A role change reaches the badge live (`onAccessChanged`) but
   * never reached CodeMirror: a viewer promoted to editor stayed locked out until a reload, and —
   * the direction that actually loses work — an editor DEMOTED mid-session kept a writable editor,
   * typed into it, mutated their local CRDT, and diverged permanently from a server that was
   * rejecting every operation. That is verbatim the failure `useCollaborativeDocument` guards
   * against for marks and block gestures.
   */
  const readOnlyCompartment = useRef(new Compartment)
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
        // Through `latest`, like every other prop this effect reads: the value here is only
        // the STARTING one, and the compartment is reconfigured from its own effect below
        // whenever the role changes. Reading `props` directly would put it in this
        // effect's dependency array, and this effect must run exactly once.
        readOnlyCompartment.current.of(
          EditorState.readOnly.of(latest.current.readOnly ?? false),
        ),
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
    // The editor is created once per document; prop changes are handled by `latest`, and
    // `readOnly` by its own compartment above.
    //
    // There used to be an `eslint-disable-next-line react-hooks/exhaustive-deps` here. There is no
    // ESLint in this project — `npm run lint` is oxlint with two rules — so it suppressed nothing
    // and read as if a linter had been consulted about this dependency array. Nothing checks
    // dependency arrays, which is how the frozen `readOnly` facet stayed invisible.
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

/**
 * Reports the cursor position as **a character and a side**, not as an offset.
 *
 * ## What the previous version got wrong, and what it blamed
 *
 * It reported one identifier under an implicit rule — "the caret is after this character" —
 * produced as `idAtVisible(position - 1)`. Its comment said a cursor at the END of the document
 * has no character to sit on and is therefore reported as null. That is not what the code did and
 * not what happens: at the end, `position === documentLength`, so it reported the LAST character,
 * and the round trip is exact. The end of the document is the case that always worked.
 *
 * The case that did not is **position 0**. There is no character before the first one, so the
 * expression fell through to null, and `remoteCursors.buildDecorations` drops a null head — so a
 * collaborator who pressed Home, or clicked before the first character, had their caret and their
 * name flag disappear from everybody else's screen until they moved again. On a shared document
 * that reads as the person having left.
 *
 * ## The fix
 *
 * Report the FIRST character with the side `before`, mirroring how `Anchor` already distinguishes
 * before from after for marks. The side travels inside the existing presence fields — the wire
 * format is shared with the server and is not changed — and the packing is documented in
 * `transport/cursorAnchor.ts`.
 *
 * `null` now means only what it should: there is nothing in the document to anchor to. That is an
 * empty document, where there is exactly one place a caret can be, or a position this replica
 * cannot resolve because CodeMirror is momentarily ahead of the model.
 */
function reportCursor(
  state: EditorState,
  props: EditorProps,
  emit: (head: CursorAnchor | null, anchor: CursorAnchor | null) => void,
): void {
  const selection = state.selection.main
  const documentLength = props.document.visibleLength

  const toAnchor = (position: number): CursorAnchor | null => {
    if (documentLength === 0 || position > documentLength || position < 0) return null
    // Position 0 is the only one with no character in front of it, so it is the only one reported
    // from the other side. Every other position keeps the encoding every previous version of this
    // client used, which is what lets two clients of different vintages agree about a cursor.
    return position === 0
      ? { charId : props.document.idAtVisible(0), side: 'before' }
: { charId: props.document.idAtVisible(position - 1), side: 'after' }
  }

  // The offset-to-identifier mapping happens HERE, before the throttle, not at send time. Deferred
  // to the end of the window, the document could change in the meantime and the same offset would
  // point at a different character — that is, the cursor would land in somebody else's text. That
  // is exactly the bug the editor-layer decision guards against.
  emit(toAnchor(selection.head), selection.empty ? null : toAnchor(selection.anchor))
}
