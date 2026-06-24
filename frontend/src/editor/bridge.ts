import type { ChangeSet, Text } from '@codemirror/state'
import type { RgaDocument } from '../crdt/document'
import type { UndoManager } from '../crdt/undo'
import type { Op } from '../crdt/types'

/**
 * The bridge between CodeMirror and the CRDT.
 *
 * The editor-layer decision sets the boundary: **the CRDT is the source of truth, CodeMirror is a projection.**
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
/**
 * The largest number of characters one insert operation may carry (CP28).
 *
 * 8192 characters is at most 32 KiB of UTF-8, half the frame limit from the transport decision — the same
 * margin the broadcast and resynchronisation paths use on the server, and for the same reason:
 * the operation is not the whole frame. Around it come the JSON envelope, the STOMP headers and
 * whatever the broker prepends, and budgeting to the exact limit means discovering their size in
 * production.
 */
const MAX_INSERT_CHARACTERS = 8192

/**
 * Where the next piece has to end so that it does not cut a character in half.
 *
 * `slice` counts UTF-16 code units, and an emoji is two of them. A paste with one straddling
 * index 8192 therefore produced an operation ending in a lone HIGH surrogate and a second
 * beginning with a lone LOW surrogate — two halves of one character, in two operations, with
 * nothing downstream able to put them back together: an operation's identity is what the merge
 * algorithm is built on, so the pieces can arrive in either order, interleaved with somebody
 * else's text, or one without the other.
 *
 * This is the SECOND independent source of lone surrogates in this codebase. Snapshot format v4
 * exists solely to survive the ones block splitting produces, and it survives them because the
 * snapshot format has an escape hatch for them. The operation log has none, so the fix here has
 * to be not producing them rather than encoding them.
 *
 * `end - 1 > offset` is the degenerate case: a limit of one code unit cannot express a surrogate
 * pair at all, and walking back would return the offset we started from and loop for ever. It is
 * unreachable while the limit is 8192 — the guard is here so that lowering the constant fails by
 * emitting one oversized piece rather than by hanging the tab.
 */
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
      // The text and the anchor have to be read BEFORE the deletion — afterwards one is gone and
      // the other is at a different position.
      const removed = document.text.slice(change.fromA, change.toA)
      const anchor = change.fromA > 0 ? document.idAtVisible(change.fromA - 1) : null
      ops.push(document.delete(change.fromA, change.toA - change.fromA))
      undo?.recordDelete(anchor, removed)
    }

    if (change.inserted.length > 0) {
      // Split into pieces, because ONE operation has to fit in ONE frame (CP28).
      //
      // Typing produces one character at a time and this loop runs once. Pasting does not: a
      // pasted chapter is a single change of tens of kilobytes, and as a single operation it
      // would exceed the 64 KiB frame limit from the transport decision — in both directions. On the way out
      // the send fails; on the way back, a client resynchronising receives a frame it cannot
      // accept and stalls silently, which is the failure CP8 already found once.
      //
      // Nothing downstream can fix this: an operation's identity is what the merge algorithm is
      // built on, so it cannot be split after the fact. The place to split is here, where the
      // text is still text.
      // Driven by the COMPUTED end rather than a fixed stride, because a piece may be one code
      // unit short of the limit — see `pieceEnd`. Stepping by the constant while slicing to a
      // walked-back boundary would drop or duplicate the character at the seam.
      for (let offset = 0; offset < change.inserted.length;) {
        const end = pieceEnd(change.inserted, offset)
        const piece = change.inserted.slice(offset, end)
        const op = document.insert(change.fromA + offset, piece)
        ops.push(op)
        // The pieces coalesce into ONE undo step: consecutive Lamport values from the same
        // replica within the coalescing window is exactly the condition recordInsert already
        // uses for continuous typing. Undoing a paste in eight steps would be absurd.
        //
        // `piece.length` and not the limit: a piece walked back off a surrogate pair is 8191 code
        // units, and the clock advances by what was actually inserted. Recording the nominal size
        // would leave the coalescing check one short for every piece after the first, so an emoji
        // in a paste would silently turn one undo step into several.
        undo?.recordInsert(op.id, piece.length)
        offset = end
      }
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
