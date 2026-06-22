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

/**
 * Refuses an inverted or empty formatting range — the twin of `RgaDocument.requireNonEmptyRange`.
 *
 * The two anchors validate independently (`anchorStart` checks `from`, `anchorEnd` checks `to`),
 * so nothing checked that one came before the other. `addMark(4, 2)` was accepted: it consumed a
 * Lamport tick, went into the mark set permanently, was broadcast and written to the log, pinned
 * two blocks against garbage collection through the anchor rule — and resolved to nothing at all.
 *
 * This guards the LOCAL path only, exactly as the server does. A range arriving from another
 * replica through `apply` is still accepted, deliberately: refusing a remote operation would make
 * two replicas hold different sets of operations, which is divergence — the one outcome the whole
 * algorithm exists to prevent. The mark simply resolves to nothing.
 *
 * The client is the side that CREATES these from user gestures, so it needed the check more than
 * the server did, and had it for the shorter time.
 */
function requireNonEmptyRange(from: number, to: number): void {
  if (from >= to) {
    throw new RangeError(`an empty formatting range [${from}, ${to})`)
  }
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
  /**
   * Operations that cannot be applied yet (a missing origin or deletion target).
   *
   * Keyed by the operation's own identifier, not a plain list — the twin of the same decision in
   * `RgaDocument.java`, and made for the same reason. `isAlreadyKnown` recognises an insert only
   * once its characters are IN THE STRUCTURE, so an operation waiting here was never a duplicate
   * to it, and every redelivery path feeds this buffer: the server's re-broadcast, this client's
   * own retransmission after a reconnect, and the buffered operations replayed on reload.
   *
   * A list kept one entry per copy, and `drainPending` then retried every one of them on every
   * successful apply — so a reconnect that redelivered a run of orphans turned into quadratic
   * work at exactly the moment the client was already behind.
   */
  private pending = new Map<string, Op>()

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
   * Whether this replica has produced any operation of its own.
   *
   * Exposed because it is the question the caller of {@link adoptReplicaId} actually has, and the
   * alternative was asking `replicaId === 0` — sound, because 0 is the constructor placeholder and
   * the server allocates from 1 upwards, but it encodes that invariant as a magic number in a file
   * that cannot see it. `adoptReplicaId` throws on exactly this condition, so a caller that wants
   * to decide rather than to be thrown at needs to be able to read it.
   */
  get hasGeneratedOperations(): boolean {
    return this.locallyGenerated > 0
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
    // CLEARED first, not merged into. "Everything the replica knows is replaced" is what the
    // paragraph above promises and what `RgaDocument.restore` does on the server — it builds a
    // fresh document. Here the marks were ADDED to whatever the set already held, so restoring
    // onto a replica that had been used would resurrect formatting the snapshot does not contain.
    // Unreachable today, because `useCollaborativeDocument` only ever restores onto a brand-new
    // replica — which is exactly the kind of guarantee that holds until somebody reuses the method
    // it was never written into.
    this.marks.clear()
    for (const mark of snapshot.marks) {
      this.marks.apply(mark)
    }
    this.pending.clear()
    // `snapshot.gcWatermark` is deliberately NOT stored. The client has no garbage collection, so
    // it has nothing to compare the boundary against, and a field written and never read is the
    // "value the system does not have" this project has already removed once (see
    // `heartbeatToleranceFactor`). It is decoded in `snapshot.ts` because the reader has to
    // advance past it; when the client gains a use for it — telling "my operation outran its
    // cause" from "its cause is gone for good" — the number is one line away.
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
    // A range reaching the end of the document has no character to anchor an expanding anchor to
    // — a known limitation, documented on the Java side.
    return markExpandsAtEnd(type) && to < length
      ? anchorBefore(this.idAtVisible(to))
      : anchorAfter(this.idAtVisible(to - 1))
  }

  /** The visible text's formatting as disjoint runs, in order. */
  formatting(): Span[] {
    // The index costs O(blocks) to build, so it is not built for a document that has no marks to
    // resolve — which is most documents, and the case the first version of this regressed from
    // 0.001 ms to 1.7 ms by walking the blocks unconditionally.
    if (this.marks.size === 0) return []
    return this.marks.spans(this.anchorIndex())
  }

  /**
   * A logarithmic-time anchor resolver, built once per `formatting` call.
   *
   * ## What this replaces, with numbers
   *
   * `formatting` used to hand `MarkSet.spans` a closure over `anchorPosition` — a **full linear
   * scan over every block** — called once per anchor, that is twice per mark. Together with the
   * quadratic boundary loop `marks.ts` used to have, the cost was `O(marks x blocks + marks^2)`,
   * and it sits on the keystroke path: `refreshStats` calls `formatting` on every local edit and
   * every remote batch, plus two or three more times per formatting gesture.
   *
   * Measured in this repository, 10 000 blocks, before and after both changes:
   *
   * | marks | before | after |
   * |------:|----------:|---------:|
   * | 0 | 0.001 ms | 0.001 ms |
   * | 50 | 1.70 ms | 1.71 ms |
   * | 200 | 7.88 ms | 1.50 ms |
   * | 800 | 31.11 ms | 1.56 ms |
   *
   * One keystroke on the 800-mark document went from 31.02 ms — a dropped frame per character — to
   * 1.61 ms. 800 marks is not exotic: comments, suggestions and every heading, quote and list line
   * are all marks.
   *
   * The cost is now essentially FLAT in the number of marks and dominated by building this index
   * over the blocks, which is the shape it should have. That also means the 50-mark row is
   * unchanged rather than improved, and the 10 000-block document those rows use is a pathological
   * one — 10 000 separate single-character blocks, which only arises from inserting at position 0
   * ten thousand times. A document of the same length written the ordinary way is a hundred
   * run-length blocks, and resolves in 0.014 ms.
   *
   * ## Why an index rather than a table of every character
   *
   * The first version of this built a `Map` from every character identifier to its position. That
   * is O(1) per anchor and **O(total characters)** to build, which made a plain 100 000-character
   * document pay for a structure it has no marks to use — a regression measured at 1.7 ms on a
   * document that previously cost 0.001 ms. Run-length blocks are the whole reason this document
   * model exists; an index that walks characters throws that away.
   *
   * So it indexes BLOCKS, the way `TreapBlockList` does on the server: block starts sorted per
   * replica, a binary search to find the block containing a character, and the visible offset
   * carried alongside. O(blocks) memory, O(blocks log blocks) to build, O(log blocks) per anchor.
   *
   * A tombstone resolves to the position of the gap it leaves — the rule that lets formatting
   * survive the deletion of the text it was applied to.
   */
  private anchorIndex(): AnchorPositions {
    // Per replica: the starting lamport of each of its blocks, ascending, with that block's index
    // and the number of visible characters before it.
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
    // Document order is not lamport order — a split or a concurrent insert puts a lower lamport
    // after a higher one — so each replica's list has to be sorted before it can be searched.
    for (const [replica, lamports] of starts) {
      const blocks = blockAt.get(replica)!
      const order = lamports.map((_, i) => i).sort((a, b) => lamports[a] - lamports[b])
      starts.set(replica, order.map((i) => lamports[i]))
      blockAt.set(replica, order.map((i) => blocks[i]))
    }

    return (anchor: Anchor) => {
      const lamports = starts.get(anchor.charId.replicaId)
      if (lamports === undefined) return undefined
      // The greatest block start at or below the anchor's lamport.
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

  get markCount(): number {
    return this.marks.size
  }

  // `anchorPosition` used to live here: a full linear scan over every block, called once per
  // anchor and therefore twice per mark, from inside the formatting resolver. It is replaced by
  // `anchorPositions` above, which builds the whole table in one pass — see that method for the
  // measurements and for why this was the dominant cost on the keystroke path.

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
    const key = idToString(op.id)
    if (this.pending.has(key)) {
      // Already waiting, so the answer is `duplicate` rather than `buffered`.
      //
      // Nothing on THIS side reads the answer — `applyAll` discards it and is the only production
      // caller. It matters for parity: `RgaDocument.java` is the twin of this file, and its
      // caller does act on it (`DocumentReplica.merge` writes and broadcasts anything news, so a
      // redelivery there cost a log write and a cluster broadcast per copy). Two implementations
      // that answer the same question differently are exactly what the conformance corpus exists
      // to prevent, whether or not today's callers happen to notice.
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
      // A duplicate: a character with this identifier is already in the structure. `apply`'s own
      // `isAlreadyKnown` check does not cover this case, because it runs BEFORE buffering — an
      // operation waiting in `pending` is not in the structure yet, so the same operation
      // delivered twice while its origin was missing was buffered twice and integrated twice.
      // That duplicated the text AND put one identifier in two blocks, which makes `locateId`
      // ambiguous and a later delete tombstone only one of the copies.
      //
      // The Java twin has always had this guard (`RgaDocument.tryApplyInsert`); the conformance
      // corpus could not catch the difference until vector 006, because no vector duplicated an
      // operation while it was buffered — 004 delivers the origin first.
      if (this.locateId(op.id) !== null) return true
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
    return this.pending.size
  }

  get version(): Map<number, number> {
    return new Map(this.maxSeen)
  }

  /** The identifier of the character at the given visible position — a cursor position. */
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
   * The visible positions of a CONTIGUOUS RUN of one replica's identifiers, ascending.
   *
   * The answer `visibleIndexOf` gives per character, for a whole run in one sweep. Undo needs
   * exactly this — it resolves every character of an insertion separately, because a collaborator
   * may have deleted some of them or wedged their own text into the middle — and calling
   * `visibleIndexOf` per character walks the block list once per character. On a document
   * fragmented into thousands of blocks, undoing a large paste spent seconds in that loop; the
   * paste itself is one gesture, so the undo has to be one too.
   *
   * Ascending by construction rather than by sorting afterwards: the blocks are in document order
   * and a block's own offsets ascend within it, so the positions come out in the order the callers
   * need. Characters that are tombstones or absent are simply missing from the result, which is
   * the "undo only what is left" case rather than an error.
   */
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

  /**
   * Whether this replica has already integrated this operation — exactly, for every kind.
   *
   * The public face of `isAlreadyKnown`, and the difference from `contains` below is worth
   * stating: `contains` asks about a CHARACTER, so it answers the question only for inserts. A
   * delete or a mark takes its identity from the same Lamport clock but never names a character,
   * so `contains` is false for one however perfectly it has been applied. Ask this when you hold
   * the operation; `contains` is for when you have only an identifier.
   */
  holds(op: Op): boolean {
    return this.isAlreadyKnown(op)
  }

  /**
   * Whether the character with the given identifier is in the structure — including as a tombstone.
   *
   * <b>Only a character.</b> See `holds` above before using this to mean "have I got this
   * operation" — for a delete or a mark that reading is wrong, and gap detection paid for it.
   */
  contains(target: Id): boolean {
    return this.locateId(target) !== null
  }

  // describeBlocks used to sit here, documented as "for diagnostics and comparison with the
  // Java version". Nothing called it — DiagnosticsBar shows blockCount, tombstoneCount and
  // pendingCount, which is what a person actually reads.

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

