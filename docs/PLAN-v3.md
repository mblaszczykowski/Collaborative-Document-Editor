# Plan v3 — from vigilance to construction

> **Status: CP16–CP20 closed, §0 mostly not. The final audit is in [§10](#10-final-audit--after-cp20).** The successor to [`PLAN-v2.md`](PLAN-v2.md) (CP10–CP15,
> closed) and [`IMPLEMENTATION_PLAN.md`](IMPLEMENTATION_PLAN.md) (CP1–CP9, closed).
>
> | Checkpoint | State |
> |---|---|
> | §0 — Reach (hosting, language, condensing the README) | **~15 %** — see the [audit](#101-not-done--and-this-is-the-most-important-part-of-this-audit) |
> | CP16 — Structurally enforced authorization | **closed** — a fourth hole of the same class was found along the way |
> | CP17 — Deterministic simulation | **closed** — 806 invocations in < 1 s (**1207 today**, see §10.4); zero bugs in the system, three in the simulation itself |
> | CP18 — A block without content, snapshot format v3 | **closed** — GC reclamation from 0 % to 100 % |
> | CP19 — A performance regression gate + security automation | **closed differently** — a gate on the shape of the complexity, because a threshold in milliseconds could not be defended |
> | CP20 — Sweeping in `MarkSet` + outstanding debts | **partially closed** — `MarkSet` and `retry-after` done, four debts not |
>
> The rules for keeping this document are carried over from v1 and v2 unchanged: if a decision from
> the plan turns out badly in practice, we fix it **here** and commit the plan change together with
> the reason.
>
> The "final audit" section (§10) is **filled in**: a point-by-point pass against the code. In v2
> such a pass found an open history endpoint; here it found a fourth hole of the same class before
> the first checkpoint, one claim in the README refuted by our own measurement, and seven defects in
> code written under this plan.

---

## The thesis — how v3 differs from v2

v1 was a **narrow artifact**: one document, one problem, maximum depth. v2 was a **change of
thesis**: rich text, comments, permissions, many documents — proof that the core can carry a
product.

v3 **does not change the thesis and adds not one product feature.** It has a single organising
theme, and all six items satisfy it without exception:

> **Wherever the system today relies on somebody remembering, noticing or happening to hit
> something — replace it with a construction that does not allow otherwise.**

| Today we rely on… | v3 replaces it with… |
|---|---|
| somebody cloning the repository and running `docker compose` | a working link (§0) |
| the author of a new controller remembering to check permissions | an architecture test that fails the build (CP16) |
| the chaos test **happening to hit** a bad event ordering | a seed that reproduces it (CP17) |
| GC reclaiming something, even though the measurement says it reclaims nothing | a representation in which a block gives memory back without being collected (CP18) |
| somebody noticing that a number in the README got worse | a CI gate comparing against a baseline (CP19) |
| documents staying small, because `MarkSet` slows down on large ones | `O(m log m)` complexity instead of `O(m²)` (CP20) |

This is the same principle the project already applied once and described in the README: *"a
protection resting on somebody remembering a point from a document is not a protection"*. v3
extends it from authorization to the whole system.

**What v3 deliberately does not do: it adds no scope.** the scope extension
set the condition that every new feature has to bring its own engineering problem. After CP15 there
were no features left satisfying that condition — what was left were **closures**, and that is this
entire plan.

---

## 0. Reach — before CP16 begins

This section is not engineering and deliberately stands before the checkpoints. The reason is
uncomfortable but true: **everything v1 and v2 built is today available only to somebody who clones
the repository and waits a few minutes for Maven.** A reviewer with three minutes will not do that.
The best-proven thesis in the world does nothing if nobody reaches the point where it can be seen.

### 0.1 A hosted instance

PLAN-v2 §11 lists this as not done (*"Docker Compose brings the whole thing up with one command;
there is no hosting"*). To do: one instance on Fly.io or Railway, with a link **above the first
GIF** in the README.

**Three things to settle at the same time, because otherwise hosting does more harm than good:**

1. **Cold start.** Free plans put containers to sleep. The first request after a sleep is 10–30 s
   of a white screen — exactly the interval after which a reviewer closes the tab, and **a worse
   impression than no link at all**. Either the cheapest plan without sleeping, or a pinger, or a
   deliberate decision not to. The GIFs stay in the README regardless, as plan B.
2. **Demo data.** A public instance with one document that everybody types over becomes unreadable
   within a week. A reset is needed (`tools/reset-demo-data.sh` already exists), run on a schedule.
3. **The token secret.** `TokenService` has a default secret of
   `rozwojowy-sekret-nie-do-produkcji`. On a public instance it has to arrive from an environment
   variable — otherwise **anyone who reads the repository can mint a token for any user**. This is
   the only point in this section that is a real hole rather than a matter of perception.

### 0.2 The language decision

The README, all the ADRs, the code comments and the commit messages are in Polish. That is not a
flaw — it is **a fork in the road that has to be chosen deliberately rather than by inertia**:

| Choice | Consequence |
|---|---|
| Stay in Polish | The Polish market. Comments of this quality in Polish are a differentiator, not a burden |
| Everything in English | The remote market. The cost: rewriting ~4 000 lines of documentation, with a risk of losing precision |
| **README and decision index in English, the rest in Polish** | Signals bilingualism rather than hiding the depth. Cost ~1 day |

The recommendation: the third option if the goal is the remote market; the first if it is Poland.
**There is no good answer here without knowledge this document does not have** — the decision
belongs to the author and should be written in here, together with the reason.

#### THE DECISION (taken by the author): **English**, the second option

The goal is the remote market, so the language stops being a differentiator and starts being a
filter that cuts readers off. The **full option was chosen, not the compromise** — because a
repository in two languages at once reads worse than in either of them alone: a reader arriving
from an English README at a Polish comment in the code gets the signal "this is where what was
written for you ends".

**The order of migration — from most to least frequently read**, because a migration of this size
does not happen in one commit and the repository **will** be inconsistent along the way:

| # | What | Why in this order |
|---|---|---|
| 1 | `README.md` | The front door. 100 % of visitors read it; a fraction read the rest |
| 2 | `docs/adr/` | The second place the README leads to, and the main evidence of deliberate decisions |
| 3 | Code comments and javadoc | The largest volume, but a reader only gets here after 1 and 2 |
| 4 | `docs/architecture.md`, `docs/crdt-design.md` | As above |
| 5 | The plans (`IMPLEMENTATION_PLAN.md`, `PLAN-v2.md`, this file) | **Last, and perhaps not at all.** This is a historical series — a record of what was thought at a given moment. Translating a historical record carries the risk of "improving" conclusions that read differently at the time |

**New content from this commit onwards is written in English** — code, tests, ADRs and commit
messages. This document stays in Polish per row 5, as part of the series with v1 and v2.

**A negative consequence, accepted deliberately:** for the duration of the migration the repository
is bilingual, that is, in exactly the state described above as the worst. That is a cost of
transition rather than a target state — but if the migration stalled halfway, that would be the
worst possible outcome of this decision. A checkpoint: if rows 1–3 are not done after CP20, **it is
better to go back to Polish entirely** than to leave the repository halfway.

> **UPDATE (2026-08-16): row 5 was superseded.** The author decided the migration was to be
> complete — the plans included — and that anything left in Polish was a mistake rather than a
> deliberate historical record. So this document is now in English too, and the "perhaps not at
> all" above describes a decision that no longer holds. The risk named in that row is real and the
> mitigation was to translate rather than rewrite: the conclusions stay as they were, including the
> ones that later proved wrong.

**Why this does not get a decision record:** those describe architectural decisions. The
language of the documentation and the choice of hosting are editorial and operational decisions.
Writing one for everything is the same mistake as applying a CRDT where there is no concurrent
editing (the comment split) — a cost with no benefit.

### 0.3 Condensing the README

The README is **630 lines**, and the "what I would do differently in production" section has about
thirty points. That section is one of the strongest elements of this repository and **must not be
removed or cut in substance.** But there is a threshold above which honesty about boundaries starts
to read as a list of things that do not work, and thirty points is past that threshold.

To be done without losing a single sentence: the 4–5 most interesting items stay on the surface,
the rest go into a `<details>`. A purely editorial change, with a different reception.

**After CP16–CP20 that section needs a pass anyway**, because five of its items will be closed. The
house convention applies: **strike through, do not delete**, together with what was confirmed along
the way and what turned out to be untrue.

---

## 1. The order, and why this one

Only one dependency is hard; the rest is an ordering by return:

```
§0    Reach ─────────────────────┐  It blocks nothing technically, but every day
      (hosting, language, README)│  without a link is a day in which finished work
                                 │  is invisible. Hence first, not last.
                                 ▼
CP16  Authorization ─────────────┐  A HARD DEPENDENCY, and a double one:
      structurally               │  (a) public hosting without it exposes a
                                 │      permission model resting on the author's
                                 │      vigilance — which has already failed
                                 │      twice (CP11, HistoryController);
                                 │  (b) CP18 touches the block representation, so
                                 │      we want the access tests set in concrete
                                 │      BEFORE the core moves.
                                 ▼
CP17  Deterministic ─────────────┐  Before CP18, not after. DST is to be a tool
      simulation                 │  FOR redesigning the block, not a way of
                                 │  checking after the fact whether it worked.
                                 │  The reverse order wastes its one advantage:
                                 │  reproducible failures.
                                 ▼
CP18  A block without content ───┐  The only change to the core in the whole plan.
      (snapshot v3)              │  After it, every performance number describes
                                 │  a different system — exactly as after CP13.
                                 ▼
CP19  A regression gate ─────────┐  It has to come AFTER CP18, because a baseline
      + security                 │  taken before the core changed would be the
                                 │  baseline of a system that no longer exists.
                                 ▼
CP20  MarkSet + debts               Independent of everything above. Deliberately
                                    last: it is the one checkpoint that can be cut
                                    painlessly if the budget runs out.
```

**Sensible stopping points** if the budget did not stretch to the whole: the end of CP16 (a project
safe to expose publicly) or the end of CP17 (a new testing pillar, a coherent whole). **Stopping in
the middle of CP18 is not a stopping point** — the block representation would be out of step
between Java and TypeScript, and the conformance corpus would stop protecting anything.

---

## 2. CP16 — Structurally enforced authorization (2–2.5 days)

### 2.1 The problem

Authorization is today checked in **about fifteen places across four files**:
`DocumentRestController`, `HistoryController`, `CommentController`, `DocumentAccessInterceptor`.
Every check on its own is correct. Nothing forces the next one to exist.

This class of bug **has bitten this project twice**:

1. CP11 opened an IDOR (knowing an identifier = access to the document), and CP12 closed it in two
   layers;
2. `HistoryController` **checked no permissions at all** for a whole checkpoint — three endpoints,
   including a full read of somebody else's document content and overwriting it. Only the plan's
   final audit found it, even though the plan named that path explicitly.

The README names the right solution, and names it well:

> *"one rule for every `/api/documents/{id}/**` path, enforced by a filter or `@PreAuthorize` —
> that is, a place where skipping the check requires a deliberate decision rather than an
> oversight"*

### 2.2 The decision — and why a step further than the README says

Centralisation alone is **not sufficient**, because it does not defend against an endpoint that
simply does not match the path pattern. That is exactly how `HistoryController` disappeared: it was
mounted under `/api/documents/{documentId}/history`, that is, in a place a rule would have covered
— had the rule existed. But the next one may land under `/api/threads/...` and again nobody will
notice.

So CP16 is **two things, not one**:

**(a) One rule.** A filter or `@PreAuthorize` on `/api/documents/{id}/**`, resolving the role once
and putting it into the request context. The controllers stop calling `PermissionService` and start
reading a role that is already there.

**(b) An architecture test that fails the build.** ArchUnit (a new dependency, test-scope only):

- every method annotated `@GetMapping`/`@PostMapping`/… in the `app` package is **either** covered
  by the path rule **or** carries an explicit `@PublicEndpoint` annotation with a justification;
- the same for `@MessageMapping`;
- the list of public endpoints is **spelled out in the test**, so adding a new one requires adding
  it there — a deliberate decision, exactly as the README wants.

Three endpoints are legitimately public today and go on that list: `POST /api/login`,
`GET /api/documents` (it filters by permission internally, because a list has to return "what you
can see" rather than a 403), and `POST /api/documents`.

**the authorization guard — structurally enforced authorization.** To be written before the code, in the same
layout as the others. The negative consequences to describe honestly, because they are real:

- a path rule is **less legible at the point of use** than an explicit `if` in the controller — a
  reader of the controller no longer sees what protects it;
- `commenter` requires looking inside the frame's body (README, "authorization looks inside the
  frame"), and a path rule does not solve that — it stays in the interceptor and stays a debt;
- ArchUnit is a dependency that **can fail the build because a package was renamed**, that is, a
  false alarm in exchange for the true ones.

### 2.3 Found while writing this plan — `POST /api/documents/recovered`

**Checked against the code, not suspected.** This endpoint sets the owner and `private` visibility,
but **only inside `if (caller != null)`**. And `caller` is `null` more often than it looks:

1. `TokenAuthFilter` **by design does not reject** requests without a token — *"it only establishes
   identity; the decision to refuse belongs to the endpoint"*. That is a deliberate and
   well-documented decision.
2. This particular controller **makes no decision to refuse** — there is no check here at all.
3. So with an absent **or expired** token the document is created with no owner, and `visibility`
   takes the schema default: **`'link'`** (`V3__permissions.sql:21`).
4. And rule 4 in `PermissionService` says: `link` visibility → **`EDITOR` for every logged-in
   user**.

So a draft that commit `b5c6ba1` made private ("a recovered draft is not public") **goes back to
being editable by everyone, as long as the request arrives without a valid token**. The fix closed
the authenticated path and missed the unauthenticated one.

**Why this is not a theoretical scenario — and this is the interesting part.** A token lives 12
hours (`TokenService.LIFETIME`). This endpoint exists **solely to serve a client that was offline
longer than the GC window** (README, the `RESYNC_REQUIRED` failure mode, a 7-day window). The
population the endpoint was written for is therefore exactly the population whose token has
certainly expired. The unauthenticated path is not an edge case here — it is **the typical case**.

The effect is not a leak of somebody else's data (the caller supplies the content) but **the
inverse of `b5c6ba1`'s intent**: private work lands as a document open for anyone to edit, at the
moment the user is worried about losing it. On top of that, the endpoint allows creating documents
**with no authentication whatsoever**.

**This is the third instance of the same class of bug** (after the CP11 IDOR and
`HistoryController`), and the best possible argument for why CP16 comes first in this plan: a
review found it, not a test — that is, vigilance again rather than construction. The rule from §2.2
covers `/api/documents/**`, so it closes this **without a separate fix**; the test with the list of
public endpoints keeps anybody from opening it again.

### 2.4 Tests

| Test | The real failure mode it catches |
|---|---|
| `everyDocumentScopedEndpointIsCoveredByTheRule` (ArchUnit) | **A new controller starts with no authorization and says nothing about it** — exactly `HistoryController` in CP14 |
| `everyMessageMappingIsCoveredByTheRule` (ArchUnit) | As above for the WebSocket path CP12 closed |
| `publicEndpointsAreExactlyTheseThree` | Somebody adds an endpoint to the public list to silence the test — the list is explicit, so it shows in the diff |
| `recoveredDraftIsPrivateEvenWithoutAToken` | **Found in §2.3.** An expired token makes a recovered draft come out as `link`, that is, editable by everyone — the inverse of `b5c6ba1`'s intent. The test has to run **without an `Authorization` header**, because only that path is broken |
| `roleIsResolvedOncePerRequest` | A performance regression: the rule and the controller each ask `PermissionService`, twice per request |
| the whole existing `PermissionIT` | **An access regression introduced by the refactor itself** — that is this checkpoint's main risk, see §8 |

**Mutation verification, per the house convention:** removing the rule has to fail `PermissionIT`,
not only the architecture test. If it fails only ArchUnit, the rule is not wired to anything that
matters.

---

## 3. CP17 — Deterministic simulation (4–6 days)

### 3.1 The problem — best seen in `nightly.yml`

The nightly run executes `ClusterChaosIT` **ten times in a row** and looks for non-determinism. The
comment in the workflow is honest: *"a test that passes once may have passed by chance; a test that
passes ten times in a row is much stronger evidence"*.

Evidence — not proof. And more importantly: **the failure, when it eventually happens, is not
reproducible.** The outcome depends on thread scheduling and on the runner's load. Debugging such a
run means running it in a loop and hoping.

This is the one place in this project where testing rests on happening to hit something. The
property tests in `crdt-core` no longer have that problem — they have a fixed seed and determinism,
and that is exactly the property the distributed layer lacks.

### 3.2 The decision — a third testing pillar

The approach from FoundationDB and TigerBeetle: **a single-threaded, deterministic scheduler in
which a seed drives everything that is chance today** — the order of message delivery, the delays,
network partitions, the moment an instance dies and comes back, the moment of a database write.

The consequence that makes it worth doing: **a failed run stops being "one in ten" and becomes
`@Test void seed_8471293()`** — a one-line regression test reproducing exactly the same event
ordering on any machine.

**Half the pieces are already there**, and that is the main argument for it fitting in 4–6 days:

| Present | To be written |
|---|---|
| `ReplicaSet`, `EditScript` — driven replicas and edit scripts | An event scheduler with a priority queue over virtual time |
| `simulator.ts` — a client-side simulator of delays and packet loss | Its server-side counterpart, driven by a seed |
| `ClusterChaosIT` — the Redis-stop scenario | The same scenario as an **event in the schedule**, not as a `Thread.sleep` |
| `RgaInvariants` — invariants to check | Checking them **after every event**, not only at the end |

### 3.3 Scope — close it narrowly, then extend

This is the same risk that applied to CP13 in v2, and the same mitigation: **close it on one
scenario with full determinism, and only then extend.** Scenarios are cheap, the scheduler is
expensive.

The first scenario: **a Redis partition while N replicas are typing** — that is, exactly what
`ClusterChaosIT` does today, only reproducibly. Only after that: an instance crash, a delayed
write, a client returning after the GC window.

**A hard condition for passing the checkpoint, without which this is merely a prettier chaos
test:** the same seed has to produce a **byte-for-byte identical event log** on two different
machines and under two different loads. A test enforcing that matters more than all the scenarios
put together — because without it the entire advantage disappears and nobody notices.

**What it does not replace:** the simulation touches neither a real Postgres, nor a real Redis, nor
a real network. `ClusterChaosIT` on Testcontainers **stays** — the simulation looks for bugs in
distributed logic, the integration tests make sure the model matches reality. Replacing one with
the other would be the same mistake as treating a snapshot as the source of truth.

**the deterministic simulation — deterministic simulation as the third testing pillar.** The negative consequences to
describe: production code has to take its clock and its source of randomness from outside (`Clock`,
`RandomGenerator`) — that is, **testability leaks into the signatures**; the simulator is a second
implementation of the transport layer, so **the same debt as with two CRDT implementations**, only
smaller; a seed protects against non-determinism in the simulation, not against non-determinism in
the JVM (GC, JIT) — and that is a boundary this checkpoint does not cross.

### 3.4 Tests

| Test | The real failure mode it catches |
|---|---|
| `sameSeedProducesIdenticalEventLog` | **The simulation is not deterministic** — that is, the whole checkpoint does not work while looking as though it does |
| `redisPartitionUnderSeed(...)` × 1000 seeds | An event ordering `ClusterChaosIT` did not hit in ten runs |
| `invariantsHoldAfterEveryEvent` | A **transient** divergence — the structure is wrong between two events and correct at the end, so a final check does not see it |
| `knownFailingSeedStaysFixed` | A regression of a fixed bug — that is the item this whole checkpoint is done for |

---

## 4. CP18 — A block without content, snapshot format v3 (3–5 days)

### 4.1 Why this stopped being optional

The tombstone-collection design, after the CP15 measurement, says outright: *"the direction
does not change, but it **stops being optional**"*. Measured: **0 % GC reclamation** on text
written in one continuous run, 80 % on text typed character by character. The same implementation,
the same text, the same marks — the only difference is the typing history.

The cause is in `RgaDocument.gc` (line 492) and in `isAnyCharReferenced` (line 540): **GC iterates
over blocks, and the reference condition is a condition on a block rather than on a character.** One
anchor saves a whole run-length block.

One change closes **two** documented limitations at once:

1. "GC reclaims less than the tombstone-collection design assumes" — a tombstone in the middle of the text is the origin
   of the next block, so it must not be removed;
2. "A formatting anchor blocks the WHOLE run-length block" — measured in CP15.

Both have the same cause and the same answer, already indicated in the tombstone-collection design:

> **All three protection conditions need a character's IDENTITY, not its content.**

### 4.2 The change

Today `Block` (`crdt-core/.../Block.java`) holds an `Id start`, a `String text`, an `Id originLeft`
and a `boolean deleted`, with the invariant **"a block cannot be empty"** and `length()` computed
as `text.length()`.

After the change a deleted block **drops its content while keeping its identity and length**. The
invariant changes from "a block cannot be empty" to **"a block has a positive length, but a deleted
block may have no content"**. `length()` becomes a field rather than a function of the text.

That lets a block **give memory back without being collected** — and then the whole difficulty from
§4.1 disappears, because it no longer has to be removed from the structure to reclaim space.

**What it touches — the full list, because the cost here is more interesting than the change:**

| Place | Why |
|---|---|
| `Block`, `RgaDocument.gc` | the core of the change |
| `TreapBlockList` | the aggregates are computed from `length()`, which stops coming from the text |
| `ArrayBlockList` | as above; the two implementations still have to agree (`BlockListEquivalenceProperties`) |
| `SnapshotCodec`, `DocumentSnapshot` | `CURRENT_FORMAT_VERSION` 2 → 3 |
| `frontend/src/crdt/block.ts`, `document.ts` | **the TypeScript twin** — the debt from the README materialises here for the second time |
| `conformance/` | the corpus needs regenerating; the CI job keeps it from drifting |

**Data migration is free, and that is no accident.** On a version mismatch `SnapshotCodec`
**ignores the snapshot rather than migrating it** (the operation-log decision: a
snapshot is a disposable cache), so the state is rebuilt from the operation log. The real cost of
this checkpoint is the TS twin and the treap's aggregates, not the data — and that is worth writing
down, because the decision from CP5 ("a snapshot is a cache, not a source of truth") **pays
off here for the second time**.

### 4.3 Settling up with the missed opportunity

The tombstone-collection design admits that the cheapest moment for this change — bumping the format to version 2 in CP13
— **was missed**, because the measurement was absent. The measurement exists now, it came out
badly, so the opportunity returns at version 3.

To be added to the tombstone-collection design once CP18 closes, per the house convention: **whether deferring the
decision actually cost what was assumed.** If it turns out doing it in CP13 would have been
cheaper, that is a conclusion worth recording. If it turns out that without the measurement the
change would have gone the wrong way, that is a more interesting conclusion still.

### 4.4 Measurement — mandatory, as in CP15

This checkpoint **is not closed without repeating `MarkCostMeasurement`.** The same table (1000
characters, the text deleted, 100 marks, a stable version vector), the same two extremes, an "after
CP18" column next to the existing ones.

**To be verified:** whether reclamation on text written in one continuous run rises from 0 % to
something close to 100 %. If not, the hypothesis in the tombstone-collection design is wrong and that has to be written
down plainly, rather than fitting the interpretation to the result. That has already happened once
in this project (an RGA scan condition believed necessary, refuted by 200 000 scenarios) and the
honest reckoning was the most interesting part.

### 4.5 Tests

| Test | The real failure mode it catches |
|---|---|
| `droppedContentBlockKeepsIdentityAndLength` | A block that has dropped its content stops being indexable — cursor positions fall apart |
| `gcReclaimsRunLengthTextWithAnchors` | **The goal of the whole checkpoint.** Without it there is no reason to do it |
| `naiveAndIndexedProduceIdenticalDocuments` (existing) | The treap's aggregates not updated after the source of `length()` changed |
| `snapshotV2IsIgnoredNotMigrated` | A snapshot in the old format read "somehow" and quietly corrupting the state |
| `garbageCollectionSparesMarkAnchors` (existing) | A regression in anchor protection — this test already once in this project **did not check what it claimed**, so mutation verification is mandatory |
| Java ↔ TS conformance | The TypeScript twin did not get the same change |

---

## 5. CP19 — A regression gate + security automation (1–1.5 days)

### 5.1 The README's numbers will rot silently

PLAN-v2 §9 lists this as *"to consider: a CI job comparing the harness against a recorded result"*.
Not done — and a project whose entire thesis is "measure, do not declare" today has **two dozen or
so numbers in the README and nothing keeping them from becoming untrue.** The first change that
makes them worse will pass unnoticed, and the README will become exactly what `CLAUDE.md` warns
against: documentation reconstructed from memory.

To do: a baseline in the repository (a file, not state in a service — just like the Grafana
dashboard), a nightly job running `LoadHarnessIT`, a comparison, and **failing the build on a
regression above a threshold**.

**The threshold has to be justified rather than guessed**, otherwise it breaks the `CLAUDE.md` rule
about default configuration with no comment. A GitHub runner is shared and variable — `nightly.yml`
says so itself about the JMH benchmarks (*"its results are not comparable between days in any
defensible way"*). The conclusion: **derive the threshold from the spread measured on the runner**,
not from a number pulled out of the air. Concretely — ten baseline runs, a standard deviation, and
a threshold above the spread. If the spread turns out so large that the threshold would have to be
100 %, **the gate makes no sense on a runner and that has to be written down** rather than
installing a dead job.

That is a real risk of this checkpoint, not a hypothetical one — and that is why the estimate is
low: the outcome may well be "measured, cannot be done, here is why". That is a result too.

### 5.2 Security automation

No Dependabot, no CodeQL, no dependency scanning. For a project whose narrative contains **two
fixed access-control holes**, that is a gap costing a few hours
and closing a thread that is already told.

- `dependabot.yml` — Maven, npm, GitHub Actions;
- CodeQL for Java and TypeScript;
- a check of whether there is anything to fix in the current dependencies (Spring Boot 3.5.0,
  React 19, Vite 8).

No decision record — this is a CI convention, not an architectural decision. The justification for the
thresholds and the choices goes into comments in the workflow files, as in `ci.yml` and
`nightly.yml`.

### 5.3 Tests

This checkpoint adds no tests — **it is itself a test.** Verification consists of checking that the
gate actually fires on an artificially introduced regression (a `Thread.sleep(5)` in the merge
path, say). A gate that has never fired on a known regression is the same thing the `*Measurement`
class that never ran was: a green build with no content.

---

## 6. CP20 — An interval tree in `MarkSet` + outstanding debts (2–3 days)

### 6.1 `MarkSet` — O(m²) measured, not suspected

`MarkSet.java:36-42` documents its own complexity, and CP15 measured it: **0.6 ms at 100 marks,
5.3 ms at 1600** (a 50k-character document). It is called **on every keystroke**, so the last row
is visible latency. The usability boundary: hundreds of marks per document.

The solution is named in the code and in the README: **a sweep with a heap of active ranges,
`O(m log m)`**, eventually an interval tree recomputed incrementally. Start with the sweep — it is
cheaper, and the difference shows in the same measurement.

**The closing condition:** the same measurement table as in CP15, with an "after CP20" column.
Without it this checkpoint is an optimisation with no proof, which is exactly what this project
avoids.

### 6.2 `retry-after` on rate limiting — it improves a number that is ALREADY in the README

The server (`DocumentAccessInterceptor.notifyRateLimited`) replies `RATE_LIMITED` **with no
information about how long to wait**. The client (`connection.ts`,
`scheduleRetryAfterThrottling`) waits a **hardcoded 1000 ms**, and the README names that outright
as the cause of the fat tails:

> *"The tails (p99) here are markedly fatter than in the previous measurement, and that is THE
> PRICE OF RATE LIMITING (…) refused operations are retried after a second, and that second is what
> the p99 shows."*

`RateLimiter.Bucket` **knows the exact time to the next token**: `(1.0 - tokens) / refillPerMs`
milliseconds. It is enough to send that number back in the `ErrorMessage` and to respect it on the
client instead of a constant.

This is the cheapest item in the whole plan with the best effort-to-effect ratio: **it improves a
number that is already published** (p99 1299 ms at 50 clients on a 100k-character document) rather
than adding a new one. The behaviour stays correct — nothing is lost — but the wait stops being a
guess.

**To be verified:** whether p99 actually falls. If not, the second was not the dominant component
of the tail and the README claims something that was not measured to the end — which is itself a
result worth recording.

### 6.3 The remaining debts from the README

Ordered by what they actually cost the user:

| Debt | Where | What to do |
|---|---|---|
| **The client replays the whole log on open** | README, "Infrastructure" | The server uses snapshots internally; the client should receive a serialised snapshot. This is the item that **hurts a real user** on a large document |
| **`SYNC_CHUNK` counted in operations, not bytes** | `DocumentStompController:209` | The broadcast path moved to a byte budget in CP10; resynchronisation did not. Pasting a large fragment is **one** operation of tens of kilobytes |
| **Presence is local to an instance** | README | Redis is already there. A separate channel plus entry expiry. A visible product gap: somebody on another instance is invisible |
| **The client's index is O(n)** | README | Porting the treap is mechanical — **the interesting part is the missing half: a measurement in the browser.** Without it this is optimising blind, which is what this project does not do |
| `App.tsx` — 655 lines | `frontend/src/App.tsx` | The one place where code quality visibly slips relative to the backend |

**This checkpoint is deliberately last and deliberately divisible.** If the budget runs out it can
be cut from the bottom with no harm to the rest of the plan — unlike CP18.

---

## 7. Estimate

| Checkpoint | Days |
|---|---:|
| §0 — Reach | 1.5–2 |
| CP16 — Structural authorization | 2–2.5 |
| CP17 — Deterministic simulation | 4–6 |
| CP18 — A block without content | 3–5 |
| CP19 — A regression gate + security | 1–1.5 |
| CP20 — `MarkSet` + debts | 2–3 |
| **Total** | **13.5–20 working days** |

For comparison: v1 ≈ 13–17 days, v2 ≈ 15.5–21 days. **v3 is a third project of the same size** —
with the difference that it adds not one feature. Worth knowing before starting: a plan in which
nothing visible accumulates is harder to carry to the end than a plan in which something new works
every other day.

**If the budget is one item:** §0. **If one engineering item:** CP17.

---

## 8. Risks

| Risk | Likelihood | Mitigation |
|---|---|---|
| **The authorization refactor introduces an access regression** — that is, CP16 opens exactly what it is meant to close | Medium | `PermissionIT` passes **before and after**, with no changes to the tests themselves. Mutation verification after the refactor, not before |
| **CP17 overruns** — a deterministic scheduler is the hardest thing in this plan | **High** | Close it on ONE scenario (a Redis partition) with full determinism. Scenarios are cheap, the scheduler is expensive. The same mitigation as for CP13 in v2 |
| **The simulation turns out to be non-deterministic** and nobody notices, because the tests pass | Medium | `sameSeedProducesIdenticalEventLog` **before** the first scenario, not after. Without it the whole checkpoint is a prettier chaos test |
| **CP18 diverges Java from TypeScript** | Medium | The conformance corpus plus the existing CI job. The mechanism has been proven twice (v1, CP13) |
| **The regression gate cannot be defended on a runner** (the spread exceeds the threshold) | Medium | Measure the spread BEFORE installing the gate. A result of "it cannot be done, here is why" is an acceptable close for CP19 — a dead job is not |
| **Hosting sleeps and the first impression is worse than no link** | Medium | Settle it in §0.1 before publishing the link, not after. The GIFs remain as plan B |
| **A public instance with the default token secret** | Low, high impact | §0.1 point 3 — the secret from an environment variable. To be checked **before** the first deploy |
| **A plan with no visible effects loses momentum** halfway | Medium | §0 produces a visible effect in the first week. CP20 is divisible and can be used as a springboard |

---

## 9. What this plan deliberately does NOT cover

- **Model checking (TLA+ / Stateright).** Tempting and thematically apt — the README tells the
  story of *"200 000 random scenarios showed the condition was not necessary"*, that is, a
  conclusion from **sampling**, to which a model checker answers exhaustively. Rejected because it
  **answers the same class of question as CP17**, and doing both on this budget is redundant. DST
  wins because it covers **the whole distributed system** (transport, persistence, instance
  crashes) rather than the merge rule alone, and because half its pieces already sit in the
  repository. **The strongest candidate for v4** — and the only item on this list I think of that
  way.
- **Fugue instead of RGA.** The interleaving anomaly is documented and pinned by a test
  (`RgaInterleavingTest`). Rewriting the core trades a strong "I understand the trade-off and can
  name it" for a weaker "I have a slightly better algorithm", at the cost of weeks. Unchanged from
  v2.
- **One implementation compiled to WebAssembly.** It would remove the debt of two implementations,
  but the conformance corpus **already handles the risk** WASM would eliminate — and the cost is on
  the order of a whole plan.
- **An access management screen and an invitations table.** Listed in the v2 audit as not done.
  `grant`/`revoke`/`setVisibility` exist and are tested; all they lack is a screen. That is UI work
  with no engineering content — exactly what `CLAUDE.md` calls excessive scope. It stays in the
  README as a boundary.
- **Product features** — notifications, mentions, export, mobile versions, embedded objects,
  CRDT-aware undo, offline editing of comments. PLAN-v2 §10 rejected them with a justification that
  has not stopped applying. v3 is not a product plan.
- **Splitting the measuring rig across separate machines.** The README names that boundary (clients
  and server in one process, network latency ≈ 0). Still out of scope — after CP10 it is no longer
  needed to answer any open question, and on its own it demonstrates nothing.

---

## 10. Final audit — after CP20

A point-by-point pass **against the code**, not against memory. In v2 such a pass found
`HistoryController` with no authorization at all; this time it found a fourth hole of the same
class before the first checkpoint even began — and that is the best argument that this section is
not a formality.

### 10.1 Not done — and this is the most important part of this audit

| Item | State | Why |
|---|---|---|
| **§0.1 Hosting** | **not done** | It needs an account on Fly.io/Railway and a decision about a paid plan. What could be done in the repository was done: the token secret no longer has a default, so a public instance will not start with a key known from the repository |
| **§0.2 Migration to English** | **~15 % done** | The decision is recorded and all new content is in English (the CP16–CP20 code, the authorization guard, the deterministic simulation, the commit messages). **The README, ADRs 0001–0011 and the existing comments stay in Polish.** That is exactly the state §0.2 warns against: a bilingual repository, that is, worse than either language alone |
| **§0.3 Condensing the README** | **not done** | The README grew rather than shrank — sections about CP18 and CP20 arrived, plus the correction to p99 |
| **CP20 — a CRDT snapshot to the client** | **not done** | The largest remaining product debt: the client still replays the whole log on open |
| **CP20 — `SYNC_CHUNK` in bytes** | **not done** | |
| **CP20 — cluster-wide presence** | **not done** | |
| **CP20 — measuring the index in the browser** | **not done** | |
| **CP20 — `App.tsx` (655 lines)** | **not done** | |
| **e2e tests after the changes** | **not run** | Port 8080 was held by an instance the author had started. Running them would have required either stopping it or testing a stale build — the first is interfering with somebody else's work, the second would give a misleading result. CI runs e2e on every pull request |

> **UPDATE (2026-08-16): five of the rows above were closed by [plan v4](PLAN-v4.md), and the table
> is deliberately left saying "not done".** It is the state at the close of v3, which is what a
> final audit is for; a table that silently updates itself is a table nobody can date. What v4
> closed, for a reader who arrives here first: the CRDT snapshot to the client (CP24 — measured at
> 12 158 B of log against 145 B of snapshot, **84×**), `SYNC_CHUNK` in bytes (CP28 — the constant
> is gone and `DocumentStompController.syncBudgetBytes` derives a byte budget from the frame
> limit), cluster-wide presence (CP26), and the English migration in full. Still open, and still
> honestly open: hosting, condensing the README — which grew again while these corrections were
> written — measuring the client's index in the browser, and `App.tsx`.

**The checkpoint from §0.2 fired and came out negative.** The section says: *"if rows 1–3 are not
done after CP20, it is better to go back to Polish entirely than to leave the repository halfway"*.
They are not done. The decision belongs to the author, but the plan foresaw this moment and pointed
at the way out — leaving it unresolved is worse than either of the two answers.

> **UPDATE (2026-08-16): resolved in the other direction.** The author chose to complete the
> migration rather than roll it back: the README, all the comments, the configuration
> and these plans are now in English. The row above
> stays as it was written — it is the record of a checkpoint that fired correctly, and the value of
> that record does not depend on which way the decision then went.

### 10.2 Done differently from what the plan assumed — deliberately

| Plan item | How it actually is | Why |
|---|---|---|
| CP16: "a filter or `@PreAuthorize` on `/api/documents/{id}/**`" | **Three annotations on the endpoints plus a guard that refuses to serve an undeclared one** | A path rule does not cover `/api/comments/{threadId}/…` and cannot express that restoring requires WRITE while previewing requires READ — and confusing those two is exactly how the restore endpoint stood open to readers |
| CP16: "an ArchUnit test" | **Reflection, with no new dependency** | The whole rule is one pass over the methods. The same trade-off `TokenService` makes against a JWT library |
| CP16: two annotations | **Three** | There turned out to be endpoints that CREATE a document: there is nothing to authorize, but an anonymous caller must not be able to run them. Collapsing that into "public" would have left both holes open while looking like a decision |
| CP17: "the simulation covers the whole distributed system" | **A model of the distributed layer on the REAL merge algorithm** | It touches neither Spring, nor Postgres, nor a socket. The boundary is named in the deterministic simulation; `ClusterChaosIT` stays |
| CP19: "a gate comparing the harness against a baseline" | **A gate on the SHAPE of the complexity, not on milliseconds** | Measured: this rig's own spread (28 ms versus 43 ms on the same code) exceeds the regression such a gate would have to detect. The plan foresaw this outcome as acceptable — §5.1 says outright "if the spread turns out too large, write that down rather than installing a dead job" |
| CP20: `retry-after` | **Done, and it did NOT deliver what it was meant to** | See 10.4 |

### 10.3 Found by the audit and the review — fixed

| What | Where |
|---|---|
| **A fourth access-control hole**: `POST /documents/recovered` with an expired token produced a document with no owner and `link` visibility | §2.3, fixed in CP16 |
| **The same class in `POST /documents`** — a document with no owner, that is, one nobody can ever secure | CP16 |
| **The token secret with a default value in the repository** — a total authentication bypass, silent | CP16 |
| The `crdt.gc.removed.characters.total` metric counted something other than its name says after CP18 | the final review |
| The GC log said "removed" while the block count was unchanged | the final review |
| `Block.append` on a block with no content would paste the string `null` into the document and overstate the length by four | the final review |
| `throttleRounds` on the client grew without bound and did not reset on reconnect; with `advertised == 0` that would give `setTimeout(fn, NaN)`, that is, a polling loop | the final review |
| A dead `DENIED` constant in `CommentController` | the final review |

### 10.4 What the measurement did NOT confirm

**`retry-after` did not improve p99, and on its own made it worse.** Three harness runs back to
back, 100k characters / 50 clients:

| Variant | p99 | refusals |
|---|---:|---:|
| before (a fixed 1 s) | 872 ms | 1523 |
| `retry-after` alone | **1352 ms** | 1614 |
| `retry-after` + exponential backoff | 826 ms | 1368 |

The claim in the README — *"refused operations are retried after a second, and that second is what
the p99 shows"* — is **untrue**. The tail is set by the bucket's refill rate times the size of the
queued batch; a client with N operations behind an empty bucket waits N/60 seconds however often it
asks. Retrying every 17 ms instead of every second is polling in a loop.

The change stays, because a guessed constant disappears and there are ~10 % fewer refusals — but
**it is not a latency gain and the commit does not pretend otherwise**.

**CP17 found not one bug in the system.** It found three in itself. `REGRESSION_SEEDS` is empty and
described as empty. The machine is built and verified by mutation (inverting the RGA scan condition
fails 593 of 806 tests, with a counterexample on four operations), but so far it has not earned its
keep with a real defect.

> **The two counts above are correct as of the close of v3 and are no longer the current ones.**
> The suite runs **1207** invocations today and the same mutation fails **826** of them, with a
> minimal counterexample of eight operations rather than four. Nothing regressed — the suite grew:
> CP17 shipped four `seeds`-driven scenarios (4 × 200 = 800, plus six fixed-seed cases = 806),
> and the crash-before-persist work added two more scenarios and a guard test
> (`acknowledgedWorkSurvivesCrashes`, `unacknowledgedWorkIsRetransmitted`,
> `durabilityScenarioReachesTheWindow`), which is 401 more invocations.
>
> Left in place rather than overwritten, because this is a plan audit and 806 is what was measured
> when it was written. The lesson is about the shape of the claim rather than the arithmetic: a
> mutation score is a fact about the suite **on the day it was taken**, and a number quoted in
> three documents at once (the deterministic simulation and the README carried
> it too) goes stale in all three the moment anyone adds a scenario. None of the three said when it
> was measured.

### 10.5 Measured along the way, previously unknown

**`ClusterChaosIT` is a load-dependent flake, and now that is a number.** In isolation, **6 runs out
of 6** green; in a full build it failed **2 out of 4**. This is not a regression introduced by v3 —
it failed the same way before CP17. It is, however, exactly the failure mode CP17 was created for:
a failure dependent on thread scheduling, irreproducible, disappearing when you try to investigate
it. The nightly `chaos ×10` job still hunts for it; the simulation covers the same class of bug
reproducibly, but does not touch a real Redis.

### 10.6 A lapse in keeping the history

The CP17 commit (`a6908f2`) swept in `.github/dependabot.yml` and `.github/workflows/codeql.yml`,
which its message does not mention. `CLAUDE.md` §2 requires one logical change per commit. The
history was not rewritten, because the author was working on the same branch in parallel and a
rebase would have destroyed their working state — but a lapse is a lapse, and it stands better
written down here than unfound.
