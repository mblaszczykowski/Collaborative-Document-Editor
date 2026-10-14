import { Block } from './block'
import type { DecodedSnapshot } from './snapshot'
import { MarkSet, type AnchorPositions, type Span } from './marks'
import {
  anchorAfter,
  anchorBefore,
  compareIds,
  id,
  idToString,
  isMarkOp,
  markExpandsAtEnd,
  opMaxLamport,
  opSpan,
  type AddMarkOp,
  type Anchor,
  type ApplyResult,
  type DeleteOp,
  type Id,
  type InsertOp,
  type Op,
  type Range,
  type RemoveMarkOp,
} from './types'

interface Locator {
  blockIndex: number
  offset: number
}

function requireNonEmptyRange(from: number, to: number): void {
  if (from >= to) {
    throw new RangeError(`an empty formatting range [${from}, ${to})`)
  }
}

export class RgaDocument {
  private blocks: Block[] = []
  private clock = 0
  private readonly maxSeen = new Map<number, number>()
  private pending = new Map<string, Op>()

  private lastDeletedCharacter: Id | undefined

  get lastDeletedId(): Id | undefined { return this.lastDeletedCharacter }

  private readonly deleteIds = new Set<string>()

  private readonly marks = new MarkSet()

  private replicaIdValue: number

  private locallyGenerated = 0

  constructor(replicaId: number) {
    this.replicaIdValue = replicaId
  }

  get replicaId(): number {
    return this.replicaIdValue
  }

  get hasGeneratedOperations(): boolean {
    return this.locallyGenerated > 0
  }

  adoptReplicaId(replicaId: number): void {
    if (this.locallyGenerated > 0) {
      throw new Error(
        `attempt to change the replica identifier after generating ${this.locallyGenerated} operations`,
      )
    }
    this.replicaIdValue = replicaId
  }

  restoreFrom(snapshot: DecodedSnapshot): void {
    this.blocks = snapshot.blocks.map(block => new Block(block.start, block.text, block.originLeft, block.deleted))
    this.deleteIds.clear()
    for (const target of snapshot.deleteIds ?? []) this.deleteIds.add(idToString(target))
    this.clock = snapshot.clock
    this.maxSeen.clear()
    for (const [replicaId, lamport] of snapshot.maxSeen) {
      this.maxSeen.set(replicaId, lamport)
    }
    this.marks.clear()
    for (const mark of snapshot.marks) {
      this.marks.apply(mark)
    }
    this.pending.clear()
  }

  cachedState(): DecodedSnapshot {
    return { formatVersion: 5, clock: this.clock, maxSeen: this.version,
      blocks: this.blocks.map(block => new Block(block.start, block.text, block.originLeft, block.deleted)),
      marks: this.marks.all(), gcWatermark: new Map(),
      deleteIds: [...this.deleteIds].map(key => { const [replicaId, lamport] = key.split('@').map(Number); return { replicaId, lamport } }) }
  }

  insert(visibleIndex: number, text: string): InsertOp {
    const length = this.visibleLength
    if (visibleIndex < 0 || visibleIndex > length) {
      throw new RangeError(`position ${visibleIndex} is outside text of length ${length}`)
    }
    const originLeft = visibleIndex === 0 ? null : this.idAtVisible(visibleIndex - 1)

    const op: InsertOp = { kind: 'insert', id: id(this.replicaIdValue, this.clock), originLeft, text }
    this.clock += opSpan(op)
    this.locallyGenerated++
    this.integrate(op)
    this.observeVersion(op)
    return op
  }

  delete(visibleIndex: number, count: number): DeleteOp {
    if (count <= 0) {
      throw new RangeError('the number of characters to delete must be positive')
    }
    const length = this.visibleLength
    if (visibleIndex < 0 || visibleIndex + count > length) {
      throw new RangeError(
        `range [${visibleIndex}, ${visibleIndex + count}) is outside text of length ${length}`,
      )
    }

    const targets: Range[] = []
    let remaining = count
    while (remaining > 0) {
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

    const last = targets.at(-1)!
    this.lastDeletedCharacter = id(last.start.replicaId, last.start.lamport + last.length - 1)
    const op: DeleteOp = { kind: 'delete', id: id(this.replicaIdValue, this.clock), targets }
    this.clock += opSpan(op)
    this.locallyGenerated++
    this.deleteIds.add(idToString(op.id))
    this.observeVersion(op)
    return op
  }

  addMark(from: number, to: number, type: string, value: string | null): AddMarkOp {
    requireNonEmptyRange(from, to)
    const op: AddMarkOp = {
      kind: 'addMark',
      id: id(this.replicaId, this.clock),
      type,
      value,
      start: this.anchorStart(from),
      end: this.anchorEnd(to, type),
    }
    this.clock += opSpan(op)
    this.locallyGenerated++
    this.marks.apply(op)
    this.observeVersion(op)
    return op
  }

  removeMark(from: number, to: number, type: string): RemoveMarkOp {
    requireNonEmptyRange(from, to)
    const op: RemoveMarkOp = {
      kind: 'removeMark',
      id: id(this.replicaId, this.clock),
      type,
      start: this.anchorStart(from),
      end: this.anchorEnd(to, type),
    }
    this.clock += opSpan(op)
    this.locallyGenerated++
    this.marks.apply(op)
    this.observeVersion(op)
    return op
  }

  private anchorStart(from: number): Anchor {
    const length = this.visibleLength
    if (from < 0 || from >= length) {
      throw new RangeError(`range start ${from} is outside text of length ${length}`)
    }
    return anchorBefore(this.idAtVisible(from))
  }

  private anchorEnd(to: number, type: string): Anchor {
    const length = this.visibleLength
    if (to <= 0 || to > length) {
      throw new RangeError(`range end ${to} is outside text of length ${length}`)
    }
    return markExpandsAtEnd(type) && to < length
      ? anchorBefore(this.idAtVisible(to))
      : anchorAfter(this.idAtVisible(to - 1))
  }

  formatting(): Span[] {
    if (this.marks.size === 0) return []
    return this.marks.spans(this.anchorIndex())
  }

  private anchorIndex(): AnchorPositions {
    const starts = new Map<number, number[]>()
    const blockAt = new Map<number, Block[]>()
    const visibleBefore = new Map<Block, number>()

    let seen = 0
    for (const block of this.blocks) {
      visibleBefore.set(block, seen)
      seen += block.visibleLength
      const replica = block.start.replicaId
      const lamports = starts.get(replica)
      if (lamports === undefined) {
        starts.set(replica, [block.start.lamport])
        blockAt.set(replica, [block])
      } else {
        lamports.push(block.start.lamport)
        blockAt.get(replica)!.push(block)
      }
    }
    for (const [replica, lamports] of starts) {
      const blocks = blockAt.get(replica)!
      const order = lamports.map((_, i) => i).sort((a, b) => lamports[a] - lamports[b])
      starts.set(replica, order.map((i) => lamports[i]))
      blockAt.set(replica, order.map((i) => blocks[i]))
    }

    return (anchor: Anchor) => {
      const lamports = starts.get(anchor.charId.replicaId)
      if (lamports === undefined) return undefined
      let low = 0
      let high = lamports.length - 1
      let found = -1
      while (low <= high) {
        const mid = (low + high) >>> 1
        if (lamports[mid] <= anchor.charId.lamport) {
          found = mid
          low = mid + 1
        } else {
          high = mid - 1
        }
      }
      if (found < 0) return undefined
      const block = blockAt.get(anchor.charId.replicaId)![found]
      if (!block.containsId(anchor.charId)) return undefined
      const base = visibleBefore.get(block)!
      if (block.deleted) return base
      const offset = block.offsetOf(anchor.charId)
      return anchor.side === 'before' ? base + offset : base + offset + 1
    }
  }

  markOperation(type: string, value: string): AddMarkOp | null {
    return [...this.marks.all()].reverse().find((op): op is AddMarkOp =>
      op.kind === 'addMark' && op.type === type && op.value === value) ?? null
  }

  get markCount(): number {
    return this.marks.size
  }

  apply(op: Op): ApplyResult {
    this.clock = Math.max(this.clock, opMaxLamport(op) + 1)

    if (this.isAlreadyKnown(op)) {
      return 'duplicate'
    }
    if (this.tryApply(op)) {
      this.observeVersion(op)
      this.drainPending()
      return 'applied'
    }
    const key = idToString(op.id)
    if (this.pending.has(key)) {
      return 'duplicate'
    }
    this.pending.set(key, op)
    return 'buffered'
  }

  applyAll(ops: readonly Op[]): void {
    for (const op of ops) this.apply(op)
  }

  private isAlreadyKnown(op: Op): boolean {
    if (op.kind === 'insert') {
      return this.locateId(op.id) !== null
    }
    if (isMarkOp(op)) {
      return this.marks.has(op.id)
    }
    return this.deleteIds.has(idToString(op.id))
  }

  private tryApply(op: Op): boolean {
    if (op.kind === 'insert') {
      if (this.locateId(op.id) !== null) return true
      if (op.originLeft !== null && this.locateId(op.originLeft) === null) return false
      this.integrate(op)
      return true
    }
    if (isMarkOp(op)) {
      if (this.locateId(op.start.charId) === null) return false
      if (this.locateId(op.end.charId) === null) return false
      this.marks.apply(op)
      return true
    }
    for (const range of op.targets) {
      if (!this.isRangeFullyPresent(range)) return false
    }
    for (const range of op.targets) {
      this.markRangeDeleted(range)
    }
    this.deleteIds.add(idToString(op.id))
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

  private integrate(op: InsertOp): void {
    let position: number
    if (op.originLeft === null) {
      position = 0
    } else {
      const found = this.locateId(op.originLeft)!
      const originBlock = this.blocks[found.blockIndex]
      if (found.offset < originBlock.length - 1) {
        this.splitBlock(found.blockIndex, found.offset + 1)
      }
      position = found.blockIndex + 1
    }

    while (position < this.blocks.length && compareIds(this.blocks[position].start, op.id) > 0) {
      position++
    }

    const block = new Block(op.id, op.text, op.originLeft, false)

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
    while (progress && this.pending.size > 0) {
      progress = false
      for (const [key, op] of [...this.pending]) {
        if (this.tryApply(op)) {
          this.observeVersion(op)
          this.pending.delete(key)
          progress = true
        }
      }
    }
  }

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
    return this.pending.size
  }

  get version(): Map<number, number> {
    return new Map(this.maxSeen)
  }

  idAtVisible(visibleIndex: number): Id {
    const found = this.locateVisible(visibleIndex)
    if (found === null) {
      throw new RangeError(`no visible character at position ${visibleIndex}`)
    }
    return this.blocks[found.blockIndex].idAt(found.offset)
  }

  anchorPosition(target: Id, side: 'before' | 'after'): number | null {
    return this.anchorIndex()({ charId: target, side }) ?? null
  }

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

  visibleIndicesOf(first: Id, length: number): number[] {
    const found: number[] = []
    const runEnd = first.lamport + length
    let seen = 0
    for (const block of this.blocks) {
      if (!block.deleted && block.start.replicaId === first.replicaId) {
        const from = Math.max(block.start.lamport, first.lamport)
        const to = Math.min(block.start.lamport + block.length, runEnd)
        for (let lamport = from; lamport < to; lamport++) {
          found.push(seen + (lamport - block.start.lamport))
        }
      }
      seen += block.visibleLength
    }
    return found
  }

  holds(op: Op): boolean {
    return this.isAlreadyKnown(op)
  }

  contains(target: Id): boolean {
    return this.locateId(target) !== null
  }

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
