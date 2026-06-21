# Collaborative Document Editor — project instructions for Claude Code

## Project goal (read this first)

The application has to work solidly — that is a necessary condition, not an optional one. But
"it works" is not enough for this to be a good portfolio project. We are building an **artifact
meant to prove senior-level full-stack engineering** to a recruiter or tech lead browsing the repo
for 3–5 minutes, so beyond working code every technical decision must be:
1. Deliberate and justified (not "because that is how it is usually done")
2. Documented somewhere a person will find it
3. Testable and measured in numbers, not adjectives

If a choice comes up between "works sooner" and "shows a deeper understanding of the problem" —
choose the second and **do not ask for approval**, even when it lengthens the work considerably.
Record the cost in the final report (see *Working mode* at the bottom of this file).

## Language

**The project language is English.** The decision and its justification:
[PLAN-v3 §0.2](docs/PLAN-v3.md).

- **All new content is written in English** — code, comments, javadoc, tests, ADRs, commit
  messages, README.
- Existing Polish content is migrated in this order: README → `docs/adr/` → code comments →
  `docs/architecture.md` and `docs/crdt-design.md` → the plans → configuration and UI strings.
- **The migration covers everything, including the plans and this file.** An earlier version of
  this rule exempted the plans as a "historical series". That exemption is withdrawn: a repository
  in two languages reads worse than in either one, and a reader arriving from an English README at
  a Polish comment gets told where the part written for them ends.
- When translating, **do not shorten and do not smooth over**. The value of these comments is that
  they say *why* and admit mistakes. The English has to carry the same bluntness and the same
  specificity, including sentences like "this did not work, and here is why".
- Filenames keep their original Polish slugs (`0003-change-history.md` and so on). Renaming them
  would break every link in the README, in the ADRs and in the commit history in exchange for
  cosmetics — and the commit history is part of the artifact here.

## Technical context

- Backend: Java 21, Spring Boot, PostgreSQL, WebSocket (STOMP)
- Frontend: React + TypeScript
- Sync algorithm: CRDT (not OT — see ADR-0001, the first document to be written in the project)
- Infrastructure: Docker Compose, GitHub Actions CI/CD
- Observability: OpenTelemetry + Prometheus/Grafana

## Hard requirements — skip none of them

### 1. Before you write the first line of domain code
- Create `/docs/adr/` and record an ADR for: the CRDT vs OT choice, the transport choice
  (WebSocket+STOMP vs SSE vs raw WS), the change-history strategy (event log vs periodic
  snapshots), the horizontal scaling strategy (Redis pub/sub between instances)
- ADR format: context → options considered → decision → consequences (including negative ones). If
  you do not know the consequences yet, write "to be verified in phase X" — do not manufacture
  certainty you do not have

### 2. Commit history as narrative
- Conventional Commits (`feat:`, `fix:`, `refactor:`, `test:`, `docs:`)
- Every commit is one logical change, not "wip" or "final v3"
- Commit decisions that did not work out too, if they are instructive (e.g. "revert: naive
  last-write-wins merge — see ADR-0001 for why it does not work") — a history of failures and
  iteration is more credible than linear success

### 3. Tests — prioritise in this order
1. Property-based tests of the CRDT algorithm (random operation sequences in random order must
   always converge to the same state) — this is the MOST IMPORTANT test in the whole project,
   because it proves the correctness of the core algorithm
2. WebSocket integration tests with Testcontainers (a real Postgres, not a mock)
3. A chaos test: simulating random delays and disconnects, checking the system does not diverge
4. Only then standard unit tests of controllers/services

Do not write tests "for coverage". Every test must answer the question "what real failure mode does
this catch".

### 4. Observability from day one, not at the end
- Distributed tracing of a single edit operation: client → WebSocket → CRDT merge → write →
  broadcast to other clients
- Metrics: number of active sessions, propagation latency (p50/p95/p99), conflicts resolved per
  second
- If time runs out for something, observability is NOT what we drop — it is one of the main signals
  of seniority

### 5. Benchmarks with real numbers
- Write a Gatling/k6 scenario simulating N concurrent users editing the same document
- Measure and record in the README: throughput, propagation latency as a function of user count,
  the point of performance degradation as document size grows
- Do not accept phrasing like "the system is fast" in documentation — always a concrete number with
  the measurement methodology

### 6. Failure modes — document them explicitly
A README section describing exactly what happens on:
- A client losing its connection mid-edit
- A backend instance dying (failover)
- Two users editing the same character at once
- A very large document (tombstone garbage collection in the CRDT)

### 7. A "what I would do differently in production" section
Deliberately list the simplifications made for portfolio scope (no rich text, simplified auth, no
document sharding) and how you would address them at real scale. This shows you know the limits of
your own solution — do not skip this section, even if it "spoils" the impression of completeness.

## What to avoid

- Off-the-shelf CRDT libraries (e.g. Yjs) as a black box with no implementation of your own — the
  goal is to show an understanding of the algorithm, not the ability to `npm install`. You may draw
  on them and compare against them in an ADR, but the core logic must be ours
- Excessive scope **at the cost of depth in the core problem** — better narrow and deep than broad
  and shallow. Up to the close of CP9 this meant plainly: no rich text, no comments, no
  multi-document. Since [ADR-0008](docs/adr/0008-scope-extension.md) the scope is wider, but
  the principle does NOT change — every new feature must bring its own engineering problem (rich
  text: anchors surviving concurrent insertions; permissions: authorization per operation type)
  rather than being work around the core. A feature that adds nothing to the project's thesis is
  still excessive.
- Default configuration values with no comment explaining why (e.g. "why this particular WebSocket
  heartbeat interval")
- A README written only at the end — write it alongside the code, section by section, so you do not
  have to guess after the fact what mattered

## Order of work (checkpoints)

The checkpoints are no longer listed here — they live in the plans, because that is where what
actually happened can be recorded next to them:

| Plan | Checkpoints | State |
|---|---|---|
| [`docs/IMPLEMENTATION_PLAN.md`](docs/IMPLEMENTATION_PLAN.md) | CP1–CP9 — CRDT core, transport, history, cluster, observability | closed |
| [`docs/PLAN-v2.md`](docs/PLAN-v2.md) | CP10–CP15 — performance, multi-document, permissions, rich text, comments | closed |
| [`docs/PLAN-v3.md`](docs/PLAN-v3.md) | CP16–CP20 — structural authorization, deterministic simulation, content-dropped blocks, regression gate | closed |
| [`docs/PLAN-v4.md`](docs/PLAN-v4.md) | CP21–CP30 — access reachable from the product, version diff by identity, suggestions, snapshot to the client, undo, cluster presence, offline comments, large pastes, content search, block structure | closed |

### Working mode: no stopping at checkpoints

An earlier version of this file required stopping after every checkpoint and asking whether to
continue. **That rule is withdrawn** — work through consecutive checkpoints without asking
permission to move on.

The reason for the change is concrete rather than cosmetic: in plans v1 and v2 asking made sense,
because every checkpoint changed the shape of the product and a wrong order would cost days of
work. Plan v3 adds no features — it closes items whose justification is **already measured and
written down in this repository**. Asking "shall I continue" about an item ADR-0005 calls
explicitly non-optional is a cost with no content.

What replaces the approval to proceed — because removing the gate does not remove the accounting:

- **Tests and the build are the gate.** A checkpoint is closed when what checks it passes, not when
  the code stops. A green build with no test that has teeth closes nothing (§3).
- **A single report at the end**, not approval along the way: what was done, what was done
  differently from the plan and **why**, and what was not done. That is the same content that used
  to be delivered at each question — only gathered rather than scattered.
- **Stop anyway** when something is irreversible or goes beyond the plan: a schema change that
  loses data, publishing anything externally, `git push`, dropping an item written into the plan.
  The autonomy covers **executing the plan**, not changing its scope.
- **Do not fake completion.** If a checkpoint is stuck, say so plainly and move to the next — plan
  v3 §1 says which ones can be cut without harm. Reporting "done" for something that does not pass
  its tests is worse than not doing it.

The same applies to the "faster" versus "deeper" choice in **Project goal**: choose deeper and **do
not ask permission to exceed ~30%** — record the cost in the final report. Ask only when the deeper
route changes the plan's scope rather than the time it takes.
