import { encodeOp, decodeOp, type WireOp } from '../crdt/codec'
import type { Op } from '../crdt/types'

/**
 * Trwały bufor operacji niepotwierdzonych zapisem.
 *
 * <p>Bufor w pamięci wystarcza na utratę sieci. Nie wystarcza na to, co użytkownik robi
 * naprawdę: zamyka kartę, usypia laptopa, przeładowuje stronę bo „coś się zawiesiło".
 * We wszystkich tych przypadkach operacje wysłane, ale niepotwierdzone, przepadłyby —
 * mimo że użytkownik widział je na ekranie.
 *
 * Dlatego IndexedDB, a nie `localStorage`: ten drugi jest synchroniczny i blokuje wątek
 * interfejsu przy każdym zapisie, czyli przy każdym naciśnięciu klawisza. Na dokumencie
 * z długim buforem byłoby to odczuwalne dokładnie wtedy, gdy sieć nie działa i wszystko
 * i tak jest irytujące.
 *
 * Wpis znika dopiero po potwierdzeniu TRWAŁEGO ZAPISU przez serwer (ADR-0003) — nigdy
 * po samym rozesłaniu.
 */

const DATABASE = 'inkline'
const STORE = 'unacknowledged'
const VERSION = 1

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
    }
    request.onsuccess = () => resolve(request.result)
    request.onerror = () => reject(request.error)
  })
}

function transact<T>(
  mode: IDBTransactionMode,
  action: (store: IDBObjectStore) => IDBRequest<T>,
): Promise<T> {
  return openDatabase().then(
    (database) =>
      new Promise<T>((resolve, reject) => {
        const transaction = database.transaction(STORE, mode)
        const request = action(transaction.objectStore(STORE))
        request.onsuccess = () => resolve(request.result)
        request.onerror = () => reject(request.error)
        transaction.oncomplete = () => database.close()
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
   * Operacje zapamiętane dla dokumentu, od najstarszej.
   *
   * Kolejność nie ma znaczenia dla poprawności (scalanie jest przemienne — ADR-0001),
   * ale ma dla czytelności logów i dla tego, co widać przy debugowaniu.
   */
  async pending(documentId: string): Promise<Op[]> {
    const all = await transact<StoredEntry[]>('readonly', (store) =>
      store.index('byDocument').getAll(documentId),
    )
    return all.sort((a, b) => a.queuedAt - b.queuedAt).map((entry) => decodeOp(entry.op))
  },

  /**
   * Czyści bufor dokumentu.
   *
   * Używane wyłącznie przy `RESYNC_REQUIRED` (ADR-0005): operacje, których serwer nie
   * potrafi już scalić, nie mają po co zajmować miejsca i próbować w nieskończoność.
   * Ich treść jest wtedy ratowana osobno, jako odzyskany szkic.
   */
  async clear(documentId: string): Promise<void> {
    const all = await transact<StoredEntry[]>('readonly', (store) =>
      store.index('byDocument').getAll(documentId),
    )
    for (const entry of all) {
      await transact('readwrite', (store) => store.delete(entry.key))
    }
  },
}
