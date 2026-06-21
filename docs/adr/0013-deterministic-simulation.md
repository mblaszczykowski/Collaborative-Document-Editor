# ADR-0013 — Deterministic simulation as a third testing pillar

**Status:** Accepted (CP17)

## Context

This project already has two testing pillars and they are both good ones:

1. **Property tests** over the merge algorithm (`crdt-core`) — random operation sequences in
   random orders, fixed seed, deterministic. These proved convergence and, once, disproved a
   documented claim: 200 000 scenarios showed the algorithm converges without a condition that
   had been asserted as necessary.
2. **Integration tests on real infrastructure** (Testcontainers) — a real Postgres, a real
   Redis, real STOMP frames.

The gap is between them. `ClusterChaosIT` stops Redis mid-write and checks the cluster repairs
itself; it is the only test covering the distributed design end to end, and its result depends
on **how threads happened to interleave**. `nightly.yml` runs it ten times in a row for exactly
that reason, and says so in a comment:

> a test that passes once may have passed by accident; a test that passes ten times in a row
> under varying runner load is a much stronger indication

An indication — not a proof. And the deeper problem is not detection but **reproduction**: when
the ten-run job goes red, there is no way to run *that* failure again. The interleaving that
produced it is gone. Debugging becomes a loop and a hope.

That gap is not hypothetical. While CP16 was being verified, a full-suite run failed
`ClusterChaosIT.editsPropagateAcrossInstances`; three runs of the same test in isolation passed.
The difference was CPU contention from a hundred other tests, and there was no way to ask for
that specific schedule back.

## Options considered

**A. Leave it; run the chaos test more times.**
More repetitions raise the chance of detection and do nothing about reproduction. Ten runs
already cost minutes of nightly CI, and the marginal value of the eleventh is small.

**B. Model checking (TLA+ / Stateright).**
Genuinely attractive here, and thematically right: property tests *sample* the state space, a
model checker *exhausts* it for small sizes. It would turn the "200 000 scenarios suggest…"
result into a proof for bounded cases.

Rejected for this plan on scope, not on merit. A TLA+ spec covers the model you write, and the
model would be the merge rule — the part already best covered, by property tests. It says
nothing about the log, the fast path, restarts, or anti-entropy, which is where the untested
design actually lives. It also builds nothing reusable: a spec is a separate artifact that
drifts from the code silently. Recorded in PLAN-v3 §9 as the strongest candidate for v4.

**C. Deterministic simulation.** ← chosen

A single-threaded scheduler with virtual time, where one seed decides message order, delays,
drops, partitions, and crashes. Nothing sleeps; nothing races.

## Decision

`DeterministicSimulation` plus `SimulatedCluster`, driving the **real** `RgaDocument` from
`crdt-core` through the design ADR-0004 describes: instances as peer replicas, an append-only
log standing in for Postgres, best-effort broadcast standing in for Redis, periodic anti-entropy.

The property that makes it worth the code:

> A failure stops being "one run in ten" and becomes `seed = 8471293` — a one-line regression
> test reproducing the identical interleaving on any machine, forever.

**The determinism test outranks every scenario.** `sameSeedProducesIdenticalHistory` compares
the full event *history* of two runs, not their final states, because a system can reach the
same answer by two different routes and only one of them is reproducible. Without that test
passing, every other test in the file is a chaos test with extra steps.
`differentSeedsProduceDifferentHistories` guards the opposite failure — a suite that claims a
thousand schedules while exploring one.

**Verified by mutation, per house convention.** Inverting the RGA scan condition — the same
mutation used in CP2 — makes **593 of 806** simulation tests fail, and the message is a minimal
counterexample: *"instances i1 and i2 were given the same 4 operations but show different text:
`olhs` vs `olsh`"*, with the seed. Four operations, not four hundred.

**Scope: one shape of scenario, done properly.** Delivery loss, partition, crash and restart.
Not because the others are uninteresting, but for the reason plan v2 gave about CP13 — the
scenarios are cheap and the scheduler is expensive, so the scheduler gets closed first.

## Consequences

**Positive**

- The distributed design is now tested at 806 scenarios per run in **under a second**, with no
  Docker, in the fast CI job. `ClusterChaosIT` covers roughly one schedule in six seconds.
- A found bug is permanently reproducible. `REGRESSION_SEEDS` is currently **empty**, and
  honestly so: CP17 built the machine and has not yet caught anything with it.
- Invariants are checked after *every* event, not at the end — a structure that is briefly wrong
  between two events and correct by the end passes any final-state check.

**Negative**

- **It is a model, and a model can be wrong in the direction that matters.** This is not
  theoretical: the simulation's first three findings were all bugs *in the simulation*, and each
  one presented as a confident convergence failure across every scenario. See below.
- **It does not touch Postgres, Redis, Spring, or a socket.** It can find a bug in the
  distributed logic and cannot find one in the wiring, so `ClusterChaosIT` stays exactly where
  it is. Replacing one with the other would be the same error as treating a snapshot as the
  source of truth.
- **The crash-before-persist window is not modelled.** The log append is synchronous with the
  edit, so the case where an instance broadcasts and dies before persisting — a real failure
  mode listed in the README — is outside what this says anything about. Its repair lives in the
  *client's* unacknowledged-operation buffer, and there is no client in this model. First thing
  to add when extending.
- **A second implementation of the transport layer** is a second thing to keep honest, the same
  debt as two CRDT implementations, smaller.

### What the simulation found first: itself

Worth recording in full, because it is the main risk of this technique and it materialised
immediately.

1. **The invariant was wrong.** It compared instances by version vector, assuming equal vectors
   mean equal operation sets. `VersionVector`'s own javadoc says otherwise in as many words: the
   Lamport clock jumps when remote operations arrive, so a hole from a dropped message is
   indistinguishable from a hole from a clock jump. With a lossy fast path, holes are normal.
   Every scenario failed, and none of the failures were real.
2. **The simulation never terminated.** Anti-entropy reschedules itself forever, exactly as the
   real periodic sweep does, so "run until the queue is empty" runs until the runaway guard
   fires. Replaced with an explicit time horizon — choosing which is now part of writing a
   scenario.
3. **The log cursor was advanced on local edits**, so an instance claimed to have read log
   entries it had never seen and anti-entropy skipped them permanently. Caught by the
   convergence assertion and *not* by the per-step invariant, which was correct to stay quiet:
   the instances genuinely held different operations the whole time.

Three model bugs before one system bug. A simulation that reports failures which are not there
costs more than one that finds nothing, so the honest reading of CP17 so far is: **the machine
works, is verified by mutation, and has not yet earned its keep on a real defect.** Whether it
does is a question for the seeds, not for this document.

**To be verified after CP18:** the block representation change is the first real chance for this
to prove itself, since it touches the merge path directly. If the simulation catches something
there that the property tests miss, that is the answer. If it does not, that should be recorded
here just as plainly.
