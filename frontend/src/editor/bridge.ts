import type { ChangeSet, Text } from '@codemirror/state'
import type { RgaDocument } from '../crdt/document'
import type { UndoManager } from '../crdt/undo'
import type { Op } from '../crdt/types'

/**
 * The bridge between CodeMirror and the CRDT.
 *
 * ADR-0006 sets the boundary: **the CRDT is the source of truth, CodeMirror is a projection.**
 * Every local change follows the path `CM -> CRDT operation -> merge -> back to CM`, never "change
 * CM and separately the CRDT". This file is the only place the two models meet — deliberately, so
 * that divergence has exactly one possible source.
 */

/**
 * Turns a CodeMirror change set into CRDT operations.
 *
 * **The changes are processed FROM THE END.** The reason: `iterChanges` reports positions in the
 * document *before* the change, and every applied operation shifts everything after it. Going
 * forwards, the second and subsequent changes would land in shifted places — a bug that with a
 * single change (that is, ordinary typing) never manifests at all and only surfaces on a paste with
 * a multiple selection or on "replace all".
 */
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
      // The text and the anchor have to be read BEFORE the deletion — afterwards one is gone and
      // the other is at a different position.
      const removed = document.text.slice(change.fromA, change.toA)
      const anchor = change.fromA > 0 ? document.idAtVisible(change.fromA - 1) : null
      ops.push(document.delete(change.fromA, change.toA - change.fromA))
      undo?.recordDelete(anchor, removed)
    }

    if (change.inserted.length > 0) {
      const op = document.insert(change.fromA, change.inserted)
      ops.push(op)
      undo?.recordInsert(op.id, change.inserted.length)
    }
  }
  return ops
}

/**
 * The minimal change turning `current` into `target`.
 *
 * After merging remote operations we have new CRDT text and want to move it into CodeMirror in a
 * way that **does not make the user's cursor jump**. Replacing the whole document is correct, but
 * it would put the cursor at the end and drop the selection — that is, it would break typing
 * exactly when somebody else is editing the same paragraph.
 *
 * A common prefix and suffix are enough, because a remote operation is usually one contiguous
 * change. With several scattered changes at once the computed range is wider than necessary —
 * correct, but less economical.
 *
 * The cost is O(document length) per batch of remote operations. **To be measured in CP8**: on a
 * document of ~100k characters under heavy multi-user editing this may stop being enough, and the
 * right answer then is computing positions directly from operation identifiers rather than
 * comparing text.
 */
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
