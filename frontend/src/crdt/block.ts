import { idEquals, idPlus, type Id } from './types'

export class Block {
  readonly start: Id
  readonly text: string
  readonly originLeft: Id | null
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
}
