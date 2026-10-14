import { useCallback, useEffect, useRef, useState } from 'react'
import { ApiError, api, type DocumentState, type Session } from '../api'
import { RgaDocument } from '../crdt/document'
import { acquireWriterLease, writerLocksSupported, type WriterLease } from '../transport/writerLease'
import { COMMENTS_CHANGED, drainComments } from '../transport/commentOutbox'
import { decodeSnapshot } from '../crdt/snapshot'
import type { DecodedSnapshot } from '../crdt/snapshot'
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

  selfReplicaId: number | null
  revision: number
  canUndo: boolean
  canRedo: boolean
  caret: number
  selectionFrom: number
  selectionTo: number
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

  refreshMetadata: () => Promise<void>
  updateTitle: (title: string) => void
  setLink: (value: string | null) => void
  toggleMark: (type: string, from?: number, to?: number) => void
  toggleBlock: (type: string, value: string | null) => void
  anchorComment: (threadId: string) => void
  captureAnchor: () => { from: Id; to: Id } | null
  anchorCommentAt: (threadId: string, range: { from: Id; to: Id }) => Promise<AnchorOutcome>
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
    'Local recovery storage is unavailable. Newly opened documents stay read-only until a recovery '
    + 'copy can be saved. Keep this tab open if it already contains unsent changes.',
  OPERATION_REJECTED:
    'The server refused one of your changes, so it is not in the document. Reload the page to be '
    + 'sure of what was saved.',
}

export function useCollaborativeDocument(
  documentId: string,
  session: Session,
  excludedFromList = false,
): CollaborativeDocument {
  const [selfReplicaId, setSelfReplicaId] = useState<number | null>(null)
  const [revision, setRevision] = useState(0)
  const [undoState, setUndoState] = useState({ canUndo: false, canRedo: false })
  const [position, setPosition] = useState({ from: 0, to: 0 })
  const [actorWritable, setActorWritable] = useState(false)
  const [recovering, setRecovering] = useState(false)
  const recoveringNow = useRef(false)
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
  const metadataRefresh = useRef<() => Promise<void>>(async () => {})
  const titleUpdater = useRef<(title: string) => void>(() => {})
  const persistLocal = useRef<() => void>(() => {})
  const applyRemote = useRef<() => void>(() => {})

  const canWrite = (role === 'owner' || role === 'editor') && !recovering && actorWritable
  const canComment = (role === 'owner' || role === 'editor' || role === 'commenter')
    && !recovering && actorWritable

  const syncFormatting = useCallback((next: Span[]) => {
    setFormatting((previous) => (sameSpans(previous, next) ? previous : next))
  }, [])

  const refreshStats = useCallback(() => {
    const model = document.current
    if (model === null) return
    setRevision(value => value + 1)
    setCharacters(model.visibleLength)
    syncFormatting(model.formatting())
  }, [syncFormatting])

  const recoverAfterRejection = useCallback(
    async (model: RgaDocument, link: DocumentConnection) => {
      if (recoveringNow.current) return
      recoveringNow.current = true
      setRecovering(true)
      try {
        const rescued = await api.createRecovered(
          `Recovered draft - ${new Date().toLocaleString('en-GB')}`, model.text,
          model.formatting().map(span => ({ from: span.from, to: span.to, marks: Object.fromEntries(span.marks) })),
        )
        if (document.current !== model) return
        await link.abandonBuffer()
        setRecovery({ title: rescued.title, id: rescued.id })
        setReopenToken(token => token + 1)
      } catch {
        if (document.current !== model) return
        setNotice('Your draft could not be recovered yet. Its unsent changes are still kept locally. '
          + 'Reconnect and reload to retry recovery.')
      } finally {
        recoveringNow.current = false
        setRecovering(false)
      }
    }, [],
  )

  useEffect(() => {
    let cancelled = false
    let saveTimer: number | undefined
    let cacheTimer: number | undefined
    let cacheSave: (() => Promise<void>) | undefined
    let writerLease: WriterLease | null = null
    let leasedActor: number | null = null
    let leaseAttempt = 0
    const leaseActor = async (replicaId: number, offline: boolean) => {
      const attempt = ++leaseAttempt
      if (offline && (!Number.isSafeInteger(replicaId) || replicaId <= 0)) {
        setNotice('This local copy has no assigned editing identity yet. Connect to the server before editing.')
        return false
      }
      if (leasedActor === replicaId && writerLease !== null) return true
      if (!writerLocksSupported()) {
        if (offline) {
          setNotice('This browser cannot safely coordinate offline editing between tabs. Your local copy is read-only until connected.')
          return false
        }
        return !cancelled
      }
      const next = await acquireWriterLease(session.userId, documentId, replicaId)
      if (cancelled || attempt !== leaseAttempt) { next?.release(); return false }
      if (next === null) {
        setNotice('Another tab is editing this local copy. This tab is read-only; close the other tab and reload to edit here.')
        return false
      }
      const previous = writerLease
      writerLease = next
      leasedActor = replicaId
      previous?.release()
      return true
    }
    let stopObservingUndo: (() => void) | null = null

    applyRemote.current = () => {}
    setActorWritable(false)
    setReplicaAssigned(false)
    setReady(false)
    setUnavailable(false)

    void (async () => {
      type Cached = { title: string; role: DocumentState['role']; replicaId: number; state: DecodedSnapshot }
      let fromCache = false
      let cached: Cached | null = null
      let state: Pick<DocumentState, 'title' | 'role'>
      try {
        if (!navigator.onLine) throw new Error('Browser is offline')
        state = await api.openDocument(documentId)
      }
      catch (error) {
        if (error instanceof ApiError && [401, 403, 404].includes(error.status)) throw error
        cached = await offlineStore.loadDocument<Cached>(documentId, session.userId).catch(() => null)
        if (cached === null) throw error
        state = cached
        fromCache = true
        setNotice('This is your local copy. Changes will sync when the server is available.')
      }
      if (cancelled) return
      setUnavailable(false)

      const model = new RgaDocument(cached?.replicaId ?? 0)
      if (cached) model.restoreFrom(cached.state)

      let from = 0
      try {
        const snapshot = fromCache ? null : await api.snapshot(documentId)
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

      const savedUndo = await offlineStore.loadUndo<unknown>(documentId, session.userId).catch(() => null)
      if (cancelled) return
      if (savedUndo !== null) undoManager.restore(savedUndo)
      setUndoState({ canUndo: undoManager.canUndo, canRedo: undoManager.canRedo })
      stopObservingUndo = undoManager.observe(() => {
        setUndoState({ canUndo: undoManager.canUndo, canRedo: undoManager.canRedo })
        window.clearTimeout(saveTimer)
        saveTimer = window.setTimeout(() => {
          void offlineStore.saveUndo(documentId, undoManager.serialise(), session.userId).catch(() => {})
        }, 1_000)
      })
      setTitle(state.title)
      setRole(state.role)
      setReady(true)
      let cachedWriterOwned = false
      if (fromCache) {
        const owned = await leaseActor(model.replicaId, true)
        if (cancelled) return
        cachedWriterOwned = owned
      }
      refreshStats()
      let cacheWrites: Promise<void> = Promise.resolve()
      cacheSave = () => {
        const captured = { title: state.title, role: state.role,
          replicaId: model.replicaId, state: model.cachedState() }
        cacheWrites = cacheWrites.catch(() => {}).then(() =>
          offlineStore.saveDocument(documentId, captured, session.userId))
        return cacheWrites
      }
      const saveCache = () => {
        window.clearTimeout(cacheTimer)
        cacheTimer = window.setTimeout(() => { void cacheSave?.().catch(() => {}) }, 300)
      }
      if (fromCache) saveCache()
      let metadataTicket = 0
      const refreshMetadata = async () => {
        const ticket = ++metadataTicket
        try {
          const next = await api.openDocument(documentId)
          if (cancelled || ticket !== metadataTicket) return
          state.title = next.title
          state.role = next.role
          setTitle(next.title)
          setRole(next.role)
          setUnavailable(false)
          saveCache()
        } catch (error) {
          if (!cancelled && ticket === metadataTicket && error instanceof ApiError
            && [403, 404].includes(error.status)) {
            setUnavailable(true)
            state.role = 'viewer'
            setRole('viewer')
            saveCache()
          }
        }
      }
      metadataRefresh.current = refreshMetadata
      titleUpdater.current = next => {
        metadataTicket++
        state.title = next
        setTitle(next)
        saveCache()
      }

      const link = new DocumentConnection(documentId, session.token, from, {
        onSession: (replicaId) => {
          if (cancelled) return
          setSelfReplicaId(replicaId)
          const actor = model.hasGeneratedOperations ? model.replicaId : replicaId
          // Keep an existing actor lease until the replacement actor is owned.
          setActorWritable(false)
          void leaseActor(actor, false).then(async owned => {
            if (cancelled || !owned) return
            if (!model.hasGeneratedOperations) model.adoptReplicaId(replicaId)
            await cacheSave?.()
            if (cancelled) return
            setActorWritable(true)
            setReplicaAssigned(true)
            saveCache()
          }).catch(() => {
            if (!cancelled) setNotice('This browser could not save a local recovery copy. Editing is read-only until storage is available.')
          })
        },
        persistApplied: () => cacheSave?.() ?? Promise.resolve(),
        versionVector: () => model.version,
        hasOperation: (replicaId, lamport) => model.contains({ replicaId, lamport }),
        hasApplied: (op) => !fromCache && model.holds(op),
        onRemoteOps: (ops) => {
          if (cancelled) return
          model.applyAll(ops)
          applyRemote.current()
          refreshStats()
          saveCache()
        },
        onPresence: (entries, complete) => {
          if (cancelled) return
          setPresence(entries)
          setPresenceComplete(complete)
        },
        onStateChange: (nextState, pending) => {
          if (cancelled) return
          setConnectionState(nextState)
          setPendingCount(pending)
        },
        onError: (code, message) => {
          if (cancelled) return
          if (code === 'RESYNC_REQUIRED' || code === 'REPLICA_ID_CONFLICT') {
            void recoverAfterRejection(model, link)
            return
          }
          if (code === 'SNAPSHOT_REQUIRED') {
            console.error(`[${code}] ${message}`)
            setReopenToken((token) => token + 1)
            return
          }
          if (code === 'ACCESS_DENIED' || code === 'SESSION_EXPIRED') {
            console.warn(`[${code}] ${message}`)
          } else {
            console.error(`[${code}] ${message}`)
          }
          if (code === 'STOMP_ERROR' || code === 'ACCESS_DENIED') {
            void refreshMetadata()
          }
          if (code === 'SESSION_EXPIRED') {
            void api.myRole(documentId).catch(() => {})
            setRole('viewer')
            setNotice('Your session expired. Unsent edits are kept locally. Sign in again to sync them.')
          }
          if (code === 'ACCESS_DENIED' || code === 'ACCESS_UNAVAILABLE') {
            setNotice(code === 'ACCESS_DENIED'
              ? 'Your access changed. Unsent edits are kept locally; ask the owner to restore access.'
              : 'The server could not check access. Your changes are kept locally while it recovers.')
          }
          const explanation = USER_FACING_ERRORS[code]
          if (explanation !== undefined) {
            setNotice(explanation)
          }
        },
        onAccessChanged: () => { void refreshMetadata() },
      }, session.userId)
      link.simulator.onChange(setNetwork)
      connection.current = link
      persistLocal.current = saveCache
      void link
        .restoreOfflineBuffer()
        .catch(() => {
          if (fromCache) {
            cachedWriterOwned = false
            setNotice('Unsent changes could not be read from this browser. This local copy is read-only until connected.')
          }
          return []
        })
        .then(() => {
          if (cancelled) return
          if (fromCache) {
            setActorWritable(cachedWriterOwned)
            setReplicaAssigned(cachedWriterOwned)
          }
          link.connect()
        })
          .catch(() => {
          if (!cancelled) setUnavailable(true)
        })
    })().catch(() => {
      if (!cancelled) setUnavailable(true)
    })

    return () => {
      cancelled = true
      metadataRefresh.current = async () => {}
      titleUpdater.current = () => {}
      leaseAttempt++
      writerLease?.release()
      writerLease = null
      window.clearTimeout(saveTimer)
      window.clearTimeout(cacheTimer)
      void cacheSave?.().catch(() => {})
      if (undo.current) void offlineStore.saveUndo(documentId, undo.current.serialise(), session.userId).catch(() => {})
      stopObservingUndo?.()
      connection.current?.disconnect()
      connection.current = null
    }
  }, [documentId, session.token, session.userId, refreshStats, recoverAfterRejection,
    reopenToken])

  const refreshMetadata = useCallback(async () => {
    if (!ready) { setReopenToken(token => token + 1); return }
    await metadataRefresh.current()
  }, [ready])
  const updateTitle = useCallback((next: string) => titleUpdater.current(next), [])
  useEffect(() => {
    if (!ready || (!excludedFromList && !unavailable)) return
    const refresh = () => {
      if (window.document.visibilityState !== 'hidden') void refreshMetadata()
    }
    const timer = window.setInterval(refresh, 30_000)
    window.addEventListener('focus', refresh)
    return () => { window.clearInterval(timer); window.removeEventListener('focus', refresh) }
  }, [ready, excludedFromList, unavailable, refreshMetadata])

  const onLocalOps = useCallback(
    (ops: Op[]) => {
      for (const op of ops) connection.current?.send(op)
      refreshStats()
      persistLocal.current()
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
    setPosition({ from, to })
  }, [])

  const toggleMark = useCallback((type: string, explicitFrom?: number, explicitTo?: number) => {
    const model = document.current
    const link = connection.current
    const from = explicitFrom ?? selectionRange.current?.from
    const to = explicitTo ?? selectionRange.current?.to
    if (model === null || link === null || from === undefined || to === undefined || from >= to) {
      return
    }
    if (!canWrite || !replicaAssigned || recoveringNow.current) return

    const beforeSpans = model.formatting()
    const alreadyMarked = coveredBy(beforeSpans, type, from, to)


    let value: string | null = null
    if (type === 'link' && !alreadyMarked) {
      value = window.prompt('Link address')
      if (value === null) return
      try { if (!['http:', 'https:', 'mailto:'].includes(new URL(value).protocol)) throw new Error() }
      catch { setNotice('Use a complete http, https, or mailto address.'); return }
    }
    const op = alreadyMarked
      ? model.removeMark(from, to, type)
      : model.addMark(from, to, type, value)

    undo.current?.recordMarkChange(type, from, to, beforeSpans, alreadyMarked ? undefined : value)

    link.send(op)
    refreshStats()
    persistLocal.current()
  }, [refreshStats, canWrite, replicaAssigned])

  const toggleBlock = useCallback((type: string, value: string | null) => {
    const model = document.current
    const link = connection.current
    if (model === null || link === null || !canWrite || !replicaAssigned || recoveringNow.current) return

    const text = model.text
    const at = Math.min(selectionRange.current?.from ?? caret.current, text.length)
    const start = text.lastIndexOf('\n', at - 1) + 1
    const selectionEnd = selectionRange.current?.to ?? at
    const newline = text.indexOf('\n', Math.max(at, selectionEnd - 1))
    const end = newline === -1 ? text.length : newline
    const ranges: { from: number; to: number }[] = []
    let from = start
    while (from <= end) {
      const lineEnd = text.indexOf('\n', from)
      const to = lineEnd === -1 ? text.length : Math.min(lineEnd, end)
      if (from < to) ranges.push({ from, to })
      if (lineEnd === -1 || lineEnd >= end) break
      from = lineEnd + 1
    }
    const beforeSpans = model.formatting()
    const already = ranges.length > 0 && ranges.every(range =>
      coveredBy(beforeSpans, type, range.from, range.to)
      && beforeSpans.filter(span => span.from < range.to && span.to > range.from)
        .every(span => span.marks.get(type) === value))
    undo.current?.transact(() => {
      for (const range of ranges) {
        const op = already ? model.removeMark(range.from, range.to, type)
          : model.addMark(range.from, range.to, type, value)
        undo.current?.recordMarkChange(type, range.from, range.to, beforeSpans, already ? undefined : value)
        link.send(op)
      }
    })
    refreshStats()
    persistLocal.current()
  }, [canWrite, replicaAssigned, refreshStats])

  const setLink = useCallback((value: string | null) => {
    const model = document.current, link = connection.current, range = selectionRange.current
    if (!model || !link || !range || !canWrite || !replicaAssigned || recoveringNow.current) return
    if (value !== null) {
      try { if (!['http:', 'https:', 'mailto:'].includes(new URL(value).protocol)) throw new Error() }
      catch { setNotice('Use a complete http, https, or mailto address.'); return }
    }
    const before = model.formatting()
    const op = value === null ? model.removeMark(range.from, range.to, 'link')
      : model.addMark(range.from, range.to, 'link', value)
    undo.current?.recordMarkChange('link', range.from, range.to, before, value === null ? undefined : value)
    link.send(op)
    refreshStats()
    persistLocal.current()
  }, [canWrite, replicaAssigned, refreshStats])

  const anchorComment = useCallback((threadId: string) => {
    const model = document.current
    const link = connection.current
    const range = selectionRange.current
    if (model === null || link === null || range === null) return
    if (!canComment || !replicaAssigned || recoveringNow.current) return
    link.send(model.addMark(range.from, range.to, 'comment', threadId))
    refreshStats()
    persistLocal.current()
  }, [canComment, replicaAssigned, refreshStats])

  const captureAnchor = useCallback(() => {
    const model = document.current
    const range = selectionRange.current
    if (model === null || range === null) return null
    return { from: model.idAtVisible(range.from), to: model.idAtVisible(range.to - 1) }
  }, [])

  const anchorCommentAt = useCallback(async (
    threadId: string,
    range: { from: Id; to: Id },
  ): Promise<AnchorOutcome> => {
    const model = document.current
    const link = connection.current
    if (model === null || link === null || !canComment || !replicaAssigned || recoveringNow.current) return 'not-ready'
    let op = model.markOperation('comment', threadId)
    if (!op) {
      const from = model.visibleIndexOf(range.from)
      const to = model.visibleIndexOf(range.to)
      if (from === null || to === null || from > to) return 'gone'
      op = model.addMark(from, to + 1, 'comment', threadId)
      refreshStats()
      persistLocal.current()
    }
    // Reuse the same operation identity on retry, including after cache/outbox replay.
    if (!link.hasPending(op)) link.send(op)
    await link.waitForDurable(op)
    return 'anchored'
  }, [canComment, replicaAssigned, refreshStats])

  useEffect(() => {
    if (!ready || !replicaAssigned || !canComment || recovering) return
    let cancelled = false
    const run = () => {
      if (cancelled) return
      void drainComments(documentId, session.userId, anchorCommentAt, setNotice, () => !cancelled).catch(() => {})
    }
    run()
    const timer = window.setInterval(run, 10_000)
    window.addEventListener('online', run)
    window.addEventListener(COMMENTS_CHANGED, run)
    return () => { cancelled = true; window.clearInterval(timer)
      window.removeEventListener('online', run); window.removeEventListener(COMMENTS_CHANGED, run) }
  }, [documentId, session.userId, ready, replicaAssigned, canComment, recovering, anchorCommentAt])

  const proposeDeletion = useCallback(async () => {
    const model = document.current
    const range = selectionRange.current
    if (model === null || range === null || !canComment || recoveringNow.current) return
    const from = model.idAtVisible(range.from)
    const to = model.idAtVisible(range.to - 1)
    await api.proposeDeletion(documentId, from, to)
  }, [documentId, canComment])

  const proposeInsertion = useCallback(async (text: string) => {
    const model = document.current
    if (model === null || text === '' || !canComment || recoveringNow.current) return
    const range = selectionRange.current
    const at = range?.to ?? caret.current
    const after = at === 0 ? null : model.idAtVisible(at - 1)
    await api.proposeInsertion(documentId, after, text)
  }, [documentId, canComment])

  const runUndo = useCallback(() => {
    if (!canWrite || !replicaAssigned || recoveringNow.current) return
    const ops = undo.current?.undo() ?? []
    if (ops.length > 0) {
      applyRemote.current()
      onLocalOps(ops)
    }
  }, [onLocalOps, canWrite, replicaAssigned])

  const runRedo = useCallback(() => {
    if (!canWrite || !replicaAssigned || recoveringNow.current) return
    const ops = undo.current?.redo() ?? []
    if (ops.length > 0) {
      applyRemote.current()
      onLocalOps(ops)
    }
  }, [onLocalOps, canWrite, replicaAssigned])

  const registerRemoteApply = useCallback((apply: () => void) => {
    applyRemote.current = apply
  }, [])

  const updateNetwork = useCallback((patch: Partial<NetworkConditions>) => {
    connection.current?.simulator.update(patch)
  }, [])

  const remoteCursors: RemoteCursor[] = (() => {
    const model = document.current
    if (!model) return []

    const resolve = (replicaId: number | null, lamport: number | null): number | null => {
      const anchor = decodeCursorAnchor(replicaId, lamport)
      if (anchor === null) return null
      const index = model.anchorPosition(anchor.charId, anchor.side)
      return index === null ? null : Math.min(index, characters)
    }

    return presence
      .filter((entry) => entry.replicaId !== selfReplicaId)
      .map((entry) => ({
        replicaId: entry.replicaId,
        displayName: entry.displayName,
        color: entry.color,
        head: resolve(entry.cursorReplicaId, entry.cursorLamport),
        anchor: resolve(entry.anchorReplicaId, entry.anchorLamport),
        typing: entry.typing,
      }))
  })()

  return {
    selfReplicaId,
    revision,
    canUndo: undoState.canUndo,
    canRedo: undoState.canRedo,
    caret: position.from,
    selectionFrom: position.from,
    selectionTo: position.to,
    setLink,
    refreshMetadata,
    updateTitle,
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
