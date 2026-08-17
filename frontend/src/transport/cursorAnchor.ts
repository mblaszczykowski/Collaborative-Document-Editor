import type { Id } from '../crdt/types'

export interface CursorAnchor {
  readonly charId: Id
  readonly side: 'before' | 'after'
}

export function encodeCursorAnchor(anchor: CursorAnchor | null): Id | null {
  if (anchor === null) return null
  return {
    replicaId: anchor.charId.replicaId,
    lamport: anchor.side === 'after' ? anchor.charId.lamport : -anchor.charId.lamport - 1,
  }
}

export function decodeCursorAnchor(
  replicaId: number | null,
  lamport: number | null,
): CursorAnchor | null {
  if (replicaId === null || lamport === null) return null
  return lamport >= 0
    ? { charId : { replicaId, lamport }, side: 'after' }
: { charId: { replicaId, lamport: -lamport - 1 }, side: 'before' }
}
