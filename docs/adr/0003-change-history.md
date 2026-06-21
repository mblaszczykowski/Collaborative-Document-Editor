# ADR-0003 — Change history: the event log as source of truth, snapshots as a cache

- **Status:** Accepted
- **Date:** 2026-08-14
- **Applies to checkpoints:** CP3 (schema), CP5 (time travel), CP8 (cost of rebuilding)

---

## Context

Requirements from `idea.md`:
- rolling a document back to **any point in time**,
- an **audit trail** — who changed what,
- rebuilding state after an instance restart.

Additionally from ADR-0001: CRDT operations must survive, because an offline client may come back
with an operation whose origin lies somewhere in the past — we need the full causal context, not
just the current text.

The tension to resolve: **the cost of writing and of storage** versus **the cost of rebuilding
state**.

---

## Options considered

### Option A — Current state only (a text column, `UPDATE` on every change)

**For:** trivial reads, minimal storage.
**Against:** no history, no audit trail, no way to close the CRDT structure (tombstones and
identifiers disappear), and a client returning from offline has nothing to refer to. It rules out
two of the three requirements. Rejected without further analysis.

### Option B — Event log only (pure event sourcing)

An append-only operations table. State = the reduction of all operations from the beginning.

**For:**
- Full history and audit trail for free — the log *is* the history.
- Fits a CRDT perfectly: an operation is already the natural, immutable unit of change.
- Writing is an `INSERT`, with no row contention — it scales well with many writers.
- Write idempotence follows from the primary key `(doc_id, replica_id, counter)`, because that
  key is the natural one for a CRDT operation identifier. A retransmission after reconnect cannot
  duplicate a row.

**Against:**
- **Rebuild time grows linearly with history.** A document with a million operations requires
  reading and applying a million operations on every open and on every instance start. Not
  acceptable.

### Option C — Periodic snapshots only

Every N seconds we write the serialised CRDT state.

**For:** constant read time.
**Against:** history has the granularity of a snapshot — "roll back to any point" degrades into
"roll back to every so many minutes". The per-change audit trail disappears. Operations between
snapshots are lost irretrievably, so an offline client has nothing to attach to.

### Option D — Event log as source of truth + snapshots as a cache

The log is the only truth; a snapshot is the materialised result of reducing the log to a
specific point, and may be deleted at any moment and rebuilt.

**For:** combines full history with bounded read time.
**Against:** two mechanisms to maintain and keep consistent; a snapshot may be stale or corrupt,
so it must be verifiable.

---

## Decision

**Option D.**

```
operations   (append-only, SOURCE OF TRUTH)
  PK (doc_id, replica_id, counter)   ← write idempotence for free
  op_type, payload, origin_left, server_seq, user_id, created_at

snapshots    (CACHE — may be deleted at any time)
  PK (doc_id, up_to_server_seq)
  state (bytea), text_length, tombstone_count, created_at
```

### Rules

1. **`server_seq`** — a monotonic per-document counter assigned on write. It has no bearing on
   CRDT correctness (convergence does not depend on order — ADR-0001) and serves only as a
   **cursor**: "here is everything above seq X" on reconnect and during anti-entropy between
   instances (ADR-0004). Separating the *causal* order (version vector) from the *write* order
   (server_seq) is deliberate — conflating them is a classic source of bugs.

2. **Rebuilding state** = the nearest snapshot with `up_to_server_seq ≤ T` + replay of operations
   in the range `(up_to_server_seq, T]`.

3. **A snapshot is disposable.** If it disappears or cannot be deserialised (for example after a
   format change), the system rebuilds from the log from scratch — slower, but correctly. A
   snapshot is never the only carrier of information. Test in CP5: deleting every snapshot
   changes no result, only the response time.

4. **Snapshot threshold: every 500 operations or 30 s of document inactivity, whichever comes
   first.** The 500 follows from the order of magnitude — replaying 500 RGA operations is single
   milliseconds, so the uncompacted tail of the log never dominates open time. The idle snapshot
   catches documents that will never reach 500 operations but are opened often. **Values to be
   calibrated in CP8** — I will measure the cost of replay and the cost of writing a snapshot and
   pick the threshold from actual numbers, rather than leaving a guessed constant.

5. **Batching operation writes: a bulk `INSERT` every 50 ms or every 100 operations.** The
   durability/throughput trade-off is explicit here: within a window of up to 50 ms an operation
   has already been broadcast to other clients but is not yet in the database. An instance dying
   in that window means losing an operation other clients already see → divergence. **This is a
   real failure mode and goes into the Failure modes section of the README**, together with its
   mitigation: the client holds the operation in a buffer until it receives an `ack` with a
   `server_seq` (that is, until the write is confirmed), and retransmits on reconnect. Ack after
   the write, not after the broadcast — that is not a detail.

6. **Restoring an old version is written as NEW operations**, never as a truncation of the log.
   The reasons: the log stays append-only (auditability), other connected clients see the restore
   as an ordinary edit and converge normally, and "who restored Tuesday's version" is itself an
   entry in the audit trail. Truncating the log would break all three properties at once.

---

## Consequences

### Positive
- Time travel to any operation, not to the nearest snapshot.
- The audit trail is a by-product, not a separate mechanism.
- Write idempotence follows from the primary key — with no deduplicating code.
- A snapshot can be invalidated at any time when the CRDT serialisation format changes, with no
  data migration.

### Negative
- **Storage grows monotonically.** A document edited actively for a year is a log nobody deletes.
  In production this would need archival of old segments to object storage and compaction — out
  of scope, goes into "what I would do differently in production".
- **Two mechanisms to test.** There must be a "delete the snapshots → identical results" test, or
  the cache will quietly become the source of truth.
- **A 50 ms window of unwritten operations** — described above, a deliberate trade-off.
- **Snapshot serialisation is coupled to the CRDT's internal structure.** Every change in
  `crdt-core` potentially invalidates snapshots. Mitigation: a format version number in the
  snapshot, and on a mismatch the snapshot is ignored (rather than: we try to migrate it).
- The query "state at time T" is not free — it costs a snapshot read + a tail replay. Browsing
  history step by step is N times that cost. **To be measured in CP5/CP8**; if it hurts, the
  mitigation is incremental replay while scrubbing the timeline (keep the state and add
  operations) instead of rebuilding from a snapshot every time.

### Verified in CP8

- **Snapshot serialisation had a bug that surfaced only with a large document.**
  `DataOutputStream.writeUTF` encodes the length in two bytes and fails above 64 KB — and a
  run-length block formed by merging continuous typing comfortably exceeds that. The effect was
  **snapshot writing silently stopping** for documents of 100 000 characters; the only symptom
  was a log entry, and the system kept working, just opening more slowly. That is exactly the
  kind of failure treating a snapshot as a cache protects against: nothing was lost. Fixed, with
  a regression test in `SnapshotCodecTest`.
- **The equivalence of "with snapshots" and "without snapshots" is tested directly** and passes —
  both for current state and for time travel. Without that test the bug above could have passed
  unnoticed as "something is slower".
- **Neither the 500-operation threshold nor the log size was calibrated by measurement.** The
  performance results showed the bottleneck is delivery to clients rather than reads from the
  database, so calibrating that threshold would be optimising a place that does not hurt. The
  value stays as chosen by reasoning, marked as unverified.

### Verified in CP10

- **Point 4 of this decision described a mechanism that did not exist in the code.** "The idle
  snapshot catches documents that will never reach 500 operations" read like a description of
  system behaviour and was a description of intent: the only implemented path was the
  operation-count threshold, checked when writing a batch. A document with no new operations does
  not enter that path at all, so the idle threshold **could not fire by construction**,
  regardless of the value in the configuration.
- The effect was that a document edited in short sessions of a few changes never got a snapshot,
  and the log tail to replay grew with every open. Correctness intact — only load time degraded,
  and gradually, so without a metric there is no way to notice.
- Implemented in CP10 together with a test that uses **five** operations against a threshold of
  twenty, so that the "every N operations" path cannot pass it by accident.
- A lesson more general than the bug itself: an ADR describing a decision and an ADR describing
  the state of the system read the same and mean different things. A configuration value with a
  justification in a comment looks like it works — and nobody checks it precisely because it
  looks that way.
