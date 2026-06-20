import { compareIds, id, idEquals, idPlus, type Id } from './types'

/**
 * A contiguous run of characters from one replica — the unit of storage.
 *
 * A document is not a list of characters but a list of blocks: typing 20 characters in a row is one
 * object, not 20. The justification and the invariants are in `docs/crdt-design.md` section 3.
 *
 * INVARIANT: a block is either entirely alive or entirely deleted. Deleting part of one splits the
 * block first.
 */
export class Block {
  readonly start: Id
  readonly text: string
  /** The left origin of the block's FIRST character; null means the start of the document. */
  readonly originLeft: Id | null
  /** The only mutable field — marking a tombstone is the only change an existing block can
      undergo without being split. */
  deleted: boolean

  constructor(start: Id, text: string, originLeft: Id | null, deleted: boolean) {
    if (text.length === 0) {
      throw new Error('a block cannot be empty')
    }
    this.start = start
    this.text = text
    this.originLeft = originLeft
    this.deleted = deleted
  }

  get length(): number {
    return this.text.length
  }

  get visibleLength(): number {
    return this.deleted ? 0 : this.text.length
  }

  idAt(offset: number): Id {
    return idPlus(this.start, offset)
  }

  get lastId(): Id {
    return idPlus(this.start, this.text.length - 1)
  }

  containsId(value: Id): boolean {
    return (
      value.replicaId === this.start.replicaId &&
      value.lamport >= this.start.lamport &&
      value.lamport < this.start.lamport + this.text.length
    )
  }

  offsetOf(value: Id): number {
    return value.lamport - this.start.lamport
  }

  /**
   * Splits the block into [0, offset) and [offset, length).
   *
   * The right part's origin is the left part's last character — which makes a split a purely
   * technical operation that does not change the document's logical structure. This is a key
   * invariant: if splitting changed meaning, the result would depend on WHEN a block was split.
   */
  splitAt(offset: number): [Block, Block] {
    if (offset <= 0 || offset >= this.text.length) {
      throw new Error(`split outside the block's interior: offset=${offset}, length=${this.text.length}`)
    }
    return [
      new Block(this.start, this.text.slice(0, offset), this.originLeft, this.deleted),
      new Block(
        idPlus(this.start, offset),
        this.text.slice(offset),
        idPlus(this.start, offset - 1),
        this.deleted,
      ),
    ]
  }

  /**
   * Whether `other` can be appended to the end of this block without changing meaning.
   *
   * This is the fast path for ordinary typing — without it every keystroke would create a separate
   * block and run-length compression would gain nothing.
   */
  canAppend(other: Block): boolean {
    return (
      !this.deleted &&
      !other.deleted &&
      other.start.replicaId === this.start.replicaId &&
      other.start.lamport === this.lastId.lamport + 1 &&
      idEquals(this.lastId, other.originLeft)
    )
  }

  append(more: string): Block {
    return new Block(this.start, this.text + more, this.originLeft, this.deleted)
  }

  /** A representation matching Block.toString() in Java — used when diagnosing divergence. */
  describe(): string {
    const origin = this.originLeft === null ? 'null' : `${this.originLeft.replicaId}@${this.originLeft.lamport}`
    return `${this.deleted ? '~' : ''}${this.start.replicaId}@${this.start.lamport}:"${this.text}"←${origin}`
  }
}

export { compareIds, id, idEquals, idPlus }
