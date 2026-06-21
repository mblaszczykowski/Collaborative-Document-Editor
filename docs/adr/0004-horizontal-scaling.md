# ADR-0004 — Horizontal scaling: a backend instance as a CRDT replica

- **Status:** Accepted
- **Date:** 2026-08-14
- **Applies to checkpoints:** CP3 (implementation), CP8 (instance-death test)

---

## Context

Clients editing the same document may land on different backend instances (the load balancer
distributes WebSocket connections). An operation accepted by instance A must reach clients
connected to instance B.

This is the moment where the choice from ADR-0001 either pays off or takes its revenge — so I
treat this decision as a test of that one.

---

## Options considered

### Option A — Sticky sessions: everyone editing a document lands on the same instance

The load balancer routes by `doc_id` (consistent hashing).

**For:** the document state exists in one place, zero coordination, no memory duplication.
**Against:**
- Requires a domain-aware LB (routing by content, not by connection) — non-trivial with
  WebSockets, where `doc_id` is known only after `CONNECT`+`SUBSCRIBE`.
- The load distribution is only as good as the distribution of document popularity. One document
  with 200 users always loads a single instance — horizontal scaling does not help where it is
  actually needed.
- Reconfiguring the ring when an instance is added or removed moves documents between nodes, each
  time with an interruption.

### Option B — Document affinity with a distributed lock (Redis / etcd)

One instance is the document's "owner"; the others proxy operations to it.

**For:** a single in-memory source of truth, a simple model to reason about.
**Against:**
- It introduces a **distributed lock and leader election** into a system whose entire point is
  that it needs no leader. A contradiction of ADR-0001.
- Owner failover is the hardest case: split-brain, leases, fencing tokens. A lot of complicated
  code, a lot of places to get it wrong.
- An extra hop for every operation passing through a non-owner.

### Option C — Every instance is a CRDT replica, Redis pub/sub as fan-out

An instance accepts an operation from its own client, merges it into its own replica, writes it to
`operations` (ADR-0003), and publishes it on the Redis channel `doc:{id}:ops`. The other instances
receive it, merge into their replicas and distribute it to their clients.

**For:**
- **This is exactly the model a CRDT was designed for.** Replicas are peers, delivery order does
  not matter, duplicates are harmless (idempotence from ADR-0001). Zero locks, zero leader, zero
  sticky sessions.
- Any instance serves any client → the LB can be completely dumb (round-robin).
- Adding an instance moves no state.

**Against:**
- **Memory duplication:** a document open by clients on 3 instances lives in 3 copies.
- **Redis pub/sub is at-most-once.** A Redis restart, a client buffer overflow, a brief partition
  — the message disappears without a trace and without an error. A replica that missed an
  operation diverges from the rest and **will never repair itself**, because a CRDT guarantees
  convergence only under the assumption that all operations eventually arrive.

---

## Decision

**Option C — every instance is a CRDT replica** — with an explicit solution to the lost-message
problem.

### Redis is the fast path. Postgres is the authoritative path.

```
client ──op──► instance A
                 │
                 ├─ 1. merge into the local replica   (immediately)
                 ├─ 2. broadcast to its own clients   (batched, every 10 ms)
                 ├─ 3. INSERT into operations         (SOURCE OF TRUTH, ADR-0003)
                 └─ 4. PUBLISH doc:{id}:ops           (best-effort, may be lost)
                             │
                    instance B ─ SUBSCRIBE ─► merge + broadcast
```

> **A change to step 2, introduced in CP10.** It originally read "immediately", one frame per
> operation. That is behaviour which **by construction does not scale with client count**: an
> operation goes out in as many frames as there are recipients, so the total number of frames
> grows with the square of the client count. At 50 clients × 40 operations that is 100 000 frames.
>
> This is an argument from arithmetic, not from measurement — and it is worth separating from the
> hypothesis about **what specifically** was breaking in the scenario the README recorded as
> unresolved ("some clients permanently behind"). The guess was that the send queue exceeds
> `send-buffer-size-bytes` and Spring tears the session down with code 4500. **That guess
> contradicts the CP8 finding** recorded in ADR-0002: the broker's statistics showed not a single
> closure due to buffer overflow at that time. So CP10 first adds the
> `ws_sessions_closed_total{reason}` metric, which measures this directly rather than through the
> broker's aggregate statistics — and the conclusions are appended below, after measurement.
>
> Whatever turns out to cause the divergence, frame coalescing is justified on its own: quadratic
> growth in frame count is a property of the construction, not a symptom of a bug.
>
> The 10 ms window is an order of magnitude below the threshold of noticeability in typing and an
> order of magnitude below the measured propagation latencies, so the trade-off is not close. It
> must be **clearly shorter than `op-batch-window-ms`** — a write acknowledgement that outruns the
> broadcast is interpreted by the client as a gap, and it asks for a full resynchronisation. To be
> safe, both buffers are drained under a single acquisition of the lock, with the broadcast before
> the write (`DocumentReplica.drainForFlush`), so we do not rely solely on the relationship
> between two schedulers' periods.

**Anti-entropy (read repair) — the mechanism that turns an unreliable broadcast into a safe one:**

Every operation carries a `server_seq` (ADR-0003). Instance B tracks the highest `server_seq` seen
for a given document. If an operation arrives with a `server_seq` higher than `last + 1`, there is
a gap in between → B pulls the missing range directly from Postgres
(`SELECT ... WHERE doc_id = ? AND server_seq BETWEEN ? AND ?`) and merges it.

In addition, a **periodic sweep every 30 s**: for every document held in memory, the instance
compares its highest `server_seq` with `MAX(server_seq)` in the database and pulls the difference.
This catches the case gap detection does not: **losing the very last operation**, after which no
further one arrives (for example, Redis dies and the document goes quiet). Without the sweep such
a replica stays diverged until the next edit.

The 30 s frequency is a trade-off: it is the upper bound on how long an undetected divergence can
live in the worst case, at the cost of one lightweight query per in-memory document. **To be
calibrated in CP8** based on the measured cost at a realistic number of open documents.

The key property: **none of these repair paths has to be fast or ordered**, because merging is
commutative and idempotent. An operation pulled from the database 30 seconds after the fact gives
the same result as one delivered immediately. That is precisely the property we chose a CRDT for —
and here it stops being theoretical.

### Eviction from memory

A document replica with no connected clients lives another **5 minutes**, then is removed from
memory (the state is in Postgres anyway). A shorter value causes constant reloading of documents
on brief disconnects; a longer one holds memory needlessly. To be calibrated in CP8 together with
a measurement of memory use per open document.

---

## Consequences

### Positive
- **The load balancer needs to know nothing about the domain.** Round-robin is enough.
- **No leader, lock, split-brain or fencing** — that is, none of the hardest class of distributed
  systems bugs.
- **An instance dying loses no data**: operations are in Postgres before publication (order 3
  before 4), clients switch to another instance and resynchronise by `server_seq`.
- Redis being unreliable stops being a correctness problem and becomes a question of repair
  latency — measurable with a metric (`redis_gap_repairs_total`, CP7).

### Negative
- **Memory use multiplies by the number of instances holding a document.** With a 1 MB document
  and 5 instances that is 5 MB instead of 1 MB. Acceptable at this scale, but it is a real cost of
  the choice — and the first thing I would change if documents were large or instances numerous.
  At that point the right answer is option A/B, not optimising this one.
- **A divergence window of up to 30 s** in the worst case (the last operation lost + silence on
  the document). A client connected to the affected instance sees stale text during that time. It
  is not data loss, but it is a visible inconsistency — it goes into the Failure modes section of
  the README.
- **Postgres becomes a critical path for repair too**, not only for durability. A database outage
  stops both writes and anti-entropy.
- The sweep is a constant load proportional to the number of open documents, regardless of whether
  anything is happening.
- **Gap detection assumes `server_seq` is dense** (no holes).

  **Verified in CP3 — the assumption turned out more expensive than I had assumed.** The first
  approach (`INSERT ... ON CONFLICT DO NOTHING`) is incorrect: numbers are reserved for the whole
  batch *before* duplicates are rejected, so every duplicate leaves exactly the kind of hole this
  mechanism forbids. The final solution:
  1. a row lock on the document (`SELECT next_server_seq ... FOR UPDATE`) — a counter in a column
     rather than a Postgres sequence, because a sequence loses numbers on a rolled-back
     transaction,
  2. duplicate filtering **in one query before** numbers are allocated, correct despite concurrency
     precisely because the lock serialises writers to that document,
  3. numbers allocated only to rows that will actually be inserted.

  **A price the original analysis did not include:** writes to a single document are now
  serialised across the whole cluster. Batching every 50 ms means the lock is taken once per batch
  rather than once per keystroke — but this is the first place that will start limiting
  throughput. **To be measured in CP8.**

  Numbering continuity is enforced by the integration test `serverSequenceHasNoGaps` against a
  real Postgres, not by assumption.

### To be verified in CP8
- Memory use per open document of a given size × number of instances.
- How long repair actually takes after artificially killing Redis mid-edit (chaos test: kill
  Redis, keep editing, measure the time to convergence of all replicas).
- Whether an instance dying mid-edit passes without losing operations — a chaos test killing the
  instance between steps 3 and 4, and between 2 and 3 (the latter *should* lose the operation at
  other clients until the client retransmits — I am checking whether retransmission actually
  repairs it).
- Calibration of the sweep interval (30 s) and the eviction TTL (5 min).
