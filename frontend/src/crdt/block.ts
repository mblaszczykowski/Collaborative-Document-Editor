import { compareIds, id, idEquals, idPlus, type Id } from './types'

/**
 * Ciągły przebieg znaków od jednej repliki — jednostka przechowywania.
 *
 * Dokument nie jest listą znaków, tylko listą bloków: wpisanie 20 znaków pod rząd to jeden
 * obiekt, a nie 20. Uzasadnienie i niezmienniki w `docs/crdt-design.md` sekcja 3.
 *
 * NIEZMIENNIK: blok jest w całości żywy albo w całości usunięty. Usunięcie fragmentu
 * najpierw dzieli blok.
 */
export class Block {
  constructor(
    readonly start: Id,
    readonly text: string,
    /** Lewy origin PIERWSZEGO znaku bloku; null oznacza początek dokumentu. */
    readonly originLeft: Id | null,
    public deleted: boolean,
  ) {
    if (text.length === 0) {
      throw new Error('blok nie może być pusty')
    }
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
   * Dzieli blok na [0, offset) oraz [offset, length).
   *
   * Origin prawej części to ostatni znak lewej — dzięki temu podział jest operacją czysto
   * techniczną i nie zmienia struktury logicznej dokumentu. To jest kluczowy niezmiennik:
   * gdyby podział zmieniał znaczenie, wynik zależałby od tego, KIEDY blok został podzielony.
   */
  splitAt(offset: number): [Block, Block] {
    if (offset <= 0 || offset >= this.text.length) {
      throw new Error(`podział poza wnętrzem bloku: offset=${offset}, długość=${this.text.length}`)
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
   * Czy `other` da się dopisać na końcu tego bloku bez zmiany znaczenia.
   *
   * To jest szybka ścieżka zwykłego pisania — bez niej każdy naciśnięty klawisz tworzyłby
   * osobny blok i kompresja run-length nie dawałaby nic.
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

  /** Reprezentacja zgodna z Block.toString() w Javie — używana przy diagnostyce rozjazdów. */
  describe(): string {
    const origin = this.originLeft === null ? 'null' : `${this.originLeft.replicaId}@${this.originLeft.lamport}`
    return `${this.deleted ? '~' : ''}${this.start.replicaId}@${this.start.lamport}:"${this.text}"←${origin}`
  }
}

export { compareIds, id, idEquals, idPlus }
