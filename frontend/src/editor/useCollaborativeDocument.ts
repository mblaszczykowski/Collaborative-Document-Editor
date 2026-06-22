import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { ApiError, api, type DocumentState, type Session } from '../api'
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
import { decodeCursorAnchor, type CursorAnchor } from '../transport/cursorAnchor'
import type { NetworkConditions } from '../transport/simulator'
import type { RemoteCursor } from './remoteCursors'
import { coveredBy, sameSpans } from './spans'

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

/**
 * What happened to a comment anchor placed after the fact (CP27).
 *
 * Three outcomes rather than a boolean, because two of them used to be the same value and only
 * one of them is about the document: `gone` means the commented characters were deleted while the
 * comment waited in the outbox, `not-ready` means this replica cannot answer yet.
 */
export type AnchorOutcome = 'anchored' | 'gone' | 'not-ready'

export interface CollaborativeDocument {
  /** The replica, or null until the document has opened. */
  model: RgaDocument | null
  undo: UndoManager | null

  title: string
  role: DocumentState['role']
  ready: boolean
  /**
   * The server has assigned this replica its identifier.
   *
   * **Nothing may be typed before this is true**, and the reason is silent data loss rather than
   * tidiness. See the `onSession` handler below and `RgaDocument.adoptReplicaId`.
   */
  replicaAssigned: boolean
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
  /** Set after a merge was refused and the local version was saved elsewhere. */
  recovery: { title: string; id: string } | null
  notice: string | null
  dismissNotice: () => void

  toggleMark: (type: string, from?: number, to?: number) => void
  /** Turns the current line into a heading, quotation or list item — or back (CP30). */
  toggleBlock: (type: string, value: string | null) => void
  anchorComment: (threadId: string) => void
  /**
   * The current selection as two character identifiers, or null when nothing is selected.
   *
   * For a comment written with no connection (CP27): the outbox has to remember WHERE the comment
   * was about, and it has to do it in identifiers rather than offsets, because by the time the
   * queue drains somebody may have typed before the selection.
   */
  captureAnchor: () => { from: Id; to: Id } | null
  /**
   * Places a comment anchor at a range captured earlier — the offline half of `anchorComment`.
   *
   * Returns whether the mark landed. `false` means the characters the comment was about are gone
   * from the document, which the panel has to distinguish from a comment that detached later.
   */
  anchorCommentAt: (threadId: string, range: { from: Id; to: Id }) => AnchorOutcome
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
  onCursorMove: (head: CursorAnchor | null, anchor: CursorAnchor | null) => void
  onSelectionChange: (from: number, to: number, text: string) => void
  runUndo: () => void
  runRedo: () => void
  /** The editor hands back a function that re-renders it after remote operations. */
  registerRemoteApply: (apply: () => void) => void
  updateNetwork: (patch: Partial<NetworkConditions>) => void
}

/**
 * The error codes worth interrupting somebody for, and what to say.
 *
 * Deliberately a short list rather than "show whatever the server sent". Two of the codes that
 * arrive here carry a message written for a log — `OPERATION_REJECTED` passes an exception's own
 * words through — and putting those on screen tells the user nothing while looking alarming. The
 * rest describe transport trouble the connection indicator already shows.
 *
 * The two that ARE here change what the user should do next: one says their safety net is gone,
 * the other says an edit did not survive. Both were previously visible only in the console, which
 * is to say to nobody.
 */
const USER_FACING_ERRORS: Record<string, string> = {
  OFFLINE_BUFFER_UNAVAILABLE:
    'This browser is not letting the editor keep a local backup, so unsent changes would be lost '
    + 'if the tab closed. Editing still works — avoid closing the tab while offline.',
  OPERATION_REJECTED:
    'The server refused one of your changes, so it is not in the document. Reload the page to be '
    + 'sure of what was saved.',
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
  /**
   * Bumped to re-open the document from scratch — see the `SNAPSHOT_REQUIRED` branch in
   * `onError`. It is a dependency of the effect that opens the document, so changing it runs that
   * effect's cleanup (which disconnects) and then opens again, fetching a fresh snapshot.
   */
  const [reopenToken, setReopenToken] = useState(0)

  const [role, setRole] = useState<DocumentState['role']>('viewer')
  const [ready, setReady] = useState(false)
  /**
   * Whether the server has told us which replica we are.
   *
   * ## The failure this closes, in full, because it is silent data loss
   *
   * The replica is built with `new RgaDocument(0)` — deliberately, because 0 is a placeholder and
   * the real identifier is handed out by a single counter in Redis (`ReplicaIdAllocator`), so that
   * two clients can never mint the same character identity. It arrives late: IndexedDB restore,
   * then the WebSocket handshake, then STOMP CONNECT, then `/app/doc/{id}/join`, and finally a
   * `SessionInfo` frame on `/user/queue/session`.
   *
   * `ready` used to be the only gate on the editor, and `ready` is set BEFORE the connection
   * object is even constructed. So the editor was writable for the whole of that window. A
   * keystroke in it makes `locallyGenerated > 0`, and `adoptReplicaId` then throws by design —
   * changing the identifier after generating operations would leave the existing characters
   * pointing at the old one. `@stomp/stompjs` swallows exceptions thrown out of a frame handler,
   * so nothing surfaced, and **the replica kept identifier 0 for the entire session**.
   *
   * Two users who both typed in that window on the same document then minted identical
   * `(0, lamport)` pairs. The merge algorithm treats identical identifiers as the same character,
   * so one author's text disappears — permanently, silently, on both screens. That is verbatim the
   * failure `adoptReplicaId`'s own javadoc says its guard exists to prevent; the guard was correct
   * and the initialisation order was not.
   *
   * The editor is therefore read-only until this is true. It is a fraction of a second on a live
   * connection, and the honest thing to show on a dead one.
   */
  const [replicaAssigned, setReplicaAssigned] = useState(false)
  const [unavailable, setUnavailable] = useState(false)

  const [connectionState, setConnectionState] = useState<ConnectionState>('connecting')
  const [pendingCount, setPendingCount] = useState(0)
  const [presence, setPresence] = useState<PresenceEntry[]>([])
  /** Whether the participant list covers the whole cluster (CP26). */
  const [presenceComplete, setPresenceComplete] = useState(true)
  const [characters, setCharacters] = useState(0)
  const [formatting, setFormatting] = useState<Span[]>([])
  const [recovery, setRecovery] = useState<{ title: string; id: string } | null>(null)
  /** The last thing worth telling the user, until they dismiss it — see USER_FACING_ERRORS. */
  const [notice, setNotice] = useState<string | null>(null)
  const dismissNotice = useCallback(() => setNotice(null), [])
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

  /**
   * Publishes a new set of formatting runs, but only when they actually differ.
   *
   * `RgaDocument.formatting` builds a FRESH array every time — including a fresh `[]` for a
   * document with no formatting at all — and `refreshStats` runs on every keystroke. Putting that
   * straight into state changed the state's identity on every character, and `Editor` keys an
   * effect on `props.formatting` identity, so every keystroke dispatched a CodeMirror transaction
   * that rebuilt all decorations over the whole document. One transaction per character, whatever
   * the document size, most often to rebuild nothing.
   *
   * The comparison is in `spans.ts` and is bounded by the number of formatting RUNS, which is
   * negligible next to the number of characters.
   */
  const syncFormatting = useCallback((next: Span[]) => {
    setFormatting((previous) => (sameSpans(previous, next) ? previous : next))
  }, [])

  const refreshStats = useCallback(() => {
    const model = document.current
    if (model === null) return
    setCharacters(model.visibleLength)
    // Formatting is recomputed on every content change, because the spans are expressed in
    // positions of the visible text — and those shift on every insertion.
    syncFormatting(model.formatting())
  }, [syncFormatting])

  /**
   * The reaction to a refused merge.
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
      // Reset alongside `unavailable`, not left over from the previous document. The workspace is
      // keyed by document id today, so the hook remounts and this is belt and braces — but the
      // effect also re-runs on a token change, and an editor that was writable a moment ago must
      // not stay writable across a replica it has not been given an identifier for.
      setReplicaAssigned(false)

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
       * always correct. That is why this is wrapped separately from the open call
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
        /**
         * The server has told us which replica we are.
         *
         * ## Why this is not simply `model.adoptReplicaId(replicaId)`
         *
         * It was, and it threw on **every reconnect after any typing**. The server allocates a new
         * replica identifier at every STOMP CONNECT, and `connection.ts` republishes `/join` on
         * every (re)connect, so a `SessionInfo` frame arrives with a DIFFERENT identifier after
         * each network blip. By then this replica has generated operations, and `adoptReplicaId`
         * refuses — correctly, because the characters already in the document carry the old
         * identifier and changing it now would diverge the structure.
         *
         * The outcome of the throw was safe: `@stomp/stompjs` swallows it and the client keeps its
         * existing, still-unique identifier, which is exactly the right behaviour. The damage was
         * to the SIGNAL. An assertion that fires during ordinary operation is an assertion nobody
         * reads, and this one is the only thing standing between us and the initialisation-order
         * bug described at `replicaAssigned` — the one where a replica silently keeps identifier 0
         * and two users mint the same character identities. A red light that is always on cannot
         * report a fire.
         *
         * So the decision of whether to adopt is made HERE, where the two cases are
         * distinguishable, and `adoptReplicaId` goes back to being an assertion that should never
         * fire. The condition is the model's own `hasGeneratedOperations`: while nothing this
         * replica minted is in the document, adopting is safe regardless of what the clock says —
         * which is the exact condition `adoptReplicaId` asserts on, asked rather than triggered.
         *
         * This began as `model.replicaId === 0`, leaning on 0 being the constructor placeholder
         * and the server allocating from 1 upwards. Sound, and it encoded that invariant as a
         * magic number in a file that cannot see it; the accessor states it where it is true.
         */
        onSession: (replicaId) => {
          if (!model.hasGeneratedOperations) {
            model.adoptReplicaId(replicaId)
          } else if (model.replicaId !== replicaId) {
            // Not an error: this is what a reconnect looks like. Logged as a warning because the
            // identifier the server now believes we have differs from the one our characters
            // carry, and anybody debugging presence or garbage collection needs to know that.
            console.warn(
              `the server assigned replica ${replicaId} on reconnect; keeping ${model.replicaId}, `
              + 'which is the identifier the characters already in this document carry',
            )
          }
          setReplicaAssigned(true)
        },
        versionVector: () => model.version,
        hasOperation: (replicaId, lamport) => model.contains({ replicaId, lamport }),
        hasApplied: (op) => model.holds(op),
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
          if (code === 'SNAPSHOT_REQUIRED') {
            // The client asked for more of the operation log than the server is willing to send
            // in one answer, so there is no `SyncResponse` coming. That matters twice over.
            //
            // It is the ONLY reply path that answers a sync request with nothing, and
            // `resynchronize` no longer marks the connection online by itself — the arrival of
            // the response does. So without this branch the connection would sit in `resyncing`
            // for the life of the socket, telling the user their work is not saved while it is,
            // and reconnecting would not clear it because the next request is refused the same
            // way.
            //
            // And the server is asking for the right thing rather than complaining: re-opening
            // fetches the state as a snapshot, which is O(document) instead of O(log) and is
            // exactly what CP24 built the endpoint for. Re-opening also cannot loop — the new
            // cursor comes from the snapshot's own sequence number, so the tail is short.
            console.error(`[${code}] ${message}`)
            setReopenToken((token) => token + 1)
            return
          }
          // The console keeps the detail either way — the code and the server's own words are
          // what a developer needs. What the USER gets is the entry in USER_FACING_ERRORS, and
          // only for the codes that mean something to them: a lost durability guarantee and a
          // refused edit both change what they should do next, whereas a transport hiccup is
          // already visible in the connection indicator.
          console.error(`[${code}] ${message}`)
          const explanation = USER_FACING_ERRORS[code]
          if (explanation !== undefined) {
            setNotice(explanation)
          }
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
            .catch((error: unknown) => {
              // A 404 — and ONLY a 404 — means "you no longer have access": the server answers it
              // identically for a document that was deleted, one that never existed and one we may
              // not read, so it is the same screen as opening a document without access.
              //
              // This used to catch everything. `api.ts` threw the same bare `Error` for a 404, a
              // 500 and a dropped connection, so an `ACCESS_CHANGED` broadcast that happened to
              // arrive during a network hiccup replaced the whole document with "It may have been
              // deleted, or you may not have access to it" — and nothing clears that banner:
              // `setUnavailable(false)` runs only on a fresh open, and `App.tsx` keys the
              // workspace by document id, so only a reload recovers. A transient failure produced
              // a permanent, wrong, alarming message. The comment on these lines already asserted
              // "a 404 means…" over code that could not tell.
              //
              // Anything else is left alone deliberately: the role we already have stays, the
              // connection indicator already reports transport trouble, and the next
              // `ACCESS_CHANGED` — or the next reload — will ask again. Being briefly wrong about
              // a role the SERVER enforces anyway is much cheaper than being permanently wrong
              // about whether the document exists.
              if (error instanceof ApiError && error.status === 404) {
                setUnavailable(true)
                return
              }
              console.error('could not refresh the role after an access change', error)
            })
        },
      })
      link.simulator.onChange(setNetwork)
      connection.current = link
      // `.catch` before `.finally`, and the socket opens either way. The durable buffer is a
      // SAFEGUARD; the socket is the product. Without the catch, a browser that refuses IndexedDB
      // — a private window, blocked storage — rejected here, `connect` was never called, and the
      // editor rendered ready while nothing at all reached the server: the user typed into a local
      // replica for as long as the tab stayed open. The connection itself reports the lost
      // guarantee, once, through `onError`.
      void link
        .restoreOfflineBuffer()
        .catch(() => [])
        .then(() => link.connect())
        // `then`, not `finally`, and with a terminal catch. `finally` passes the settlement
        // through, so anything thrown while opening the socket became an unhandled rejection: no
        // connection, no message, and an editor that looks ready. The IIFE's own catch below does
        // not cover this chain — it belongs to the promise, not to the surrounding function.
          .catch(() => {
          if (!cancelled) setUnavailable(true)
        })
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
  }, [documentId, session.token, session.userId, refreshStats, recoverAfterRejection,
    reopenToken])

  const onLocalOps = useCallback(
    (ops: Op[]) => {
      for (const op of ops) connection.current?.send(op)
      refreshStats()
    },
    [refreshStats],
  )

  const onCursorMove = useCallback((head: CursorAnchor | null, anchor: CursorAnchor | null) => {
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
  const toggleMark = useCallback((type: string, explicitFrom?: number, explicitTo?: number) => {
    const model = document.current
    const link = connection.current
    // The range defaults to the tracked selection, which CodeMirror reported in DOCUMENT
    // coordinates. The toolbar used to measure it itself, from the DOM: it summed the length of
    // `Range.toString` over `.cm-content`. CodeMirror renders one <div class="cm-line"> per
    // line and `Range.toString` concatenates only text-node data, so no newline was ever
    // counted — every offset was short by the number of lines above the selection, and on a long
    // document the viewport only renders lines near the caret, so it was worse still. Formatting
    // landed on the wrong characters, which reads as the author's decision rather than a bug.
    //
    // The keyboard path was always correct and used `view.state.selection.main`. Two
    // implementations of one gesture, one silently wrong; now there is one.
    const from = explicitFrom ?? selectionRange.current?.from
    const to = explicitTo ?? selectionRange.current?.to
    if (model === null || link === null || from === undefined || to === undefined || from >= to) {
      return
    }
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
    // The span CONTAINING the range, not merely one starting at or before it. `spans` are
    // disjoint and ascending with unmarked gaps skipped, so `span.from <= from` alone matched the
    // FIRST span carrying the type anywhere earlier in the document. Invisible for bold and
    // italic, whose value is always null; for a link it meant undo restored a different URL.
    const before = alreadyMarked
      ? (model.formatting().find(
          (span) => span.from <= from && span.to > from && span.marks.has(type),
      )?.marks.get(type) ?? null)
      : undefined

    let value: string | null = null
    if (type === 'link' && !alreadyMarked) {
      value = window.prompt('Link address')
      // Cancel returns null, and it is not the same answer as an empty one: pressing Escape used
      // to create a valueless link mark anyway — broadcast to everybody, and removable only by
      // finding the same selection again.
      if (value === null) return
    }
    const op = alreadyMarked
      ? model.removeMark(from, to, type)
      : model.addMark(from, to, type, value)

    // No null check on these two, and that is a correction rather than an omission.
    //
    // `idAtVisible` returns an `Id` and THROWS on a position it cannot resolve; it never returns
    // null. The `if (anchorFrom !== null && anchorTo !== null)` that used to stand here was
    // therefore always true — a guard that made the API look nullable to every future reader while
    // guarding nothing. What actually protects this call is the line above: `from < to` was
    // checked, and `model.addMark`/`removeMark` has just succeeded over exactly this range, so
    // both positions are in the document. `toggleBlock` already says the same thing about its own
    // anchors, and says it correctly.
    undo.current?.recordMark(type, model.idAtVisible(from), model.idAtVisible(to - 1), before,
      alreadyMarked ? undefined : value)

    link.send(op)
    // Recomputed immediately rather than waiting for the echo from the server: the operation is
    // already applied locally, so waiting would mean a visible delay between pressing Ctrl+B and
    // the appearance changing — exactly the cost optimistic editing exists to avoid.
    syncFormatting(model.formatting())
  }, [canWrite, syncFormatting])

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
   *.
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
    // What the line carried before, so Ctrl+Z restores that state rather than performing the
    // opposite gesture — the same rule as `toggleMark`, and `undefined` versus `null` carries the
    // same distinction (absent, versus present with no value).
    const before = already
      ? (model.formatting().find(
          (span) => span.from <= start && span.to > start && span.marks.has(type),
      )?.marks.get(type) ?? null)
: undefined

    const op = already
      ? model.removeMark(start, end, type)
      : model.addMark(start, end, type, value)

    // Recorded, and until this line it was not. `toggleBlock` was the one mark-producing gesture
    // that never reached the undo stack, so Ctrl+Z after making a line a heading popped whatever
    // was underneath — usually the paragraph the user had just typed, which it then deleted while
    // leaving the heading in place. That is verbatim the failure CP25 fixed for Ctrl+B; block
    // structure arrived later and did not inherit it. `start < end` is guaranteed above, so both
    // anchors resolve.
    undo.current?.recordMark(type, model.idAtVisible(start), model.idAtVisible(end - 1),
      before, already ? undefined : value)

    link.send(op)
    syncFormatting(model.formatting())
  }, [canWrite, syncFormatting])

  /**
   * Places a comment anchor — a CRDT mark of type `comment` carrying the thread identifier.
   *
   * It is created AFTER the thread, because it needs its identifier, and it travels a different
   * road: the thread over REST, the anchor over WebSocket. That is deliberate — the anchor has
   * to merge like any other CRDT operation, and the conversation has nothing to merge
   * (CP14).
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
    syncFormatting(model.formatting())
  }, [canComment, syncFormatting])

  /**
   * The selection as two character identifiers, for a comment that has to wait (CP27).
   *
   * `to` is the identifier of the LAST character in the selection, not of the one past it, because
   * there may not be one past it — the same convention `proposeDeletion` sends and for the same
   * reason. `anchorCommentAt` puts the pair back together.
   *
   * `idAtVisible` throws rather than returning null on a position it cannot resolve, and nothing
   * here catches that: `selectionRange` is written by `onSelectionChange` from CodeMirror's own
   * current selection, and `Editor` asserts on every local change that CodeMirror's text and the
   * CRDT's text are identical. If those two ever disagreed, a thrown `RangeError` naming the
   * position is a far better outcome than a comment quietly anchored to the wrong words.
   */
  const captureAnchor = useCallback(() => {
    const model = document.current
    const range = selectionRange.current
    if (model === null || range === null) return null
    return { from: model.idAtVisible(range.from), to: model.idAtVisible(range.to - 1) }
  }, [])

  /**
   * Anchors a comment to a range captured earlier — the offline half of `anchorComment` (CP27).
   *
   * This is what was missing from CP27, and its absence had a visible cost: a comment written with
   * no connection was created when the outbox drained and never got a `comment` mark, so
   * `thread.anchored` came back false and the panel told the user the anchored text had been
   * deleted. Nobody had deleted anything.
   *
   * `visibleIndexOf` returns null for a character that is now a tombstone or is not in this
   * replica at all. That case is real — the comment may have sat in the outbox for hours — and it
   * is reported to the caller rather than swallowed, because "there was never an anchor" and "the
   * anchor was destroyed later" are different things to tell somebody, and only one of them is
   * about the document having changed.
   */
  const anchorCommentAt = useCallback((
    threadId: string,
    range: { from: Id; to: Id },
  ): AnchorOutcome => {
    const model = document.current
    const link = connection.current
    // 'not-ready' rather than 'gone', and the difference is what the caller shows the user. This
    // returned a plain `false` for both, so a comment flushed while the document was still opening
    // — `role` defaults to `viewer` until the server answers, and the panel is not gated on
    // `ready` — was reported as text that had been deleted. Nobody had deleted anything, which is
    // the exact sentence CP27 exists to stop the interface saying.
    if (model === null || link === null || !canComment) return 'not-ready'
    const from = model.visibleIndexOf(range.from)
    const to = model.visibleIndexOf(range.to)
    if (from === null || to === null || from > to) return 'gone'
    // `to + 1`, because the captured `to` names the last character INSIDE the range and `addMark`
    // takes a half-open one. Off by one here would leave the last commented character unmarked,
    // which is invisible until somebody deletes exactly that character and the thread detaches
    // for no apparent reason.
    link.send(model.addMark(from, to + 1, 'comment', threadId))
    syncFormatting(model.formatting())
    return 'anchored'
  }, [canComment, syncFormatting])

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
    // No null check on these two: `idAtVisible` returns an `Id` and throws — it never returns
    // null, so `if (from === null || to === null) return` was a branch that could not be taken.
    // Worse than dead, it was misdirection: what actually happens on a selection this replica can
    // no longer resolve is a `RangeError` out of `idAtVisible`, which `SuggestionsPanel.run`
    // catches and reports as "that text is no longer there — somebody changed it while you were
    // writing". That message is right, and it was right by accident, with a guard standing in
    // front of it claiming to handle the case.
    const from = model.idAtVisible(range.from)
    const to = model.idAtVisible(range.to - 1)
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

  // The same guard `toggleMark` and `toggleBlock` carry, and for the same reason: Ctrl+Z is a
  // KEYBOARD SHORTCUT, so greying out a button stops nothing, and `EditorState.readOnly` does not
  // apply either — undo produces operations against the CRDT model and reaches CodeMirror through
  // a programmatic dispatch, which `readOnly` does not gate. An editor demoted mid-session with a
  // full undo stack could otherwise press Ctrl+Z, have the operations rejected by the server, and
  // diverge permanently.
  const runUndo = useCallback(() => {
    if (!canWrite) return
    const ops = undo.current?.undo() ?? []
    if (ops.length > 0) {
      applyRemote.current()
      onLocalOps(ops)
    }
  }, [onLocalOps, canWrite])

  const runRedo = useCallback(() => {
    if (!canWrite) return
    const ops = undo.current?.redo() ?? []
    if (ops.length > 0) {
      applyRemote.current()
      onLocalOps(ops)
    }
  }, [onLocalOps, canWrite])

  const registerRemoteApply = useCallback((apply: () => void) => {
    applyRemote.current = apply
  }, [])

  const updateNetwork = useCallback((patch: Partial<NetworkConditions>) => {
    connection.current?.simulator.update(patch)
  }, [])

  // Cursor positions arrive as CRDT character identifiers and are turned into offsets only here,
  // at the last moment before drawing.
  const remoteCursors: RemoteCursor[] = useMemo(() => {
    const model = document.current
    if (!model) return []

    /**
     * A place on the wire, turned into an offset in the text we are about to draw.
     *
     * The `side` is what makes position 0 expressible: "after character X" is the position one
     * past X, "before character X" is X's own position. Until the side existed, a caret at the
     * start of the document had nothing to anchor to and was sent as null — so it was dropped, and
     * a collaborator pressing Home vanished from everybody else's screen (see
     * `transport/cursorAnchor.ts`).
     *
     * Clamped to the character count, because presence and the local replica are two independent
     * streams: a cursor can name a character we have while the model is momentarily behind on the
     * text around it, and a decoration past the end of the document makes CodeMirror throw at
     * render time rather than draw something slightly wrong.
     */
    const resolve = (replicaId: number | null, lamport: number | null): number | null => {
      const anchor = decodeCursorAnchor(replicaId, lamport)
      if (anchor === null) return null
      const index = model.visibleIndexOf(anchor.charId)
      // null means the character is a tombstone or is not in this replica — the cursor of somebody
      // whose character has just been deleted disappears rather than pointing somewhere arbitrary.
      if (index === null) return null
      return Math.min(anchor.side === 'after' ? index + 1 : index, characters)
    }

    return presence
      .filter((entry) => entry.userId !== session.userId)
      .map((entry) => ({
        replicaId: entry.replicaId,
        displayName: entry.displayName,
        color: entry.color,
        head: resolve(entry.cursorReplicaId, entry.cursorLamport),
        anchor: resolve(entry.anchorReplicaId, entry.anchorLamport),
        typing: entry.typing,
      }))
  }, [presence, session.userId, characters])

  return {
    model: document.current,
    undo: undo.current,
    title,
    role,
    ready,
    replicaAssigned,
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
    notice,
    dismissNotice,
    toggleMark,
    toggleBlock,
    anchorComment,
    captureAnchor,
    anchorCommentAt,
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
