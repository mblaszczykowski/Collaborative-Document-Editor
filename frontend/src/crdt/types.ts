export interface Id {
  readonly replicaId: number
  readonly lamport: number
}

export function id(replicaId: number, lamport: number): Id {
  return { replicaId, lamport }
}

export function idPlus(base: Id, offset: number): Id {
  return { replicaId: base.replicaId, lamport: base.lamport + offset }
}

export function idEquals(a: Id | null, b: Id | null): boolean {
  if (a === null || b === null) return a === b
  return a.replicaId === b.replicaId && a.lamport === b.lamport
}

export function compareIds(a: Id, b: Id): number {
  if (a.lamport !== b.lamport) return a.lamport - b.lamport
  return a.replicaId - b.replicaId
}

export function idToString(value: Id): string {
  return `${value.replicaId}@${value.lamport}`
}

export interface Range {
  readonly start: Id
  readonly length: number
}

export interface InsertOp {
  readonly kind: 'insert'
  readonly id: Id
  readonly originLeft: Id | null
  readonly text: string
}

export interface DeleteOp {
  readonly kind: 'delete'
  readonly id: Id
  readonly targets: readonly Range[]
}

export interface Anchor {
  readonly charId: Id
  readonly side: 'before' | 'after'
}

export function anchorBefore(charId: Id): Anchor {
  return { charId, side: 'before' }
}

export function anchorAfter(charId: Id): Anchor {
  return { charId, side: 'after' }
}

export interface AddMarkOp {
  readonly kind: 'addMark'
  readonly id: Id
  readonly type: string
  readonly value: string | null
  readonly start: Anchor
  readonly end: Anchor
}

export interface RemoveMarkOp {
  readonly kind: 'removeMark'
  readonly id: Id
  readonly type: string
  readonly start: Anchor
  readonly end: Anchor
}

export type Op = InsertOp | DeleteOp | AddMarkOp | RemoveMarkOp

export function isMarkOp(op: Op): op is AddMarkOp | RemoveMarkOp {
  return op.kind === 'addMark' || op.kind === 'removeMark'
}

export function opSpan(op: Op): number {
  return op.kind === 'insert' ? op.text.length : 1
}

const EXPANDING_MARKS = new Set([
  'bold',
  'italic',
  'underline',
  'strike',
  'heading',
  'quote',
  'list',
])

export function markExpandsAtEnd(type: string): boolean {
  return EXPANDING_MARKS.has(type)
}

export function opMaxLamport(op: Op): number {
  return op.id.lamport + opSpan(op) - 1
}

export type ApplyResult = 'applied' | 'duplicate' | 'buffered'
