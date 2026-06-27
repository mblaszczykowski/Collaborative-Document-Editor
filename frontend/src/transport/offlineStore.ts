import { encodeOp, decodeOp, type WireOp } from '../crdt/codec'
import type { Op } from '../crdt/types'

/**
 * A durable buffer of operations not yet acknowledged as written.
 *
 * An in-memory buffer is enough for losing the network. It is not enough for what a user actually
 * does: closes the tab, puts the laptop to sleep, reloads the page because "something froze". In
 * all of those cases operations that were sent but not acknowledged would be lost — even though the
 * user had seen them on screen.
 *
 * Hence IndexedDB rather than `localStorage`: the latter is synchronous and blocks the UI thread on
 * every write, that is, on every keystroke. On a document with a long buffer that would be
 * noticeable exactly when the network is down and everything is irritating anyway.
 *
 * An entry disappears only once the server acknowledges a DURABLE WRITE — never on the
 * broadcast alone.
 */

/**
 * Renamed from `inkline` when the product name was dropped, and that rename is not free: a browser
 * holding unacknowledged operations under the old database keeps them, unread, for ever. Those are
 * edits the user watched appear on screen.
 *
 * <p>Accepted rather than migrated, and the reason is the window rather than the principle. The
 * buffer only holds anything between a write and its acknowledgement, so a client has to have been
 * offline or mid-flight across the exact deploy that carried the rename — and this project has no
 * deployment with users to be caught by it. A real one would open the old database, drain it into
 * the new one and delete it, which is perhaps thirty lines and worth every one of them there.
 */
const DATABASE = 'collabedit'
const STORE = 'unacknowledged'

/**
 * The undo stacks, kept per document (CP25).
 *
 * A second store rather than a second database: they share a lifetime (both belong to "what this
 * browser knows about a document that is not on the server yet") and an upgrade path.
 */
const UNDO_STORE = 'undo'

/**
 * Comments written without a connection (CP27).
 *
 * The buffer above holds CRDT operations, which travel the WebSocket. A comment travels REST, so
 * before CP27 one written offline simply disappeared — the README listed it as a limit of CP14.
 */
const COMMENT_STORE = 'comments'
const VERSION = 3

/**
 * A comment waiting for a connection (CP27).
 *
 * Exported although nothing imports the name: it is the parameter of `queueComment` and the
 * element type of what `pendingComments` returns, both of which are part of `offlineStore`'s
 * surface. `CommentsPanel` builds one as an object literal and reads the result field by field, so
 * it never writes the name — that is a property of the call sites, not of the contract.
 */
export interface QueuedComment {
  clientId: string
  documentId: string
  /** A new conversation, or a reply to an existing one. */
  kind: 'thread' | 'reply'
  threadId?: string
  quotedText?: string
  /**
   * Where in the text the comment was about, as two CHARACTER IDENTIFIERS (`from` inclusive,
   * `to` inclusive — the same shape `proposeDeletion` sends).
   *
   * This is the half of CP27 that did not land. A comment written offline was queued with its
   * quoted TEXT and nothing else, so when the outbox drained, the thread was created and no
   * `comment` mark was ever placed — `flush` did not even keep the identifier the server returned
   * (`void created`). `thread.anchored` is computed on the server from that mark, so the comment
   * came back marked as detached and the panel told the user "the anchored text was deleted"
   * about text nobody had touched. Reporting a change to the document that did not happen is a
   * worse failure than reporting nothing.
   *
   * Identifiers rather than offsets, for the same reason cursors, marks and proposals all use
   * them: an offset written down while offline is stale the moment anybody types before it, and
   * the resulting failure is a comment attached to words nobody commented on.
   *
   * Optional, because a comment can be written with nothing selected. Absent means "there was
   * never an anchor", which the panel distinguishes from "there was one and it is gone" — those
   * are different things to tell somebody.
   */
  anchor?: { from: { replicaId: number; lamport: number }; to: { replicaId: number; lamport: number } }
  body: string
  queuedAt: number
}

interface StoredEntry {
  key: string
  documentId: string
  op: WireOp
  queuedAt: number
}

function openDatabase(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(DATABASE, VERSION)
    request.onupgradeneeded = () => {
      const database = request.result
      if (!database.objectStoreNames.contains(STORE)) {
        const store = database.createObjectStore(STORE, { keyPath: 'key' })
        store.createIndex('byDocument', 'documentId')
      }
      // Created without touching the store above: a browser that already has a buffer from
      // before CP25 must keep it. An upgrade that dropped unacknowledged operations would lose
      // exactly the work this whole mechanism exists to protect.
      if (!database.objectStoreNames.contains(UNDO_STORE)) {
        database.createObjectStore(UNDO_STORE, { keyPath: 'documentId' })
      }
      if (!database.objectStoreNames.contains(COMMENT_STORE)) {
        const store = database.createObjectStore(COMMENT_STORE, { keyPath: 'clientId' })
        store.createIndex('byDocument', 'documentId')
      }
    }
    request.onsuccess = () => resolve(request.result)
    request.onerror = () => reject(request.error)
    // A second tab holding the OLD version open blocks the upgrade, and without this the promise
    // never settles: no error fires, `onsuccess` never comes, and every caller — that is, every
    // keystroke's `remember` — waits for ever on a promise nothing will resolve. Rejecting turns a
    // permanent hang into the one thing the transport already knows how to survive, a storage
    // failure it can report through OFFLINE_BUFFER_UNAVAILABLE.
    request.onblocked = () =>
      reject(new Error('another tab is holding an older version of the buffer open'))
  })
}

/**
 * The one open connection, shared.
 *
 * ## Why this is not a micro-optimisation
 *
 * `transact` used to call `openDatabase` on every single call. On the `remember` path that is
 * **once per keystroke**: a full `indexedDB.open` — version check, connection handshake, and on
 * the first call of a session an upgrade transaction — to write one operation of about a hundred
 * bytes. `clear` was worse by a different order: one `getAll` and then a separate open per ROW,
 * so a document with a few thousand buffered operations after a long offline session performed a
 * few thousand database opens, on the `RESYNC_REQUIRED` path, which is the moment the user is
 * already being told their work could not be merged.
 *
 * The connection was also **leaked on every failure**. `transaction.oncomplete = () => close`
 * fires only when a transaction completes; an aborted or errored one never closes its connection,
 * and the failure that matters most here — quota exceeded — is precisely the one that repeats. So
 * the arrangement closed connections exactly when closing them was unnecessary and kept them
 * exactly when it was not.
 *
 * One cached promise fixes both, and it is the shape IndexedDB is designed for: a connection is
 * meant to be long-lived and a transaction is the unit of work.
 *
 * ## What invalidates it
 *
 * The cache is dropped whenever the connection can no longer serve a transaction — the open
 * failed, another tab is upgrading the schema (`versionchange`), or the browser closed it
 * underneath us (`close`, which fires on a forced close or on storage being evicted). The next
 * caller then opens a fresh one rather than inheriting a dead handle, which was the other way this
 * could hang for the rest of a session.
 */
let connection: Promise<IDBDatabase> | null = null

function database(): Promise<IDBDatabase> {
  if (connection !== null) return connection
  const opening = openDatabase().then(
    (open) => {
      const forget = () => {
        // Only if it is still OURS. A slow failure on a connection that has already been replaced
        // would otherwise throw away the live one, and the next call would open a third.
        if (connection === opening) connection = null
      }
      open.onclose = forget
      open.onversionchange = () => {
        // Another tab wants to upgrade the schema. Holding the connection open blocks it for ever
        // — that is the `onblocked` case above, seen from the other side — so we get out of the
        // way. Anything in flight fails, and the callers already treat a storage failure as
        // survivable.
        open.close()
        forget()
      }
      return open
    },
    (error: unknown) => {
      if (connection === opening) connection = null
      throw error
    },
  )
  connection = opening
  return opening
}

function transact<T>(
  mode: IDBTransactionMode,
  action: (store: IDBObjectStore) => IDBRequest<T>,
  storeName: string = STORE,
): Promise<T> {
  return database().then(
    (open) =>
      new Promise<T>((resolve, reject) => {
        const transaction = open.transaction(storeName, mode)
        const request = action(transaction.objectStore(storeName))
        request.onsuccess = () => resolve(request.result)
        request.onerror = () => reject(request.error)
        // A transaction can abort WITHOUT the request's own `onerror` firing — a quota that ran
        // out mid-commit is the case that matters, since it is the one failure this buffer exists
        // to survive. Without this handler the promise simply never settled: `remember`'s
        // `.catch` never ran, `reportStorageFailure` was never called, and the user was never told
        // their local backup had stopped working. A silent hang where the whole design calls for
        // a visible warning.
        transaction.onabort = () =>
          reject(transaction.error ?? new Error(`the ${storeName} transaction was aborted`))
      }),
  )
}

/**
 * A transaction whose result is that it FINISHED, rather than the answer to one request.
 *
 * Needed for the cursor sweep in `clear`: there the requests are a stream of them, and the thing
 * worth waiting for is the commit.
 */
function transactUntilComplete(
  mode: IDBTransactionMode,
  action: (store: IDBObjectStore) => void,
  storeName: string = STORE,
): Promise<void> {
  return database().then(
    (open) =>
      new Promise<void>((resolve, reject) => {
        const transaction = open.transaction(storeName, mode)
        action(transaction.objectStore(storeName))
        transaction.oncomplete = () => resolve()
        transaction.onabort = () =>
          reject(transaction.error ?? new Error(`the ${storeName} transaction was aborted`))
        transaction.onerror = () =>
          reject(transaction.error ?? new Error(`the ${storeName} transaction failed`))
      }),
  )
}

export const offlineStore = {
  async remember(documentId: string, op: Op): Promise<void> {
    const entry: StoredEntry = {
      key: `${documentId}:${op.id.replicaId}@${op.id.lamport}`,
      documentId,
      op: encodeOp(op),
      queuedAt: Date.now(),
    }
    await transact('readwrite', (store) => store.put(entry))
  },

  async forget(documentId: string, op: Op): Promise<void> {
    await transact('readwrite', (store) =>
      store.delete(`${documentId}:${op.id.replicaId}@${op.id.lamport}`),
    )
  },

  /**
   * The operations remembered for a document, oldest first.
   *
   * The order does not matter for correctness (merging is commutative — the CRDT decision), but it does
   * matter for legible logs and for what you see while debugging.
   */
  async pending(documentId: string): Promise<Op[]> {
    const all = await transact<StoredEntry[]>('readonly', (store) =>
      store.index('byDocument').getAll(documentId),
    )
    return all.sort((a, b) => a.queuedAt - b.queuedAt).map((entry) => decodeOp(entry.op))
  },

  /**
   * Clears a document's buffer.
   *
   * Used only on `RESYNC_REQUIRED`: operations the server can no longer merge have no
   * reason to occupy space and retry forever. Their content is rescued separately at that point, as
   * a recovered draft.
   */
  async clear(documentId: string): Promise<void> {
    // ONE transaction with a cursor, not a read followed by a delete per row.
    //
    // The previous version did a `getAll` and then a separate `transact` — that is, a separate
    // `indexedDB.open` — for every entry it found. After a long offline session that is thousands
    // of database opens, and the only caller is the `RESYNC_REQUIRED` path: the moment
    // the user has just been told their changes could not be merged, and the moment the recovery
    // save is racing to finish before they close the tab.
    //
    // A cursor over the index also makes the sweep atomic: the old shape could delete half the
    // buffer, fail, and leave the rest to be retried on the next load — operations the server has
    // already refused for good, restored into the buffer on every future page load.
    await transactUntilComplete('readwrite', (store) => {
      const scan = store.index('byDocument').openCursor(IDBKeyRange.only(documentId))
      scan.onsuccess = () => {
        const cursor = scan.result
        if (cursor === null) return
        cursor.delete()
        cursor.continue()
      }
    })
  },

  /**
   * Saves the undo stacks for a document (CP25).
   *
   * The stacks used to live in the tab's memory and die with a reload, which the README listed as
   * a limit: reloading a page cost you the ability to undo work you could still see on screen.
   * Storing them is cheap because the entries are already made of things that outlive a session —
   * character identifiers and text, never positions.
   */
  async saveUndo(documentId: string, state: unknown): Promise<void> {
    await transact('readwrite', (store) => store.put({ documentId, state }), UNDO_STORE)
  },

  /**
   * Remembers a comment that could not be posted (CP27).
   *
   * The `clientId` is generated here and travels with every retry, so the server can recognise
   * an attempt that already arrived. That is the whole mechanism: what an offline comment needs
   * is not merging but exactly-once, and exactly-once is a key rather than a data structure
   * (held).
   */
  async queueComment(entry: QueuedComment): Promise<void> {
    await transact('readwrite', (store) => store.put(entry), COMMENT_STORE)
  },

  async pendingComments(documentId: string): Promise<QueuedComment[]> {
    const all = await transact<QueuedComment[]>(
      'readonly',
      (store) => store.index('byDocument').getAll(documentId),
      COMMENT_STORE,
    )
    return all.sort((a, b) => a.queuedAt - b.queuedAt)
  },

  async forgetComment(clientId: string): Promise<void> {
    await transact('readwrite', (store) => store.delete(clientId), COMMENT_STORE)
  },

  async loadUndo<T>(documentId: string): Promise<T | null> {
    const row = await transact<{ documentId: string; state: T } | undefined>(
      'readonly',
      (store) => store.get(documentId),
      UNDO_STORE,
    )
    return row?.state ?? null
  },
}
