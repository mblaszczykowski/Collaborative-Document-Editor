import { Block } from './block'
import type { DecodedSnapshot } from './snapshot'
import { MarkSet, type Span } from './marks'
import {
  anchorAfter,
  anchorBefore,
  compareIds,
  id,
  idEquals,
  idPlus,
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

/**
 * The client-side document replica.
 *
 * Its behaviour has to be IDENTICAL to `RgaDocument` in Java — not "equivalent", but the same
 * character for character, including RGA's known limitation (interleaving when typing backwards).
 * The corpus of conformance vectors enforces that.
 *
 * THE INDEX IS NAIVE (a linear scan), unlike the server version, which uses a treap. That is a
 * deliberate decision rather than an oversight: on the server we measured with a benchmark that a
 * linear scan hurts, and only then replaced it. On the client the corresponding measurement (typing
 * latency in the browser on a 100k-character document) does not exist yet — so we do not optimise
 * blind. TO BE MEASURED IN CP8; if it turns out badly, porting the treap is mechanical.
 */
export class RgaDocument {
  private blocks: Block[] = []
  /** The Lamport clock — the value the next local operation will receive. */
  private clock = 0
  /** The highest applied lamport per replica. For GC and resynchronisation, NOT for dedup. */
  private readonly maxSeen = new Map<number, number>()
  /** Operations that cannot be applied yet (a missing origin or deletion target). */
  private pending: Op[] = []

  /**
   * Formatting as a separate layer over the character sequence (CP13).
   *
   * Separate, because it is not an attribute of a character and cannot be one — the justification
   * is in `marks.ts` and on the Java side. It shows in the fact that `Block` did not change by a
   * single field in CP13.
   */
  private readonly marks = new MarkSet()

  private replicaIdValue: number

  /** How many operations this replica has CREATED — see adoptReplicaId. */
  private locallyGenerated = 0

  constructor(replicaId: number) {
    this.replicaIdValue = replicaId
  }

  get replicaId(): number {
    return this.replicaIdValue
  }

  /**
   * Adopts the replica identifier assigned by the server.
   *
   * Identifiers MUST be globally unique — a collision makes the algorithm treat two different
   * characters as the same one and silently lose one author's text. That is why a single counter in
   * Redis hands them out rather than the client (ReplicaIdAllocator on the server).
   *
   * Throws when the replica has already generated anything: changing the identifier after
   * generating operations would diverge the structure, because the existing characters would still
   * point at the old one. The condition is a design assertion rather than error handling — if it
   * ever fires, the initialisation order has broken.
   */
  adoptReplicaId(replicaId: number): void {
    // The guard is about OPERATIONS CREATED HERE, not about the clock's value, and the
    // difference started to matter in CP24: restoring a server snapshot sets the clock to the
    // document's, without this replica having produced anything. Keying the check on the clock
    // made a document opened from a snapshot refuse the identifier the server had just assigned
    // it — leaving the replica on id 0, the one identifier guaranteed to collide.
    if (this.locallyGenerated > 0) {
      throw new Error(
        `attempt to change the replica identifier after generating ${this.locallyGenerated} operations`,
      )
    }
    this.replicaIdValue = replicaId
  }

  /**
   * Loads a snapshot from the server in place of replaying the log (CP24).
   *
   * Everything the replica knows is replaced: the blocks in document order (so the integration
   * algorithm does not have to run again — that is what a snapshot is for), the Lamport clock,
   * the version vector, and the marks.
   *
   * The marks go straight into the MarkSet rather than through `apply`, and that is not a
   * shortcut: `apply` would compare each of them against the version vector loaded a line
   * earlier, conclude it has seen them, and drop every one — leaving a document with the right
   * text and no formatting at all.
   */
  restoreFrom(snapshot: DecodedSnapshot): void {
    this.blocks = snapshot.blocks
    this.clock = snapshot.clock
    this.maxSeen.clear()
    for (const [replicaId, lamport] of snapshot.maxSeen) {
      this.maxSeen.set(replicaId, lamport)
    }
    for (const mark of snapshot.marks) {
      this.marks.apply(mark)
    }
    this.pending = []
  }

  // ------------------------------------------------------------------
  // Local operations
  // ------------------------------------------------------------------

  insert(visibleIndex: number, text: string): InsertOp {
    const length = this.visibleLength
    if (visibleIndex < 0 || visibleIndex > length) {
      throw new RangeError(`position ${visibleIndex} is outside text of length ${length}`)
    }
    // The origin is the visible predecessor of the insertion point. When tombstones sit before
    // the position, the new text lands before them — standard RGA behaviour.
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
      // Once a block is marked as a tombstone it stops being visible, so visibleIndex already
      // points at the next character to delete — which is why it is not incremented.
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

    const op: DeleteOp = { kind: 'delete', id: id(this.replicaIdValue, this.clock), targets }
    this.clock += opSpan(op)
    this.locallyGenerated++
    this.observeVersion(op)
    return op
  }

  // ------------------------------------------------------------------
  // Remote operations
  // ------------------------------------------------------------------

  // ------------------------------------------------------------------
  // Formatting (CP13)
  // ------------------------------------------------------------------

  /**
   * Applies formatting to a range of visible text [from, to).
   *
   * The choice of anchor sides decides the behaviour while typing — the start is always `before`
   * (formatting does not grow backwards), the end depends on the mark type. The full justification
   * is at `addMark` in the Java version; here it has to agree character for character, because both
   * sides create operations independently.
   */
  addMark(from: number, to: number, type: string, value: string | null): AddMarkOp {
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
    // A range reaching the end of the document has no character to anchor an expanding anchor to
    // — a known limitation, documented on the Java side.
    return markExpandsAtEnd(type) && to < length
      ? anchorBefore(this.idAtVisible(to))
      : anchorAfter(this.idAtVisible(to - 1))
  }

  /** The visible text's formatting as disjoint runs, in order. */
  formatting(): Span[] {
    return this.marks.spans((anchor) => this.anchorPosition(anchor))
  }

  get markCount(): number {
    return this.marks.size
  }

  /**
   * An anchor's position in visible coordinates.
   *
   * An anchor on a tombstone does not disappear — it points at the gap between the visible
   * characters surrounding it. That is what makes deleting formatted text and typing new text in
   * the same place preserve the formatting instead of losing it.
   */
  private anchorPosition(anchor: Anchor): number | null {
    let seen = 0
    for (const block of this.blocks) {
      if (block.containsId(anchor.charId)) {
        if (block.deleted) return seen
        return anchor.side === 'before'
          ? seen + block.offsetOf(anchor.charId)
          : seen + block.offsetOf(anchor.charId) + 1
      }
      seen += block.visibleLength
    }
    return null
  }

  apply(op: Op): ApplyResult {
    // The clock advances even for a buffered operation: the Lamport rule concerns receipt, not
    // application.
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
    // Mark operations are keyed by their own identifier, so duplicate detection is exact —
    // unlike deletion, where we infer it from the structure's contents.
    if (isMarkOp(op)) {
      return this.marks.has(op.id)
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
    if (isMarkOp(op)) {
      // A mark waits for BOTH anchors. Applied earlier it would put formatting in a random
      // place — silent corruption of the document's appearance.
      if (this.locateId(op.start.charId) === null) return false
      if (this.locateId(op.end.charId) === null) return false
      this.marks.apply(op)
      return true
    }
    // All or nothing — see the comment at tryApplyDelete in the Java version.
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
  // The heart of the algorithm
  // ------------------------------------------------------------------

  /**
   * The RGA rule: find the origin, walk right while the blocks encountered have a HIGHER timestamp
   * than the one being inserted, insert at the first place with a lower one.
   *
   * The scan is coarse-grained (over blocks, not characters), and that is correct because the
   * characters in a block have increasing lamports: if a block's first character has a higher
   * timestamp than the one being inserted, so do all the rest.
   */
  private integrate(op: InsertOp): void {
    let position: number
    if (op.originLeft === null) {
      position = 0
    } else {
      const found = this.locateId(op.originLeft)!
      const originBlock = this.blocks[found.blockIndex]
      // The origin has to be the last character of its block, so that "immediately after the
      // origin" is a block boundary.
      if (found.offset < originBlock.length - 1) {
        this.splitBlock(found.blockIndex, found.offset + 1)
      }
      position = found.blockIndex + 1
    }

    while (position < this.blocks.length && compareIds(this.blocks[position].start, op.id) > 0) {
      position++
    }

    const block = new Block(op.id, op.text, op.originLeft, false)

    // The fast path for ordinary typing.
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
  // Reading the state
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

  /** The identifier of the character at the given visible position — a cursor position (ADR-0006). */
  idAtVisible(visibleIndex: number): Id {
    const found = this.locateVisible(visibleIndex)
    if (found === null) {
      throw new RangeError(`no visible character at position ${visibleIndex}`)
    }
    return this.blocks[found.blockIndex].idAt(found.offset)
  }

  /**
   * The inverse of `idAtVisible` — the position in the text of the character with a given
   * identifier.
   *
   * Needed to draw other users' cursors: presence carries identifiers (because those move with the
   * text), whereas CodeMirror draws by offsets. Returns null when the character is a tombstone or
   * absent — the cursor of someone whose character has just been deleted simply disappears instead
   * of pointing at a random place.
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

  /**
   * Whether the character with the given identifier is in the structure — including as a tombstone.
   *
   * Used for gap detection: a write acknowledgement carries the operation's identity, so not
   * holding that operation means its broadcast never arrived.
   */
  contains(target: Id): boolean {
    return this.locateId(target) !== null
  }

  /** A copy of the blocks in document order — for diagnostics and comparison with the Java version. */
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
