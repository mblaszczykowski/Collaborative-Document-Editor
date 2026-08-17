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

export type AnchorOutcome = 'anchored' | 'gone' | 'not-ready'

export interface CollaborativeDocument {
  model: RgaDocument | null
  undo: UndoManager | null

  title: string
  role: DocumentState['role']
  ready: boolean
  replicaAssigned: boolean
  unavailable: boolean

  canWrite: boolean
  canComment: boolean

  connectionState: ConnectionState
  pendingCount: number
  presence: PresenceEntry[]
  presenceComplete: boolean
  remoteCursors: RemoteCursor[]
  network: NetworkConditions

  characters: number
  formatting: Span[]
  selection: string
  recovery: { title: string; id: string } | null
  notice: string | null
  dismissNotice: () => void

  toggleMark: (type: string, from?: number, to?: number) => void
  toggleBlock: (type: string, value: string | null) => void
  anchorComment: (threadId: string) => void
  captureAnchor: () => { from: Id; to: Id } | null
  anchorCommentAt: (threadId: string, range: { from: Id; to: Id }) => AnchorOutcome
  proposeDeletion: () => Promise<void>
  proposeInsertion: (text: string) => Promise<void>
  onLocalOps: (ops: Op[]) => void
  onCursorMove: (head: CursorAnchor | null, anchor: CursorAnchor | null) => void
  onSelectionChange: (from: number, to: number, text: string) => void
  runUndo: () => void
  runRedo: () => void
  registerRemoteApply: (apply: () => void) => void
  updateNetwork: (patch: Partial<NetworkConditions>) => void
}

const USER_FACING_ERRORS: Record<string, string> = {
  OFFLINE_BUFFER_UNAVAILABLE:
    'This browser is not letting the editor keep a local backup, so unsent changes would be lost '
    + 'if the tab closed. Editing still works - avoid closing the tab while offline.',
  OPERATION_REJECTED:
    'The server refused one of your changes, so it is not in the document. Reload the page to be '
    + 'sure of what was saved.',
}

export function useCollaborativeDocument(
  documentId: string,
  session: Session,
): CollaborativeDocument {
  const [title, setTitle] = useState('')

  const [reopenToken, setReopenToken] = useState(0)

  const [role, setRole] = useState<DocumentState['role']>('viewer')
  const [ready, setReady] = useState(false)
  const [replicaAssigned, setReplicaAssigned] = useState(false)
  const [unavailable, setUnavailable] = useState(false)

  const [connectionState, setConnectionState] = useState<ConnectionState>('connecting')
  const [pendingCount, setPendingCount] = useState(0)
  const [presence, setPresence] = useState<PresenceEntry[]>([])
  const [presenceComplete, setPresenceComplete] = useState(true)
  const [characters, setCharacters] = useState(0)
  const [formatting, setFormatting] = useState<Span[]>([])
  const [recovery, setRecovery] = useState<{ title: string; id: string } | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const dismissNotice = useCallback(() => setNotice(null), [])
  const [network, setNetwork] = useState<NetworkConditions>({
    latencyMs: 0,
    jitterMs: 0,
    packetLoss: 0,
    partitioned: false,
  })

  const [selection, setSelection] = useState('')
  const selectionRange = useRef<{ from: number; to: number } | null>(null)
  const caret = useRef(0)

  const document = useRef<RgaDocument | null>(null)
  const undo = useRef<UndoManager | null>(null)
  const connection = useRef<DocumentConnection | null>(null)
  const applyRemote = useRef<() => void>(() => {})

  const canWrite = role === 'owner' || role === 'editor'
  const canComment = canWrite || role === 'commenter'

  const syncFormatting = useCallback((next: Span[]) => {
    setFormatting((previous) => (sameSpans(previous, next) ? previous : next))
  }, [])

  const refreshStats = useCallback(() => {
    const model = document.current
    if (model === null) return
    setCharacters(model.visibleLength)
    syncFormatting(model.formatting())
  }, [syncFormatting])

  const recoverAfterRejection = useCallback(
    async (model: RgaDocument, link: DocumentConnection) => {
      const rescued = await api.createRecovered(
        `Recovered draft - ${new Date().toLocaleString('en-GB')}`,
        model.text,
      )
      await link.abandonBuffer()
      setRecovery({ title: rescued.title, id: rescued.id })
    },
    [],
  )

  useEffect(() => {
    let cancelled = false
    let saveTimer: number | undefined
    let stopObservingUndo: (() => void) | null = null

    setReplicaAssigned(false)

    void (async () => {
      const state = await api.openDocument(documentId)
      if (cancelled) return
      setUnavailable(false)

      const model = new RgaDocument(0)

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
      }
      if (cancelled) return

      document.current = model
      const undoManager = new UndoManager(model)
      undo.current = undoManager

      void offlineStore
        .loadUndo<unknown>(documentId)
        .then((saved) => {
          if (!cancelled && saved !== null) undoManager.restore(saved)
        })
        .catch(() => {})
      stopObservingUndo = undoManager.observe(() => {
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
        onSession: (replicaId) => {
          if (!model.hasGeneratedOperations) {
            model.adoptReplicaId(replicaId)
          } else if (model.replicaId !== replicaId) {
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
            console.error(`[${code}] ${message}`)
            setReopenToken((token) => token + 1)
            return
          }
          console.error(`[${code}] ${message}`)
          if (code === 'STOMP_ERROR') {
            void api.myRole(documentId).catch(() => undefined)
          }
          const explanation = USER_FACING_ERRORS[code]
          if (explanation !== undefined) {
            setNotice(explanation)
          }
        },
        onAccessChanged: () => {
          void api.myRole(documentId)
            .then((mine) => setRole(mine.role))
            .catch((error: unknown) => {
              if (error instanceof ApiError && error.status === 404) {
                setUnavailable(true)
                setRole('viewer')
                return
              }
              console.error('could not refresh the role after an access change', error)
            })
        },
      })
      link.simulator.onChange(setNetwork)
      connection.current = link
      void link
        .restoreOfflineBuffer()
        .catch(() => [])
        .then(() => link.connect())
          .catch(() => {
          if (!cancelled) setUnavailable(true)
        })
    })().catch(() => {
      if (!cancelled) setUnavailable(true)
    })

    return () => {
      cancelled = true
      window.clearTimeout(saveTimer)
      stopObservingUndo?.()
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

  const onSelectionChange = useCallback((from: number, to: number, text: string) => {
    selectionRange.current = from < to ? { from, to } : null
    caret.current = from
    setSelection(text)
  }, [])

  const toggleMark = useCallback((type: string, explicitFrom?: number, explicitTo?: number) => {
    const model = document.current
    const link = connection.current
    const from = explicitFrom ?? selectionRange.current?.from
    const to = explicitTo ?? selectionRange.current?.to
    if (model === null || link === null || from === undefined || to === undefined || from >= to) {
      return
    }
    if (!canWrite) return

    const alreadyMarked = coveredBy(model.formatting(), type, from, to)
    const before = alreadyMarked
      ? (model.formatting().find(
          (span) => span.from <= from && span.to > from && span.marks.has(type),
      )?.marks.get(type) ?? null)
      : undefined

    let value: string | null = null
    if (type === 'link' && !alreadyMarked) {
      value = window.prompt('Link address')
      if (value === null) return
    }
    const op = alreadyMarked
      ? model.removeMark(from, to, type)
      : model.addMark(from, to, type, value)

    undo.current?.recordMark(type, model.idAtVisible(from), model.idAtVisible(to - 1), before,
      alreadyMarked ? undefined : value)

    link.send(op)
    syncFormatting(model.formatting())
  }, [canWrite, syncFormatting])

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
    const before = already
      ? (model.formatting().find(
          (span) => span.from <= start && span.to > start && span.marks.has(type),
      )?.marks.get(type) ?? null)
: undefined

    const op = already
      ? model.removeMark(start, end, type)
      : model.addMark(start, end, type, value)

    undo.current?.recordMark(type, model.idAtVisible(start), model.idAtVisible(end - 1),
      before, already ? undefined : value)

    link.send(op)
    syncFormatting(model.formatting())
  }, [canWrite, syncFormatting])

  const anchorComment = useCallback((threadId: string) => {
    const model = document.current
    const link = connection.current
    const range = selectionRange.current
    if (model === null || link === null || range === null) return
    if (!canComment) return
    link.send(model.addMark(range.from, range.to, 'comment', threadId))
    syncFormatting(model.formatting())
  }, [canComment, syncFormatting])

  const captureAnchor = useCallback(() => {
    const model = document.current
    const range = selectionRange.current
    if (model === null || range === null) return null
    return { from: model.idAtVisible(range.from), to: model.idAtVisible(range.to - 1) }
  }, [])

  const anchorCommentAt = useCallback((
    threadId: string,
    range: { from: Id; to: Id },
  ): AnchorOutcome => {
    const model = document.current
    const link = connection.current
    if (model === null || link === null || !canComment) return 'not-ready'
    const from = model.visibleIndexOf(range.from)
    const to = model.visibleIndexOf(range.to)
    if (from === null || to === null || from > to) return 'gone'
    link.send(model.addMark(from, to + 1, 'comment', threadId))
    syncFormatting(model.formatting())
    return 'anchored'
  }, [canComment, syncFormatting])

  const proposeDeletion = useCallback(async () => {
    const model = document.current
    const range = selectionRange.current
    if (model === null || range === null) return
    const from = model.idAtVisible(range.from)
    const to = model.idAtVisible(range.to - 1)
    await api.proposeDeletion(documentId, from, to)
  }, [documentId])

  const proposeInsertion = useCallback(async (text: string) => {
    const model = document.current
    if (model === null || text === '') return
    const range = selectionRange.current
    const after = range === null || range.to === 0 ? null : model.idAtVisible(range.to - 1)
    await api.proposeInsertion(documentId, after, text)
  }, [documentId])

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

  const remoteCursors: RemoteCursor[] = useMemo(() => {
    const model = document.current
    if (!model) return []

    const resolve = (replicaId: number | null, lamport: number | null): number | null => {
      const anchor = decodeCursorAnchor(replicaId, lamport)
      if (anchor === null) return null
      const index = model.visibleIndexOf(anchor.charId)
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
