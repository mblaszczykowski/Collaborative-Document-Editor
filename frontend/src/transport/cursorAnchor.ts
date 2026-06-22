import type { Id } from '../crdt/types'

/**
 * Where somebody's caret is, expressed the way marks already express a place: **a character and a
 * side**, not an offset (and the `Anchor` type in `crdt/types.ts`).
 *
 * ## The bug this exists to fix
 *
 * Presence used to carry one identifier and an implicit rule: "the caret sits AFTER this
 * character". `Editor.reportCursor` produced it as `idAtVisible(position - 1)`, and the receiver
 * undid it by adding one back. That is exact for every position from 1 to the end of the document
 * — including the end, which the comment there blamed for the problem and which is in fact the one
 * case that always worked, because the last character is a perfectly good thing to sit after.
 *
 * It has no answer for **position 0**. There is no character before the first one, so `toId`
 * returned `null`, and `remoteCursors.buildDecorations` drops a null head. The visible effect: a
 * collaborator who presses Home, or clicks before the first character, has their caret AND their
 * name flag vanish from everybody else's screen until they move again. On a shared document the
 * natural reading of a disappearing cursor is that the person left.
 *
 * ## Why a side, rather than "null means the beginning"
 *
 * Because a position is not a place. If the caret at position 0 were sent as "no identifier, draw
 * at 0", then somebody typing at the start of the document would push that caret's text along
 * while the caret stayed at 0 — pointing into words it did not belong to. That is verbatim the
 * failure the editor-layer decision rejects offsets for, and the reason every other position is anchored to a
 * character. "Before the first character" moves with the text; "0" does not.
 *
 * ## The encoding, and why it does not extend the wire format
 *
 * `Messages.ClientPresence` is shared with the server, and the server does not read these fields —
 * it copies them into `Participant` and relays them. Adding a `side` field would still mean a
 * Java change, a version skew during a rolling deploy, and a new nullable field on a record whose
 * whole documentation is that it is the protocol. So the side travels in the LAMPORT field, which
 * is already a number and already nullable:
 *
 * - `lamport >= 0` — the caret is AFTER the character `(replicaId, lamport)`. Every frame any
 * previous version of this client ever sent is read correctly by this rule, which is the point:
 * two clients of different vintages in the same document must not disagree about where a cursor
 * is;
 * - `lamport < 0` — the caret is BEFORE the character `(replicaId, -lamport - 1)`;
 * - `null` — there is nothing to anchor to. An empty document, where there is exactly one place a
 * caret can be, or a position the local replica cannot resolve.
 *
 * `-lamport - 1` rather than a plain sign flip, because **lamport 0 exists**: the clock starts at
 * zero, so the first character of a document has lamport 0, and `-0 === 0` in JavaScript. A sign
 * flip would have made a caret before the very first character of a document indistinguishable
 * from one after it — that is, wrong in exactly the case this whole change is about. The offset by
 * one makes the mapping total.
 *
 * This is a packed field and it is worth saying plainly what that costs: anyone reading a presence
 * frame in a network inspector sees `-4` and cannot guess what it means without this file. That is
 * the price of not changing a protocol shared with another runtime, and it is paid in one place
 * with the encoding and decoding side by side.
 */
export interface CursorAnchor {
  readonly charId: Id
  readonly side: 'before' | 'after'
}

/** The pair of numbers that goes on the wire, or null when there is nothing to anchor to. */
export function encodeCursorAnchor(anchor: CursorAnchor | null): Id | null {
  if (anchor === null) return null
  return {
    replicaId: anchor.charId.replicaId,
    lamport: anchor.side === 'after' ? anchor.charId.lamport : -anchor.charId.lamport - 1,
  }
}

/** The inverse. `null` in gives `null` out — the two nullable fields always travel together. */
export function decodeCursorAnchor(
  replicaId: number | null,
  lamport: number | null,
): CursorAnchor | null {
  if (replicaId === null || lamport === null) return null
  return lamport >= 0
    ? { charId : { replicaId, lamport }, side: 'after' }
: { charId: { replicaId, lamport: -lamport - 1 }, side: 'before' }
}
