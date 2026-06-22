import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { api, type DocumentState, type Session } from '../api'
import { RgaDocument } from '../crdt/document'
import { decodeSnapshot } from '../crdt/snapshot'
import type { Span } from '../crdt/marks'
import type { Id, Op } from '../crdt/types'
import { UndoManager } from '../crdt/undo'
import { offlineStore } from '../transport/offlineStore'
import {
  DocumentConnection,
  type ConnectionState,
  type PresenceEntry,
} from '../transport/connection'
import type { NetworkConditions } from '../transport/simulator'
import type { RemoteCursor } from './remoteCursors'
import { shiftAfter } from '../ui/format'

/**
 * Everything one open document does, minus how it looks.
 *
 * ## Why this was extracted
 *
 * `DocumentWorkspace` had grown to roughly 470 lines and about twenty `useState` calls, and it
 * was the one place in this repository where the code was visibly worse than the backend it
 * talks to — named as such in the README's own list of limitations and in PLAN-v3 §6.3.
 *
 * The problem was not the length. It was that three unrelated things shared one scope:
 *
 * 1. **The wiring** — opening the document over REST, building the replica, connecting the
 *    socket, tearing it down. This is where the ordering rules live, and every one of them is a
 *    decision with a reason (start from an EMPTY replica, connect only after the REST call
 *    succeeds, default to the weakest role).
 * 2. **The commands** — toggling a mark, anchoring a comment, undo and redo. Each has to check
 *    permission independently, because a keyboard shortcut bypasses a greyed-out button.
 * 3. **The presentation** — a menubar, three panels, a toolbar and a status bar.
 *
 * Only the third one changes when somebody redesigns the page, and only the first two matter
 * when somebody changes the protocol. Keeping them together meant every edit to either touched
 * a file where the other lived.
 *
 * ## What deliberately stayed behind
 *
 * The panel toggle, the share button and the copy-to-clipboard state. They are about what is on
 * screen, not about the document — moving them here would trade a large component for a hook
 * that knows about buttons, which is the same mistake in a different place.
 */

export interface CollaborativeDocument {
  /** The replica, or null until the document has opened. */
  model: RgaDocument | null
  undo: UndoManager | null

  title: string
  role: DocumentState['role']
  ready: boolean
  /** The document cannot be opened: deleted, missing, or not ours. The server says 404 to all three. */
  unavailable: boolean

  canWrite: boolean
  canComment: boolean

  connectionState: ConnectionState
  pendingCount: number
  presence: PresenceEntry[]
  /** Whether the participant list covers the whole cluster (CP26). */
  presenceComplete: boolean
  remoteCursors: RemoteCursor[]
  network: NetworkConditions

  characters: number
  formatting: Span[]
  selection: string
  /** Set after a merge was refused and the local version was saved elsewhere (ADR-0005). */
  recovery: { title: string; id: string } | null

  toggleMark: (type: string, from: number, to: number) => void
  /** Turns the current line into a heading, quotation or list item — or back (CP30). */
  toggleBlock: (type: string, value: string | null) => void
  anchorComment: (threadId: string) => void
  /**
   * Suggestion mode (CP23): propose a change instead of making it.
   *
   * Declared here because the hook already returns both. They were added to the implementation
   * and to the call sites while this interface was being extracted out of App.tsx, and the two
   * halves landed in one commit without meeting — the type error is exactly the seam.
   */
  proposeDeletion: () => Promise<void>
  proposeInsertion: (text: string) => Promise<void>
  onLocalOps: (ops: Op[]) => void
  onCursorMove: (head: Id | null, anchor: Id | null) => void
  onSelectionChange: (from: number, to: number, text: string) => void
  runUndo: () => void
  runRedo: () => void
  /** The editor hands back a function that re-renders it after remote operations. */
  registerRemoteApply: (apply: () => void) => void
  updateNetwork: (patch: Partial<NetworkConditions>) => void
}

export function useCollaborativeDocument(
  documentId: string,
  session: Session,
): CollaborativeDocument {
  const [title, setTitle] = useState('')

  /**
   * The role in this document, sent with the initial state (CP12).
   *
   * Defaults to the **weakest possible**, not the strongest: if the server did not answer, or
   * answered in an old format, the user should see a read-only document. The opposite default
   * would allow typing in the window between opening and the answer — precisely the moment when
   * it is not yet known whether that is allowed.
   */
  const [role, setRole] = useState<DocumentState['role']>('viewer')
  const [ready, setReady] = useState(false)
  const [unavailable, setUnavailable] = useState(false)

  const [connectionState, setConnectionState] = useState<ConnectionState>('connecting')
  const [pendingCount, setPendingCount] = useState(0)
  const [presence, setPresence] = useState<PresenceEntry[]>([])
  /** Whether the participant list covers the whole cluster (CP26). */
  const [presenceComplete, setPresenceComplete] = useState(true)
  const [characters, setCharacters] = useState(0)
  const [formatting, setFormatting] = useState<Span[]>([])
  const [recovery, setRecovery] = useState<{ title: string; id: string } | null>(null)
  const [network, setNetwork] = useState<NetworkConditions>({
    latencyMs: 0,
    jitterMs: 0,
    packetLoss: 0,
    partitioned: false,
  })

  /**
   * The current selection and its range.
   *
   * Held in state because the comments panel needs it AFTER a click into its own text field —
   * by which time the editor's selection no longer exists. It is captured while it still does.
   */
  const [selection, setSelection] = useState('')
  const selectionRange = useRef<{ from: number; to: number } | null>(null)
  const caret = useRef(0)

  const document = useRef<RgaDocument | null>(null)
  const undo = useRef<UndoManager | null>(null)
  const connection = useRef<DocumentConnection | null>(null)
  const applyRemote = useRef<() => void>(() => {})

  // The same thresholds as on the server (Role.canWriteText / Role.canComment). Duplicating the
  // rule here is DELIBERATE and harmless: the server checks independently and it decides. The
  // client uses it only so as not to offer a gesture that will be rejected.
  const canWrite = role === 'owner' || role === 'editor'
  const canComment = canWrite || role === 'commenter'

  const refreshStats = useCallback(() => {
    const model = document.current
    if (model === null) return
    setCharacters(model.visibleLength)
    // Formatting is recomputed on every content change, because the spans are expressed in
    // positions of the visible text — and those shift on every insertion.
    setFormatting(model.formatting())
  }, [])

  /**
   * The reaction to a refused merge (ADR-0005).
   *
   * The server refused because garbage collection removed the anchor point for these operations.
   * Where that text belongs is irretrievably lost, and there is no way to insert it back
   * honestly. Rather than faking success (appending at the end = silent corruption) or staying
   * quiet (= silent loss of work), the local version is saved as a separate document.
   */
  const recoverAfterRejection = useCallback(
    async (model: RgaDocument, link: DocumentConnection) => {
      const rescued = await api.createRecovered(
        `Recovered draft — ${new Date().toLocaleString('en-GB')}`,
        model.text,
      )
      await link.abandonBuffer()
      setRecovery({ title: rescued.title, id: rescued.id })
    },
    [],
  )

  useEffect(() => {
    let cancelled = false

    void (async () => {
      const state = await api.openDocument(documentId)
      if (cancelled) return
      setUnavailable(false)

      // The document starts EMPTY and is built from the operations the server sends. Seeding it
      // with the text from REST would create new local operations the server does not know
      // about — and then synchronisation would deliver operations describing the same text.
      const model = new RgaDocument(0)

      /**
       * The snapshot, in place of replaying the whole log (CP24).
       *
       * Until CP24 opening a document meant asking for every operation from zero — a hundred
       * thousand characters typed one at a time is a hundred thousand operations across the
       * socket, each integrated by the merge algorithm, to reach a state the server already
       * held as one object.
       *
       * Every failure here falls back to the old path rather than surfacing: an unreadable or
       * unknown-version snapshot is a cache miss, and rebuilding from the log is slower and
       * always correct (ADR-0003). That is why this is wrapped separately from the open call
       * above — a snapshot that does not arrive must not read as a document that cannot be
       * opened.
       */
      let from = 0
      try {
        const snapshot = await api.snapshot(documentId)
        if (cancelled) return
        if (snapshot !== null) {
          const decoded = await decodeSnapshot(snapshot.bytes)
          if (decoded !== null) {
            model.restoreFrom(decoded)
            from = snapshot.throughServerSeq
          }
        }
      } catch {
        // Falls through to `from = 0`, i.e. the pre-CP24 behaviour.
      }
      if (cancelled) return

      document.current = model
      const undoManager = new UndoManager(model)
      undo.current = undoManager

      /**
       * The undo stacks survive a reload (CP25).
       *
       * They used to live in the tab's memory, so reloading the page cost the ability to undo
       * work that was still on screen. Loading is best-effort — a browser without IndexedDB, or
       * a stack from an older format, means no history rather than no editor.
       */
      void offlineStore
        .loadUndo<Parameters<UndoManager['restore']>[0]>(documentId)
        .then((saved) => {
          if (!cancelled && saved !== null) undoManager.restore(saved)
        })
        .catch(() => {})
      // Debounced, because `observe` fires on every recorded change — that is, on every
      // keystroke, since coalescing mutates the top entry rather than skipping the callback.
      // Undoing the last second of typing after a reload is a convenience; paying a second
      // IndexedDB write per keystroke for it, on the same thread as the offline buffer's write,
      // is not.
      let saveTimer: number | undefined
      undoManager.observe(() => {
        window.clearTimeout(saveTimer)
        saveTimer = window.setTimeout(() => {
          void offlineStore.saveUndo(documentId, undoManager.serialise()).catch(() => {})
        }, 1_000)
      })
      setTitle(state.title)
      setRole(state.role)
      setReady(true)
      refreshStats()

      const link = new DocumentConnection(documentId, session.token, from, {
        onSession: (replicaId) => model.adoptReplicaId(replicaId),
        versionVector: () => model.version,
        hasOperation: (replicaId, lamport) => model.contains({ replicaId, lamport }),
        onRemoteOps: (ops) => {
          model.applyAll(ops)
          applyRemote.current()
          refreshStats()
        },
        onPresence: (entries, complete) => {
          setPresence(entries)
          // False only when the server could not reach the cluster channel (CP26). The
          // interface says so rather than showing a short list as if it were the whole one.
          setPresenceComplete(complete)
        },
        onStateChange: (nextState, pending) => {
          setConnectionState(nextState)
          setPendingCount(pending)
        },
        onError: (code, message) => {
          if (code === 'RESYNC_REQUIRED') {
            void recoverAfterRejection(model, link)
            return
          }
          console.error(`[${code}] ${message}`)
        },
        /**
         * Access changed (CP21) — we ask the server for OUR role rather than believing the
         * message.
         *
         * We ask for the role alone rather than through `openDocument`: that one also carries
         * the whole text.
         */
        onAccessChanged: () => {
          void api.myRole(documentId)
            .then((mine) => setRole(mine.role))
            // A 404 means "you no longer have access" — the same answer as for a document one
            // may not read, and the same screen as opening one without access.
            .catch(() => setUnavailable(true))
        },
      })
      link.simulator.onChange(setNetwork)
      void link.restoreOfflineBuffer().then(() => link.connect())
      connection.current = link
    })().catch(() => {
      // We do not open the WebSocket: the interceptor would reject the subscription for the same
      // reason, only silently — and the client would retry in a loop.
      if (!cancelled) setUnavailable(true)
    })

    return () => {
      cancelled = true
      connection.current?.disconnect()
      connection.current = null
    }
  }, [documentId, session.token, session.userId, refreshStats, recoverAfterRejection])

  const onLocalOps = useCallback(
    (ops: Op[]) => {
      for (const op of ops) connection.current?.send(op)
      refreshStats()
    },
    [refreshStats],
  )

  const onCursorMove = useCallback((head: Id | null, anchor: Id | null) => {
    connection.current?.sendPresence(head, anchor, false)
  }, [])

  /**
   * Remembers the selection while it still exists.
   *
   * Clicking into the comment field takes focus away from the editor, and by then it is too late
   * to read it. Offsets rather than identifiers: an identifier cannot express position zero, so
   * a selection starting at the beginning of the document would look like no selection at all.
   */
  const onSelectionChange = useCallback((from: number, to: number, text: string) => {
    selectionRange.current = from < to ? { from, to } : null
    // The caret is remembered even when nothing is selected, because a block gesture (CP30) acts
    // on the LINE the cursor is in rather than on a selection — pressing "heading" with no
    // selection is the ordinary way to use it.
    caret.current = from
    setSelection(text)
  }, [])

  /**
   * Toggles formatting over the selection.
   *
   * "Toggles", not "applies": if the WHOLE selected range already carries the mark, the gesture
   * removes it. Without that, Ctrl+B on bold text would apply bold a second time and do nothing
   * visible — and the user has no way to guess that undoing their own change needs a different
   * gesture.
   */
  const toggleMark = useCallback((type: string, from: number, to: number) => {
    const model = document.current
    const link = connection.current
    if (model === null || link === null || from >= to) return
    // Greying out the buttons is not enough: a KEYBOARD SHORTCUT bypasses the toolbar, and
    // `EditorState.readOnly` blocks only document changes — keymap commands still run. Without
    // this check, Ctrl+B in a reader's browser created a mark locally, the server dropped it
    // silently, and the replica diverged permanently.
    if (!canWrite) return

    const alreadyMarked = coveredBy(model.formatting(), type, from, to)
    // What the range carried BEFORE, so undo can put that back rather than perform the opposite
    // gesture (CP25). `undefined` means the type was absent; `null` means present with no value,
    // which is what bold and italic look like. Conflating the two is how a restore silently keeps
    // formatting it was asked to remove — the bug CP22 found in the history layer.
    const before = alreadyMarked
      ? (model.formatting().find((span) => span.from <= from && span.marks.has(type))
          ?.marks.get(type) ?? null)
      : undefined
    const value = type === 'link' ? window.prompt('Adres linku') : null
    const op = alreadyMarked
      ? model.removeMark(from, to, type)
      : model.addMark(from, to, type, value)

    const anchorFrom = model.idAtVisible(from)
    const anchorTo = model.idAtVisible(to - 1)
    if (anchorFrom !== null && anchorTo !== null) {
      undo.current?.recordMark(type, anchorFrom, anchorTo, before,
        alreadyMarked ? undefined : value)
    }

    link.send(op)
    // Recomputed immediately rather than waiting for the echo from the server: the operation is
    // already applied locally, so waiting would mean a visible delay between pressing Ctrl+B and
    // the appearance changing — exactly the cost optimistic editing exists to avoid.
    setFormatting(model.formatting())
  }, [canWrite])

  /**
   * Turns the current LINE into a heading, a quotation or a list item — or back (CP30).
   *
   * Block structure is marks, because the document is a flat character sequence and a paragraph
   * is not an object in it: it is the text between two newlines. The mark covers exactly that
   * range, so the character layer needs nothing new — the third time that has been true, after
   * formatting and comments.
   *
   * Two limits worth stating rather than hiding:
   *
   * - **an empty line cannot be marked.** There is nothing to anchor to, and inventing an anchor
   *   would mean a mark pointing at a character that does not exist. Pressing the button on an
   *   empty line does nothing, which is at least honest;
   * - **there is no move.** Moving a paragraph here is a deletion plus an insertion, and two
   *   people moving the same one produce two copies — pinned by a test rather than described
   *   (ADR-0016).
   */
  const toggleBlock = useCallback((type: string, value: string | null) => {
    const model = document.current
    const link = connection.current
    if (model === null || link === null || !canWrite) return

    const text = model.text
    const at = Math.min(selectionRange.current?.from ?? caret.current, text.length)
    const start = text.lastIndexOf('\n', Math.max(0, at - 1)) + 1
    const newline = text.indexOf('\n', at)
    const end = newline === -1 ? text.length : newline
    if (start >= end) return

    const already = coveredBy(model.formatting(), type, start, end)
    const op = already
      ? model.removeMark(start, end, type)
      : model.addMark(start, end, type, value)
    link.send(op)
    setFormatting(model.formatting())
  }, [canWrite])

  /**
   * Places a comment anchor — a CRDT mark of type `comment` carrying the thread identifier.
   *
   * It is created AFTER the thread, because it needs its identifier, and it travels a different
   * road: the thread over REST, the anchor over WebSocket. That is deliberate — the anchor has
   * to merge like any other CRDT operation, and the conversation has nothing to merge
   * (ADR-0010, CP14).
   */
  const anchorComment = useCallback((threadId: string) => {
    const model = document.current
    const link = connection.current
    const range = selectionRange.current
    if (model === null || link === null || range === null) return
    // As above — the panel does not show a reader the comment field, but the entry to this
    // function is closed regardless of what the interface happens to be drawing.
    if (!canComment) return
    link.send(model.addMark(range.from, range.to, 'comment', threadId))
    setFormatting(model.formatting())
  }, [canComment])

  /**
   * Proposes deleting the selection (CP23).
   *
   * Nothing is sent over the operation channel: a proposal is a REST call, and the SERVER
   * replica generates the mark. That is not a detour — it is what lets a `commenter` make one at
   * all, since a commenter may not send operations that change the text, and a proposal has to
   * be possible for exactly the role that cannot make the change itself.
   *
   * The range travels as two character identifiers, like a cursor: offsets sent to a server are
   * stale as soon as somebody types before them, and the failure that produces is a proposal
   * attached to words nobody proposed anything about.
   */
  const proposeDeletion = useCallback(async () => {
    const model = document.current
    const range = selectionRange.current
    if (model === null || range === null) return
    const from = model.idAtVisible(range.from)
    const to = model.idAtVisible(range.to - 1)
    if (from === null || to === null) return
    await api.proposeDeletion(documentId, from, to)
  }, [documentId])

  /** Proposes inserting text after the selection, or at the start when nothing is selected. */
  const proposeInsertion = useCallback(async (text: string) => {
    const model = document.current
    if (model === null || text === '') return
    const range = selectionRange.current
    const after = range === null || range.to === 0 ? null : model.idAtVisible(range.to - 1)
    await api.proposeInsertion(documentId, after, text)
  }, [documentId])

  const runUndo = useCallback(() => {
    const ops = undo.current?.undo() ?? []
    if (ops.length > 0) {
      applyRemote.current()
      onLocalOps(ops)
    }
  }, [onLocalOps])

  const runRedo = useCallback(() => {
    const ops = undo.current?.redo() ?? []
    if (ops.length > 0) {
      applyRemote.current()
      onLocalOps(ops)
    }
  }, [onLocalOps])

  const registerRemoteApply = useCallback((apply: () => void) => {
    applyRemote.current = apply
  }, [])

  const updateNetwork = useCallback((patch: Partial<NetworkConditions>) => {
    connection.current?.simulator.update(patch)
  }, [])

  // Cursor positions arrive as CRDT character identifiers and are turned into offsets only here,
  // at the last moment before drawing (ADR-0006).
  const remoteCursors: RemoteCursor[] = useMemo(() => {
    const model = document.current
    if (!model) return []
    const clamp = (index: number | null) => (index === null ? null : Math.min(index, characters))

    return presence
      .filter((entry) => entry.userId !== session.userId)
      .map((entry) => ({
        replicaId: entry.replicaId,
        displayName: entry.displayName,
        color: entry.color,
        head:
          entry.cursorReplicaId !== null && entry.cursorLamport !== null
            ? clamp(shiftAfter(model.visibleIndexOf({
                replicaId: entry.cursorReplicaId,
                lamport: entry.cursorLamport,
              })))
            : null,
        anchor:
          entry.anchorReplicaId !== null && entry.anchorLamport !== null
            ? clamp(shiftAfter(model.visibleIndexOf({
                replicaId: entry.anchorReplicaId,
                lamport: entry.anchorLamport,
              })))
            : null,
        typing: entry.typing,
      }))
  }, [presence, session.userId, characters])

  return {
    model: document.current,
    undo: undo.current,
    title,
    role,
    ready,
    unavailable,
    canWrite,
    canComment,
    connectionState,
    pendingCount,
    presence,
    presenceComplete,
    remoteCursors,
    network,
    characters,
    formatting,
    selection,
    recovery,
    toggleMark,
    toggleBlock,
    anchorComment,
    proposeDeletion,
    proposeInsertion,
    onLocalOps,
    onCursorMove,
    onSelectionChange,
    runUndo,
    runRedo,
    registerRemoteApply,
    updateNetwork,
  }
}

/**
 * Whether the WHOLE range [from, to) already carries a mark of the given type.
 *
 * The condition is deliberately strong: for a selection covering both bold and non-bold text the
 * gesture should ADD bold to all of it, rather than remove it from part. That is how the editors
 * people are used to behave, and it is easier to undo than the opposite.
 */
export function coveredBy(spans: Span[], type: string, from: number, to: number): boolean {
  let covered = from
  for (const span of spans) {
    if (!span.marks.has(type)) continue
    if (span.from > covered) break
    covered = Math.max(covered, span.to)
    if (covered >= to) return true
  }
  return covered >= to
}
