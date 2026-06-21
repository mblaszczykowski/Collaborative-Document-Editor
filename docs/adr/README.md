# Architecture Decision Records

A record of this project's architecture decisions. Every ADR has the same shape:

**Context** → **Options considered** → **Decision** → **Consequences (including negative ones)**

The rule: the "Negative consequences" section is never empty. If I cannot see a downside in the
option I chose, that means I do not understand it well enough — not that there is no downside.

Where a consequence cannot be predicted before implementation, the ADR says plainly **"to be
verified in CP-X"** rather than manufacturing certainty. After a checkpoint closes I come back to
the ADR and append what actually happened — including the cases where the decision turned out worse
than I had assumed.

| ADR | Decision | Status |
|---|---|---|
| [0001](0001-crdt-vs-ot.md) | RGA (CRDT) instead of OT | Accepted |
| [0002](0002-transport.md) | WebSocket + STOMP | Accepted |
| [0003](0003-change-history.md) | Event log as source of truth + snapshots as a cache | Accepted |
| [0004](0004-horizontal-scaling.md) | Backend instance as a CRDT replica, Redis as an unreliable fast path | Accepted |
| [0005](0005-tombstone-gc.md) | GC based on causal stability, with explicit rejection of very stale clients | Accepted |
| [0006](0006-editor-layer.md) | CodeMirror 6 as the view layer, our own document model | Accepted |
| [0007](0007-ui-scope.md) | The mockup's visual language accepted, its functional scope rejected | **Superseded by [0008](0008-scope-extension.md)** |
| [0008](0008-scope-extension.md) | Widening the scope from artifact to product | Accepted |
| [0009](0009-permission-model.md) | Ordered roles, enforced in both layers from one rule | Accepted |
| [0010](0010-rich-text.md) | Formatting as a separate CRDT layer (Peritext), not a character attribute | Accepted |
| [0011](0011-comments.md) | The comment anchor in the CRDT, the conversation outside it | Accepted |
| [0012](0012-structural-authorization.md) | Authorization declared at the endpoint and enforced by construction — an endpoint that declares nothing is not served | Accepted |
| [0013](0013-deterministic-simulation.md) | Deterministic simulation as a third testing pillar — a seed instead of a thread interleaving | Accepted |
| [0014](0014-invitations.md) | Sharing by invitation link carrying a role, because passwordless login leaves nobody to name | Accepted |
| [0015](0015-suggestion-mode.md) | Suggestions as marks over the real text — the third use of the CP13 layer, and the first the layer did not anticipate | Accepted |
| [0016](0016-block-structure.md) | Block structure as marks over a line, with no move operation — and the duplication that costs, pinned by a test | Accepted |

Filenames keep their original Polish slugs. Renaming them would break every link in the README, in
the commit history and in the other ADRs, in exchange for cosmetics — and the commit history is
part of the artifact here.
