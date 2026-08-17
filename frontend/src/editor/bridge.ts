import type { ChangeSet, Text } from '@codemirror/state'
import type { RgaDocument } from '../crdt/document'
import type { UndoManager } from '../crdt/undo'
import type { Op } from '../crdt/types'

const MAX_INSERT_CHARACTERS = 8192

function pieceEnd(text: string, offset: number): number {
  const end = Math.min(offset + MAX_INSERT_CHARACTERS, text.length)
  if (end === text.length) return end
  const before = text.charCodeAt(end - 1)
  const at = text.charCodeAt(end)
  const splitsAPair = before >= 0xd800 && before <= 0xdbff && at >= 0xdc00 && at <= 0xdfff
  return splitsAPair && end - 1 > offset ? end - 1 : end
}

export function changesToOps(
  document: RgaDocument,
  changes: ChangeSet,
  undo?: UndoManager,
): Op[] {
  const collected: { fromA: number; toA: number; inserted: string }[] = []
  changes.iterChanges((fromA, toA, _fromB, _toB, inserted: Text) => {
    collected.push({ fromA, toA, inserted: inserted.toString() })
  })

  const ops: Op[] = []
  for (let i = collected.length - 1; i >= 0; i--) {
    const change = collected[i]

    if (change.toA > change.fromA) {
      const removed = document.text.slice(change.fromA, change.toA)
      const anchor = change.fromA > 0 ? document.idAtVisible(change.fromA - 1) : null
      ops.push(document.delete(change.fromA, change.toA - change.fromA))
      undo?.recordDelete(anchor, removed)
    }

    if (change.inserted.length > 0) {
      for (let offset = 0; offset < change.inserted.length;) {
        const end = pieceEnd(change.inserted, offset)
        const piece = change.inserted.slice(offset, end)
        const op = document.insert(change.fromA + offset, piece)
        ops.push(op)
        undo?.recordInsert(op.id, piece.length)
        offset = end
      }
    }
  }
  return ops
}

export function minimalChange(
  current: string,
  target: string,
): { from: number; to: number; insert: string } | null {
  if (current === target) return null

  let prefix = 0
  const maxPrefix = Math.min(current.length, target.length)
  while (prefix < maxPrefix && current[prefix] === target[prefix]) prefix++

  let suffix = 0
  const maxSuffix = Math.min(current.length - prefix, target.length - prefix)
  while (
    suffix < maxSuffix &&
    current[current.length - 1 - suffix] === target[target.length - 1 - suffix]
  ) {
    suffix++
  }

  return {
    from: prefix,
    to: current.length - suffix,
    insert: target.slice(prefix, target.length - suffix),
  }
}
