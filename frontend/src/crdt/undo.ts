import type { RgaDocument } from './document'
import type { Id, Op } from './types'

/**
 * Cofanie i ponawianie zmian — świadome CRDT.
 *
 * Historia CodeMirrora jest wyłączona ([ADR-0006](../../docs/adr/0006-warstwa-edytora.md)),
 * bo działa na tekście: cofnięcie zdejmowałoby również zmiany innych osób, które wpadły
 * w międzyczasie. W edytorze współdzielonym to nie jest niedogodność, tylko kasowanie
 * cudzej pracy.
 *
 * Cofanie musi więc działać na **własnych operacjach**, i to jest cała trudność:
 * w CRDT nie da się „odwrócić" operacji. Usunięty znak ma tombstone i nie wraca do życia;
 * przywrócenie tekstu oznacza wstawienie **nowych** znaków o nowych identyfikatorach.
 * Widoczny efekt jest ten sam, tożsamość znaków — nie. Tak samo robi to Yjs.
 *
 * ### Kotwice, nie pozycje
 *
 * Wpis w stosie pamięta **identyfikator znaku**, nie offset. Offset zapisany przy edycji
 * jest nieaktualny w chwili cofania, bo inni w międzyczasie pisali. Identyfikator przesuwa
 * się razem z tekstem.
 *
 * ### Czego ta implementacja NIE robi
 *
 * Jeśli kotwica została w międzyczasie usunięta przez kogoś innego, wpis jest **pomijany**,
 * a nie zgadywany. Wstawienie tekstu „mniej więcej tam" byłoby gorsze niż nierobienie nic:
 * użytkownik zobaczyłby swój tekst w przypadkowym miejscu i nie wiedziałby dlaczego.
 */

interface UndoableInsert {
  kind: 'insert'
  /** Pierwszy wstawiony znak — kotwica do odnalezienia fragmentu przy cofaniu. */
  first: Id
  /** Rośnie przy scalaniu kolejnych naciśnięć klawisza w jeden krok. */
  length: number
}

interface UndoableDelete {
  kind: 'delete'
  /** Znak bezpośrednio PRZED usuniętym fragmentem; null gdy usuwano od początku dokumentu. */
  after: Id | null
  text: string
}

type UndoEntry = UndoableInsert | UndoableDelete

export class UndoManager {
  private readonly undoStack: UndoEntry[] = []
  private readonly redoStack: UndoEntry[] = []
  private applying = false

  /**
   * Głębokość stosu.
   *
   * Ograniczona, bo stos rośnie z każdą edycją, a dokument redagowany godzinami
   * trzymałby w pamięci całą sesję. 200 kroków to wyraźnie więcej, niż ktokolwiek
   * cofa w praktyce.
   */
  private static readonly LIMIT = 200

  /**
   * Okno scalania kolejnych naciśnięć klawisza w jeden krok cofania.
   *
   * Bez tego każda litera jest osobnym wpisem i Ctrl+Z zdejmuje po jednym znaku — zachowanie,
   * którego nie ma żaden edytor i które użytkownik odbiera jako zepsute. Sekunda przerwy
   * kończy grupę, bo mniej więcej tyle trwa pauza między „myślami" przy pisaniu.
   */
  private static readonly COALESCE_WINDOW_MS = 1000

  private lastRecordedAt = 0

  private readonly document: RgaDocument

  constructor(document: RgaDocument) {
    this.document = document
  }

  get canUndo(): boolean {
    return this.undoStack.length > 0
  }

  get canRedo(): boolean {
    return this.redoStack.length > 0
  }

  /**
   * Zapamiętuje zmianę lokalną.
   *
   * Wywoływane WYŁĄCZNIE dla własnych operacji. Zapamiętanie cudzych oznaczałoby, że
   * Ctrl+Z cofa czyjąś pracę — dokładnie to, przed czym broni wyłączenie historii
   * CodeMirrora.
   */
  recordInsert(first: Id, length: number): void {
    if (this.applying) return

    // Scalanie z poprzednim wpisem, gdy to jest kontynuacja tego samego pisania:
    // ta sama replika, bezpośrednio kolejne wartości zegara, krótka przerwa.
    const previous = this.undoStack.at(-1)
    if (
      previous !== undefined &&
      previous.kind === 'insert' &&
      this.withinWindow() &&
      previous.first.replicaId === first.replicaId &&
      previous.first.lamport + previous.length === first.lamport
    ) {
      previous.length += length
      this.lastRecordedAt = Date.now()
      this.redoStack.length = 0
      return
    }

    this.push({ kind: 'insert', first, length })
  }

  recordDelete(after: Id | null, text: string): void {
    if (this.applying) return

    // Analogicznie dla kasowania backspace'em: kolejne znaki znikają od prawej do lewej,
    // więc nowy fragment doklejamy PRZED poprzednio zapamiętany, a kotwicą staje się
    // ta z nowszego usunięcia.
    const previous = this.undoStack.at(-1)
    if (previous !== undefined && previous.kind === 'delete' && this.withinWindow()) {
      previous.text = text + previous.text
      previous.after = after
      this.lastRecordedAt = Date.now()
      this.redoStack.length = 0
      return
    }

    this.push({ kind: 'delete', after, text })
  }

  private withinWindow(): boolean {
    return Date.now() - this.lastRecordedAt < UndoManager.COALESCE_WINDOW_MS
  }

  private push(entry: UndoEntry): void {
    this.lastRecordedAt = Date.now()
    this.undoStack.push(entry)
    if (this.undoStack.length > UndoManager.LIMIT) {
      this.undoStack.shift()
    }
    // Nowa zmiana unieważnia gałąź ponawiania — standardowe zachowanie każdego edytora.
    this.redoStack.length = 0
  }

  undo(): Op[] {
    return this.step(this.undoStack, this.redoStack)
  }

  redo(): Op[] {
    return this.step(this.redoStack, this.undoStack)
  }

  private step(from: UndoEntry[], to: UndoEntry[]): Op[] {
    const entry = from.pop()
    if (entry === undefined) return []

    this.applying = true
    try {
      const result = entry.kind === 'insert' ? this.revertInsert(entry) : this.revertDelete(entry)
      if (result === null) {
        // Kotwica zniknęła — pomijamy wpis i próbujemy następny. Bez tego jeden
        // nieodwracalny krok blokowałby cofanie wszystkiego, co pod nim.
        return this.step(from, to)
      }
      to.push(result.inverse)
      return result.ops
    } finally {
      this.applying = false
    }
  }

  /** Cofnięcie wstawienia = usunięcie tych znaków, które jeszcze żyją. */
  private revertInsert(entry: UndoableInsert): { ops: Op[]; inverse: UndoEntry } | null {
    const start = this.document.visibleIndexOf(entry.first)
    if (start === null) {
      return null
    }
    // Ktoś mógł już usunąć część tego fragmentu — cofamy tylko to, co zostało.
    const available = Math.min(entry.length, this.document.visibleLength - start)
    if (available <= 0) {
      return null
    }

    const text = this.document.text.slice(start, start + available)
    const anchor = start > 0 ? this.document.idAtVisible(start - 1) : null
    const op = this.document.delete(start, available)
    return { ops: [op], inverse: { kind: 'delete', after: anchor, text } }
  }

  /** Cofnięcie usunięcia = wstawienie tekstu z powrotem, jako NOWYCH znaków. */
  private revertDelete(entry: UndoableDelete): { ops: Op[]; inverse: UndoEntry } | null {
    let position: number
    if (entry.after === null) {
      position = 0
    } else {
      const anchor = this.document.visibleIndexOf(entry.after)
      if (anchor === null) {
        return null
      }
      position = anchor + 1
    }

    const op = this.document.insert(position, entry.text)
    return { ops: [op], inverse: { kind: 'insert', first: op.id, length: entry.text.length } }
  }
}
