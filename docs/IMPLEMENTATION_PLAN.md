# Implementation plan — Collaborative Document Editor

> **Status: all 9 checkpoints closed.** The document stays in the repository as a record of what
> was planned — including the places where the plan did not hold. The most significant departures:
> Gradle replaced by Maven (CP1), Gatling replaced by a custom harness (CP8), and the reach of GC
> turning out to be narrower than ADR-0005 assumed (CP6). All three are described where they
> belong.

A working document. Written before the first line of code, on the basis of `idea.md` and
`CLAUDE.md`. Updated as work progressed — if a decision from the plan turned out badly in practice,
we fix it here **and** commit the plan change together with the reason (the history of iteration is
part of the artifact).

---

## 0. Starting state and blockers (to be done before checkpoint 1 begins)

| Thing | Status | What to do |
|---|---|---|
| git repository | ❌ absent | `git init` + `.gitignore` + the first commit. Without it we lose the commit narrative CLAUDE.md §2 requires |
| Java | ⚠️ 25-ea by default, 21.0.1 also present | We target **21 LTS** via `maven.compiler.release=21` — the bytecode is the same whatever Maven starts on |
| Node | ✅ v20.18.1 | Fine for Vite 5 / React 19 |
| **Docker** | ❌ **absent** | **BLOCKER.** Without it there is no: Testcontainers (requirement §3.2), local Postgres, Docker Compose, or Prometheus/Grafana stack. Needs Docker Desktop or Colima (`brew install colima docker docker-compose`) |
| ffmpeg / gifski | ❌ absent | Needed only in checkpoint 9 (the demo GIF). `brew install ffmpeg gifski` |

**Docker blocks checkpoints 3, 7, 8 and 9** — checkpoints 1 and 2 (the ADRs and the CRDT core) can
be done without it, because `crdt-core` is pure Java with no I/O. I suggest installing Docker in
parallel with checkpoint 1, so as not to stall.

---

## 1. The target architecture — one page

```
┌─ frontend (React + TS, Vite) ──────────────────────────────┐
│  CodeMirror 6 (view layer and cursor decorations only)     │
│  crdt-ts/          ← our own RGA implementation (Java twin)  │
│  transport/        ← STOMP client + offline buffer + network simulator │
└────────────────────────┬───────────────────────────────────┘
                         │ WebSocket / STOMP (traceparent in the headers)
┌────────────────────────▼───────────────────────────────────┐
│ app (Spring Boot 3, Java 21)                               │
│  ws/         StompController, presence, per-doc session reg│
│  domain/     DocumentReplica (in-memory), OpApplier        │
│  persist/    op_log (append-only) + snapshots (Postgres)   │
│  history/    time travel: snapshot + replay up to point T  │
│  cluster/    Redis pub/sub fan-out + anti-entropy from op_log │
│  otel/       tracing, Micrometer metrics → Prometheus      │
└──────┬──────────────────────┬──────────────────────────────┘
       │                      │
   PostgreSQL             Redis (fast path)
   (source of truth)      (best-effort broadcast)

crdt-core/  ← a separate Maven module, ZERO Spring dependencies.
             The pure algorithm + property-based tests (jqwik) + JMH.
```

### The key architectural decision: every backend instance is a CRDT replica

There is no "document owner", no sticky sessions and no distributed lock. Every instance holds its
own replica of the document and merges operations — that is, does exactly what a CRDT was invented
for. Redis pub/sub is the **fast, unreliable path** (at-most-once). The Postgres `op_log` is the
**slow, authoritative path** — an instance that detects a gap in the version vector pulls the
missing operations from the database (anti-entropy / read repair).

This is ADR-004 and it is the architecture's strongest point: an unreliable broadcast is *safe by
definition* here, rather than "safe because we hope so".

---

## 2. The algorithm's core — what exactly we implement

### The choice: RGA (Replicated Growable Array), the left-origin variant

- `Id = (replicaId: int, counter: long)` — a Lamport counter per replica.
- The character node: `{ id, originLeft: Id, value, deleted: boolean }`.
- **Insert**: a new node points at its left neighbour at the moment of insertion. Among siblings
  inserted after the same origin, the order is descending by `(counter, replicaId)`. A
  deterministic total order with no arbiter.
- **Delete**: a tombstone (a flag), not removal from the list — because other replicas may still
  point at that node as an origin.
- **Dedup / causal delivery**: a version vector per replica plus a buffer of operations whose
  causes we have not seen yet.
- **Run-length encoding**: continuous typing ("the user typed 20 characters") is one operation with
  a base `id` and a length; a node splits only when somebody inserts into the middle of it (like
  `Item` in Yjs). Without this, a 100k-character document is 100k objects.

### A deliberately documented weakness: the interleaving anomaly

RGA is not maximally non-interleaving (see Weidner & Kleppmann, *Fugue*). There are sequences of
concurrent insertions where two authors' letters interleave instead of staying in contiguous
blocks.

**The plan:** write a property-based test `concurrentTyping_staysContiguous`, find the case where
RGA breaks it, and **leave it in the repository as an explicitly marked known-limitation test**
(`@Disabled("ADR-001: a known RGA anomaly, see the Trade-offs section")`) with a link to the ADR.
Honestly documenting the boundary of one's own algorithm beats pretending the boundary is not
there.

An optional extension (if time allows): a second algorithm backend (Fugue) behind the same
interface plus the same set of property tests run against both. That would be strong, but it is
+2–3 days — a decision to be taken after checkpoint 2.

### Optimisation as narrative, not as an assumption

We build **two** implementations of the `index ↔ CRDT id` mapping behind the same interface:

1. **Naive** — a linear scan of the list. O(n) per operation. Simple, obviously correct.
2. **Indexed** — an order-statistic tree / skip list over the blocks, carrying a count of visible
   characters. O(log n).

Both pass **the same** set of property tests (that is the proof the optimisation broke nothing),
and JMH shows the difference in numbers on documents of 1k / 10k / 100k / 1M characters. That
sequence of commits — naive → measured → optimised → the same correctness — is worth more than
clever code dropped in from the start.

### Two implementations (Java + TypeScript) and how to keep them from diverging

The CRDT has to exist on both sides (the client edits offline, locally). That is a real cost: two
bodies of code, one specification. The solution: **a corpus of conformance vectors**.

The Java property tests generate random sequences of operations and write them out as JSON
(`crdt-core/src/test/resources/conformance/*.json`: the operations plus the expected final state).
The TypeScript suite replays exactly those vectors and has to arrive at the same text. CI runs
both. The implementations can no longer diverge silently.

---

## 3. Checkpoints

After each checkpoint, **stop and ask whether to continue** (CLAUDE.md). The estimates assume
working with Claude Code, not "by hand from scratch".

---

### CP1 — ADRs + skeleton (0.5–1 day) — no domain code

**Deliverables:**
- `git init`, `.gitignore`, `.editorconfig`, the first commit
- `docs/adr/0001-crdt-vs-ot.md` — RGA vs Logoot/LSEQ vs YATA/Yjs vs Fugue vs OT. Negative
  consequences: tombstone growth, the interleaving anomaly, the double implementation
- `docs/adr/0002-transport.md` — STOMP/WS vs raw WS vs SSE+POST vs WebTransport. A justification
  for the heartbeat and for its numeric value (not "because it is the default")
- `docs/adr/0003-change-history.md` — an event log vs periodic snapshots (decision: both, with the
  snapshot a cache over the log; the space/replay-time trade-off)
- `docs/adr/0004-horizontal-scaling.md` — an instance as a replica + the Redis fast path +
  anti-entropy from the op_log; the alternative (document affinity) and why it was rejected
- `docs/adr/0005-tombstone-gc.md` — causal stability, and when a tombstone may be removed
- The Maven skeleton: `pom.xml` (parent) + the `crdt-core` and `app` modules
- `frontend/` — Vite + React + TS, empty
- `docker-compose.yml` — Postgres, Redis, Prometheus, Grafana, the OTel Collector (with a comment
  saying *why* next to every non-trivial setting)
- CI: GitHub Actions, for now just build + lint
- README: placeholder sections with titles, so it is clear what gets filled in along the way

**Exit criteria:** `./mvnw install` and `npm run build` pass, the ADRs cover 5 decisions, and each
of them has a "Negative consequences" section that is not empty.

---

### CP2 — The CRDT core + property tests (2–3 days) — **the heart of the project**

**Deliverables:**
- `crdt-core`: `Id`, `VersionVector`, `Op` (Insert/Delete), `RgaDocument`, `IndexMap` (naive and
  indexed), binary serialisation of operations
- Property tests (jqwik) — each answering a specific question:

| Property | The failure mode it catches |
|---|---|
| `convergence` — N replicas, random operations, random delivery order → identical text | The core: without commutative merging, changes are silently lost |
| `idempotence` — delivering the same operation twice changes nothing | A retransmission after a reconnect duplicates text |
| `causalDelivery` — an operation arriving before its origin goes into the buffer, not into the state | The network reorders → an NPE or a character in the wrong place |
| `deleteCommutesWithInsert` — deleting a character and inserting next to it, in either order | A cursor/backspace race eats the neighbouring character |
| `naiveEqualsIndexed` — both IndexMap implementations on the same input | The O(log n) optimisation introduced a correctness regression |
| `concurrentTyping_staysContiguous` | The interleaving anomaly — **we expect this to fail**, and we document it |
| `gcPreservesText` — tombstone GC does not change the visible text | GC removes a node that was somebody's origin |

- Test determinism: a fixed seed in CI plus a nightly run with a random seed and 10× the tries
  (counterexamples found go into the repository as regression tests)
- A generator of conformance vectors (JSON) for the frontend
- JMH: an insert/delete/merge benchmark on 1k/10k/100k/1M characters, with the result in the README

**Exit criteria:** every property test green (except the explicitly marked known limitation), and
JMH showing in numbers that the indexed version scales log-linearly while the naive one scales
quadratically. **We do not go further until this is solid** (CLAUDE.md).

---

### CP3 — WebSocket + persistence (2 days)

**Deliverables:**
- A STOMP endpoint, with the channels `/topic/doc/{id}/ops`, `/topic/doc/{id}/presence`,
  `/app/doc/{id}/op`
- The protocol: `ClientOp` → the server merges → `ServerAck(opId, serverSeq)` + a broadcast to
  everyone else. The client does not wait for an ack to show its own character (local-first, zero
  input lag)
- Postgres: `documents`, `operations` (PK = `(doc_id, replica_id, counter)` → write idempotence for
  free), `snapshots`, `document_members`
- Migrations: Flyway
- Batched operation writes (a grouped insert every N ms) — with a comment about the
  durability/throughput trade-off
- Auth: JWT with a simplified in-memory user store — deliberately, and destined for the "what I
  would do differently in production" section
- **Integration tests with Testcontainers** (a real Postgres): two WS clients editing the same
  document → both see the same thing; an application restart → the state rebuilt from the op_log

**Exit criteria:** two (test) clients converging through the real stack, and the state surviving a
restart.

---

### CP4 — Frontend: the editor and cursors (2–3 days)

**Deliverables:**
- `crdt-ts` — a port of RGA that passes the conformance vectors from CP2
- CodeMirror 6 as a **view layer** (not the model): CM6 transactions → CRDT operations → back as
  decorations. The justification goes into ADR-006: raw contentEditable is a month of fighting
  IME/composition/the browser's undo, and `idea.md` explicitly allows a library as the UI layer
- Remote cursors and selections — a cursor position held as a **CRDT id, not an offset** (an offset
  drifts under remote editing before the cursor — a classic bug worth writing about in the README)
- Presence: a list of users, "X is typing", a colour per user
- Optimistic local apply plus reconciliation after the ack

**Exit criteria:** two browser tabs, simultaneous typing in the same paragraph, no cursor jumping,
no lost characters.

---

### CP5 — Change history / versioning (1–1.5 days)

**Deliverables:**
- Time travel: `GET /api/docs/{id}/at?t=...` — the nearest earlier snapshot plus a replay of
  operations from the op_log up to point T
- An audit trail: who, what, when (based on `replica_id → user_id`)
- Restore: bringing back a version as **new operations**, not by overwriting the log (the log stays
  append-only — otherwise we lose auditability and break convergence for online clients)
- UI: a timeline / version list, a diff between versions, a preview

**Exit criteria:** it is possible to go back to any point and restore it, and other open clients see
that as an ordinary edit.

---

### CP6 — Offline / reconnect (1.5 days)

**Deliverables:**
- An offline operation buffer in IndexedDB (surviving a tab refresh, not just a network loss)
- Reconnect: an exchange of version vectors → the server sends what is missing, the client sends
  its own
- Exponential backoff with jitter (justified numerically, not plucked from the air)
- **Tombstone GC plus a policy for very old clients**: a client offline for longer than the GC
  window comes back with operations pointing at collected tombstones. The decision: we reject them
  and export the local copy as a "recovered draft", instead of pretending to merge. That is a
  failure mode for the README
- A network simulator: transport middleware with latency / jitter / packet loss / partition
  sliders. The same code drives the demo (idea.md) and the chaos tests (CP8) — one thing, two uses

**Exit criteria:** turning the network off for 60 s while both sides are actively typing → after it
returns, both clients hold identical text containing both sets of edits.

---

### CP7 — Observability (1.5 days)

**Deliverables:**
- OpenTelemetry: an end-to-end trace of a single edit. `traceparent` (W3C) carried in a STOMP
  header; the spans: `edit.submit` (browser) → `op.receive` → `crdt.merge` → `op.persist` →
  `op.broadcast` → span **links** to N × `op.deliver` (fan-out is not a parent-child relationship —
  a link is semantically correct here)
- Metrics (Micrometer → Prometheus): `doc_active_sessions`, `crdt_merge_duration_seconds`,
  `op_propagation_latency_seconds`, `crdt_concurrent_merges_total`, `doc_tombstone_ratio`,
  `ws_reconnect_total`, `offline_buffer_size`, `redis_gap_repairs_total`
- **A methodology for measuring propagation latency** (because otherwise it is a meaningless
  number):
  - *server-side*: ingest → broadcast, one clock, honest
  - *end-to-end*: measured solely in the benchmark harness, where writer and reader are in one
    process and share a monotonic clock
  - We do not mix those two numbers in the README, and we say outright which is which
- Grafana: a dashboard provisioned from the repository (JSON), not clicked together by hand
- Exemplars: a Prometheus histogram → a specific trace (optional, if time is left)

**Exit criteria:** one keystroke in the browser = one trace visible end to end; the dashboard comes
up from `docker compose up` with no manual configuration.

---

### CP8 — Chaos + benchmarks (1.5–2 days)

**Deliverables:**
- Chaos tests: random delays, reordering, drops, a network partition, killing a backend instance
  mid-edit. The final assertion is always the same: **every replica converges**
- Toxiproxy in Testcontainers, for chaos on a real connection (not just in-process)
- **Gatling** (not k6) — because it is JVM-based, matches the stack, and makes assembling STOMP
  frames by hand easier
- Scenarios: 10 / 50 / 100 / 250 users in one document; documents of 1k / 10k / 100k / 1M
  characters
- For the README, with the methodology and the machine's specification:
  - throughput (ops/s) vs the number of users
  - propagation latency p50/p95/p99 vs the number of users
  - **the degradation point** — at how many users and what document size p95 crosses the threshold
  - memory use vs document size (the cost of tombstones, before and after GC)

**Exit criteria:** the README contains tables of numbers and charts, with zero adjectives like
"fast". If something degrades badly, we write that down honestly along with a hypothesis as to why.

---

### CP9 — Final documentation + demo (1–1.5 days)

**Deliverables:**
- **A GIF at the very top of the README, before any text.** Recorded by a Playwright script (two
  browser contexts, a deterministic scenario) → video → `ffmpeg` + `gifski`. The script lives in
  the repository → the demo can be re-recorded after a UI change, and doubles as an E2E test
- The demo scenario: (1) two users typing in the same sentence, (2) a disconnect + offline editing
  + a reconnect and a merge, (3) rewinding to an earlier version
- Diagrams: the architecture, the sequence of a single edit, the reconnect state machine (Mermaid)
- **A Failure modes section** (CLAUDE.md §6): losing the connection, an instance crash/failover,
  simultaneous editing of the same character, a very large document and tombstone GC
- **A "what I would do differently in production" section** (§7): no rich text, simplified auth, no
  document sharding, no per-user rate limiting, RGA's interleaving anomaly, the double
  implementation of the algorithm as a debt — and how each of those would be addressed at scale
- A pass over the ADRs: adding "verified in phase X" wherever CP1 left uncertainty

---

## 4. Decisions taken (2026-08-14)

1. **Depth of the CRDT** → **RGA** alone, with the interleaving anomaly documented as a known
   limitation (an explicitly marked test plus a section in ADR-001). Fugue as a second algorithm
   backend is **rejected** on cost (+2–3 days); it goes into the "what I would do differently in
   production" section as a deliberately skipped step.
2. **The editor layer** → **CodeMirror 6** as a pure view layer (the document model is our CRDT;
   CM6 renders and supplies the decorations for remote cursors). The justification goes into
   ADR-006.
3. **The scope of the benchmark** → up to **250 users / 1M characters**, one machine, with the
   hardware specification stated in the README alongside the results. No distributed Gatling.

## 5. Total estimate

| Checkpoint | Days |
|---|---|
| CP1 ADRs + skeleton | 0.5–1 |
| CP2 CRDT + property tests | 2–3 |
| CP3 WebSocket + persistence | 2 |
| CP4 Frontend + cursors | 2–3 |
| CP5 Change history | 1–1.5 |
| CP6 Offline / reconnect | 1.5 |
| CP7 Observability | 1.5 |
| CP8 Chaos + benchmarks | 1.5–2 |
| CP9 Documentation + demo | 1–1.5 |
| **Total** | **~13–17 working days** |

If time ran short: we would cut CP5 (history) to a minimum, along with the CRDT extensions from
§4.1. **We would not cut CP7 (observability)** — CLAUDE.md §4 states outright that it is one of the
main signals of seniority.
