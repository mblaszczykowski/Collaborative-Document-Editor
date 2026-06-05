import { Block } from './block'
import {
  compareIds,
  id,
  idEquals,
  idPlus,
  opMaxLamport,
  opSpan,
  type ApplyResult,
  type DeleteOp,
  type Id,
  type InsertOp,
  type Op,
  type Range,
} from './types'

interface Locator {
  blockIndex: number
  offset: number
}

/**
 * Replika dokumentu po stronie klienta.
 *
 * Zachowanie musi być IDENTYCZNE z `RgaDocument` w Javie — nie „równoważne", tylko takie
 * samo znak w znak, łącznie ze znanym ograniczeniem RGA (przeplot przy pisaniu wstecz).
 * Pilnuje tego korpus wektorów konformancyjnych.
 *
 * INDEKS JEST NAIWNY (skanowanie liniowe), w przeciwieństwie do wersji serwerowej, która
 * używa treapa. To jest świadoma decyzja, a nie przeoczenie: po stronie serwera zmierzyliśmy
 * benchmarkiem, że skanowanie liniowe boli, i dopiero wtedy je zastąpiliśmy. Po stronie
 * klienta odpowiedniego pomiaru (opóźnienie pisania w przeglądarce przy dokumencie
 * o 100 tys. znaków) jeszcze nie ma — więc nie optymalizujemy w ciemno.
 * DO ZMIERZENIA W CP8; jeśli wyjdzie źle, port treapa jest mechaniczny.
 */
export class RgaDocument {
  private blocks: Block[] = []
  /** Zegar Lamporta — wartość, którą dostanie następna operacja lokalna. */
  private clock = 0
  /** Najwyższy zastosowany lamport per replika. Do GC i resynchronizacji, NIE do dedup. */
  private readonly maxSeen = new Map<number, number>()
  /** Operacje, których nie da się jeszcze zastosować (brakuje originu albo celu usunięcia). */
  private pending: Op[] = []

  constructor(readonly replicaId: number) {}

  // ------------------------------------------------------------------
  // Operacje lokalne
  // ------------------------------------------------------------------

  insert(visibleIndex: number, text: string): InsertOp {
    const length = this.visibleLength
    if (visibleIndex < 0 || visibleIndex > length) {
      throw new RangeError(`pozycja ${visibleIndex} poza tekstem o długości ${length}`)
    }
    // Origin to widoczny poprzednik punktu wstawienia. Gdy przed pozycją stoją tombstone'y,
    // nowy tekst ląduje przed nimi — zachowanie standardowe dla RGA.
    const originLeft = visibleIndex === 0 ? null : this.idAtVisible(visibleIndex - 1)

    const op: InsertOp = { kind: 'insert', id: id(this.replicaId, this.clock), originLeft, text }
    this.clock += opSpan(op)
    this.integrate(op)
    this.observeVersion(op)
    return op
  }

  delete(visibleIndex: number, count: number): DeleteOp {
    if (count <= 0) {
      throw new RangeError('liczba znaków do usunięcia musi być dodatnia')
    }
    const length = this.visibleLength
    if (visibleIndex < 0 || visibleIndex + count > length) {
      throw new RangeError(
        `zakres [${visibleIndex}, ${visibleIndex + count}) poza tekstem o długości ${length}`,
      )
    }

    const targets: Range[] = []
    let remaining = count
    while (remaining > 0) {
      // Po oznaczeniu bloku tombstone'em przestaje on być widoczny, więc visibleIndex
      // wskazuje już na kolejny znak do usunięcia — dlatego nie jest zwiększany.
      const found = this.locateVisible(visibleIndex)!
      let index = found.blockIndex
      if (found.offset > 0) {
        this.splitBlock(index, found.offset)
        index++
      }
      let target = this.blocks[index]
      if (target.length > remaining) {
        this.splitBlock(index, remaining)
        target = this.blocks[index]
      }
      target.deleted = true
      targets.push({ start: target.start, length: target.length })
      remaining -= target.length
    }

    const op: DeleteOp = { kind: 'delete', id: id(this.replicaId, this.clock), targets }
    this.clock += opSpan(op)
    this.observeVersion(op)
    return op
  }

  // ------------------------------------------------------------------
  // Operacje zdalne
  // ------------------------------------------------------------------

  apply(op: Op): ApplyResult {
    // Zegar podbijamy nawet dla operacji zbuforowanej: reguła Lamporta dotyczy odbioru,
    // nie zastosowania.
    this.clock = Math.max(this.clock, opMaxLamport(op) + 1)

    if (this.isAlreadyKnown(op)) {
      return 'duplicate'
    }
    if (this.tryApply(op)) {
      this.observeVersion(op)
      this.drainPending()
      return 'applied'
    }
    this.pending.push(op)
    return 'buffered'
  }

  applyAll(ops: readonly Op[]): void {
    for (const op of ops) this.apply(op)
  }

  private isAlreadyKnown(op: Op): boolean {
    if (op.kind === 'insert') {
      return this.locateId(op.id) !== null
    }
    return this.isEntirelyDeleted(op)
  }

  private isEntirelyDeleted(op: DeleteOp): boolean {
    for (const range of op.targets) {
      let cursor = range.start.lamport
      const end = cursor + range.length
      while (cursor < end) {
        const found = this.locateId(id(range.start.replicaId, cursor))
        if (found === null) return false
        const block = this.blocks[found.blockIndex]
        if (!block.deleted) return false
        cursor += block.length - found.offset
      }
    }
    return true
  }

  private tryApply(op: Op): boolean {
    if (op.kind === 'insert') {
      if (op.originLeft !== null && this.locateId(op.originLeft) === null) return false
      this.integrate(op)
      return true
    }
    // Wszystko albo nic — patrz komentarz przy tryApplyDelete w wersji Javy.
    for (const range of op.targets) {
      if (!this.isRangeFullyPresent(range)) return false
    }
    for (const range of op.targets) {
      this.markRangeDeleted(range)
    }
    return true
  }

  private isRangeFullyPresent(range: Range): boolean {
    let cursor = range.start.lamport
    const end = cursor + range.length
    while (cursor < end) {
      const found = this.locateId(id(range.start.replicaId, cursor))
      if (found === null) return false
      cursor += this.blocks[found.blockIndex].length - found.offset
    }
    return true
  }

  private markRangeDeleted(range: Range): void {
    let cursor = range.start.lamport
    const end = cursor + range.length
    while (cursor < end) {
      const found = this.locateId(id(range.start.replicaId, cursor))!
      let index = found.blockIndex
      if (found.offset > 0) {
        this.splitBlock(index, found.offset)
        index++
      }
      let block = this.blocks[index]
      const coverable = Math.min(block.length, end - cursor)
      if (coverable < block.length) {
        this.splitBlock(index, coverable)
        block = this.blocks[index]
      }
      block.deleted = true
      cursor += block.length
    }
  }

  // ------------------------------------------------------------------
  // Serce algorytmu
  // ------------------------------------------------------------------

  /**
   * Reguła RGA: znajdź origin, idź w prawo dopóki napotykane bloki mają WYŻSZY znacznik
   * czasu niż wstawiany, wstaw w pierwszym miejscu o niższym.
   *
   * Skan jest gruboziarnisty (po blokach, nie po znakach) i jest to poprawne, bo znaki
   * w bloku mają rosnące lamporty: jeśli pierwszy znak bloku ma wyższy znacznik niż
   * wstawiany, to wszystkie pozostałe też.
   */
  private integrate(op: InsertOp): void {
    let position: number
    if (op.originLeft === null) {
      position = 0
    } else {
      const found = this.locateId(op.originLeft)!
      const originBlock = this.blocks[found.blockIndex]
      // Origin musi być ostatnim znakiem swojego bloku, żeby "zaraz za originem"
      // było granicą bloków.
      if (found.offset < originBlock.length - 1) {
        this.splitBlock(found.blockIndex, found.offset + 1)
      }
      position = found.blockIndex + 1
    }

    while (position < this.blocks.length && compareIds(this.blocks[position].start, op.id) > 0) {
      position++
    }

    const block = new Block(op.id, op.text, op.originLeft, false)

    // Szybka ścieżka zwykłego pisania.
    if (position > 0) {
      const previous = this.blocks[position - 1]
      if (previous.canAppend(block)) {
        this.blocks[position - 1] = previous.append(block.text)
        return
      }
    }
    this.blocks.splice(position, 0, block)
  }

  private splitBlock(index: number, offset: number): void {
    const [left, right] = this.blocks[index].splitAt(offset)
    this.blocks.splice(index, 1, left, right)
  }

  private drainPending(): void {
    let progress = true
    while (progress && this.pending.length > 0) {
      progress = false
      const remaining: Op[] = []
      for (const op of this.pending) {
        if (this.tryApply(op)) {
          this.observeVersion(op)
          progress = true
        } else {
          remaining.push(op)
        }
      }
      this.pending = remaining
    }
  }

  // ------------------------------------------------------------------
  // Odczyt stanu
  // ------------------------------------------------------------------

  get text(): string {
    let result = ''
    for (const block of this.blocks) {
      if (!block.deleted) result += block.text
    }
    return result
  }

  get visibleLength(): number {
    let total = 0
    for (const block of this.blocks) total += block.visibleLength
    return total
  }

  get tombstoneCount(): number {
    let total = 0
    for (const block of this.blocks) total += block.length - block.visibleLength
    return total
  }

  get blockCount(): number {
    return this.blocks.length
  }

  get pendingCount(): number {
    return this.pending.length
  }

  get version(): Map<number, number> {
    return new Map(this.maxSeen)
  }

  /** Identyfikator znaku na podanej pozycji widocznego tekstu — pozycja kursora (ADR-0006). */
  idAtVisible(visibleIndex: number): Id {
    const found = this.locateVisible(visibleIndex)
    if (found === null) {
      throw new RangeError(`brak widocznego znaku na pozycji ${visibleIndex}`)
    }
    return this.blocks[found.blockIndex].idAt(found.offset)
  }

  /**
   * Odwrotność `idAtVisible` — pozycja w tekście dla znaku o podanym identyfikatorze.
   *
   * Potrzebne do rysowania kursorów innych użytkowników: presence niesie identyfikatory
   * (bo te przesuwają się razem z tekstem), a CodeMirror rysuje po offsetach.
   * Zwraca null, gdy znak jest tombstone'em albo go nie ma — kursor osoby, której znak
   * ktoś właśnie skasował, po prostu znika, zamiast wskazywać przypadkowe miejsce.
   */
  visibleIndexOf(target: Id): number | null {
    let seen = 0
    for (const block of this.blocks) {
      if (block.containsId(target)) {
        return block.deleted ? null : seen + block.offsetOf(target)
      }
      seen += block.visibleLength
    }
    return null
  }

  /** Kopia bloków w kolejności dokumentu — do diagnostyki i porównań z wersją w Javie. */
  describeBlocks(): string[] {
    return this.blocks.map((block) => block.describe())
  }

  // ------------------------------------------------------------------

  private locateVisible(visibleIndex: number): Locator | null {
    if (visibleIndex < 0) return null
    let seen = 0
    for (let i = 0; i < this.blocks.length; i++) {
      const visible = this.blocks[i].visibleLength
      if (visible > 0 && visibleIndex < seen + visible) {
        return { blockIndex: i, offset: visibleIndex - seen }
      }
      seen += visible
    }
    return null
  }

  private locateId(target: Id): Locator | null {
    for (let i = 0; i < this.blocks.length; i++) {
      if (this.blocks[i].containsId(target)) {
        return { blockIndex: i, offset: this.blocks[i].offsetOf(target) }
      }
    }
    return null
  }

  private observeVersion(op: Op): void {
    const current = this.maxSeen.get(op.id.replicaId)
    const candidate = opMaxLamport(op)
    if (current === undefined || candidate > current) {
      this.maxSeen.set(op.id.replicaId, candidate)
    }
  }
}

export { idEquals, idPlus, compareIds }
