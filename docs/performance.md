# Performance

Measured numbers, the methodology behind them, and the claims that turned out to be wrong once
measured. Summarised in the [README](../README.md#performance); this is the long version.

All numbers come from one machine: **MacBook, Apple Silicon (aarch64), 10 cores, JDK 25-ea, 4 GB
JVM heap**, with Postgres and Redis in containers on the same machine. A number without its
machine is useless, so it is stated next to every table.

### The index structure: O(n) versus O(log n)

JMH benchmark (`./mvnw -pl crdt-core test-compile exec:exec@run-benchmarks`), average operation
time in microseconds, 3 warm-up iterations + 5 measurement iterations of 2 s.

> This line omitted `test-compile` and therefore failed on a clean tree — the benchmarks live in
> `src/jmh/java`, added as a test source, so there is nothing on the classpath for JMH to find
> without it. The command existed in **three** forms across the repository, of which two did not
> run: this one, and a comment in `crdt-core/pom.xml` citing a `-Pbenchmark` profile that has never
> existed (`mvn -pl crdt-core -Pbenchmark test-compile exec:exec` — a BUILD FAILURE, since bare
> `exec:exec` picks up no execution's configuration). The test commands in the [README](../README.md#tests) and
> `nightly.yml` had it right all along. All four now agree, and the pom comment records what it
> used to say.

| Operation | Document | `ArrayBlockList` | `TreapBlockList` | Difference |
|---|---:|---:|---:|---:|
| Position in text → identifier | 1 000 | 0.367 µs | 0.065 µs | 5.6× |
| | 10 000 | 5.510 µs | 0.149 µs | 37× |
| | 100 000 | **60.2 µs** | **0.286 µs** | **210×** |
| Identifier → position | 1 000 | 0.187 µs | 0.068 µs | 2.7× |
| | 10 000 | 2.863 µs | 0.122 µs | 23× |
| | 100 000 | **40.3 µs** | **0.210 µs** | **192×** |
| Full insertion | 100 000 | 684 µs | 4.66 µs | 147× |
| Reading the whole text | 100 000 | 310 µs | 429 µs | **0.72× (treap SLOWER)** |

The shape matters more than the individual numbers. For a tenfold larger document, the naive
search grows **15× and 11×** (linearly) and the treap grows **2.3× and 1.9×** (logarithmically).
That is exactly the difference that made the second implementation worth writing — and it is
visible only after measuring, not after reading off the complexity.

**Reading the whole text is 40% slower in the treap**, and that is not a measurement error:
walking a tree has worse memory locality than iterating an array. That operation runs once when
a document is opened, while lookup runs on every keystroke, so the trade is obvious. But it is a
trade, not a free improvement, and sweeping it under the rug would be dishonest towards the
numbers.

### Propagation between clients

A custom harness rather than Gatling — the justification is in `LoadHarnessIT`: the quantity of
interest is the gap between events at **two different clients**, so both ends must share one
clock. Clients and server run in one process and network latency is ≈ 0; the numbers describe
the cost of the system, not the experience of a user over the internet.

Each client performs 40 insertions **at the start of the document** (not at the end — typing
continuously compresses into a single block and bypasses the path that actually scales with
size).

| Document | Clients | p50 | p95 | p99 | Convergence |
|---:|---:|---:|---:|---:|---|
| empty | 2 | 27 ms | 29 ms | 30 ms | 55 ms |
| empty | 10 | 74 ms | 83 ms | 86 ms | 516 ms |
| empty | 25 | 114 ms | 154 ms | 165 ms | 513 ms |
| empty | 50 | 202 ms | 360 ms | 374 ms | 499 ms |
| 10 000 chars | 2 | 9.3 ms | 10 ms | 10 ms | 60 ms |
| 10 000 chars | 10 | 28 ms | 31 ms | 32 ms | 60 ms |
| 10 000 chars | 25 | 46 ms | 76 ms | 84 ms | 107 ms |
| 10 000 chars | 50 | 135 ms | 218 ms | 230 ms | 267 ms |
| 100 000 chars | 2 | 4.5 ms | 586 ms | 586 ms | 603 ms |
| 100 000 chars | 10 | 13 ms | 518 ms | 830 ms | 831 ms |
| 100 000 chars | 25 | 67 ms | 92 ms | 695 ms | 712 ms |
| 100 000 chars | 50 | 124 ms | 212 ms | 1299 ms | 1647 ms |

**All twelve scenarios converge**, including 50 clients in a 100 000-character document. The
table was **repeated after the code-review changes** and reproduced within noise — with one
exception worth recording: p50 for 10 000 characters and 10 clients came out as 28 ms once and
43 ms another time. That is the **spread between runs on the same machine**, not a difference
between versions of the code, and it is the best measure of how seriously to take the second
significant digit in this table. Document size has almost no effect on median latency — after
swapping the index for a treap, the data structure stopped being the bottleneck.

**The p99 tails here are noticeably fatter than in the previous measurement, and this is the
PRICE OF RATE LIMITING** introduced in CP12. The harness seeds the 100 000-character document
with two hundred operations sent without pause, which exhausts the limiter's burst. The
behaviour is correct — nothing is lost — but the number is worse, and it stays in the table as
it is.

#### The explanation of that tail which used to stand here was WRONG — checked in CP20

The previous version of this paragraph ended with: *"rejected operations are retried after a
second, and that second is what the p99 shows"*. It sounded reasonable: the client waited a
hardcoded 1000 ms, while the token bucket refills at 60/s, so the real time to one token is
~17 ms. A sixtyfold overestimate looked like the obvious cause of a fat tail.

CP20 made the server send back the exact wait, which the bucket knows anyway. Measured with
three harness runs back to back on the same machine:

| 100 000 chars, 50 clients | p99 | rejections |
|---|---:|---:|
| before (fixed 1 s) | 872 ms | 1523 |
| `retry-after` alone | **1352 ms** | 1614 |
| `retry-after` + exponential backoff | 826 ms | 1368 |

**The server-supplied wait alone made the tail 55% worse.** The reason is instructive: the
server states the time to ONE token, while the client holds dozens of operations in its buffer.
Resending the whole buffer every 17 ms is busy-polling — it creates no tokens and does create
rejections.

Exponential backoff removes that regression, and **that is where the gain ends**: 826 ms against
872 ms sits inside the spread of this rig (see the paragraph above — 28 ms versus 43 ms on
identical code). What actually bounds the tail is **the refill rate times the size of the queued
batch**: a client with N operations behind an empty bucket waits N/60 seconds no matter how
often it asks.

What the change really buys, since it does not buy latency: a guessed constant disappears from
the client, the server states a value only it knows, and rejections at 50 clients drop by ~10%.
It stays in the README because the claim "it is that one second" was published here and turned
out to be untrue.

### Traffic profile — what the previous measurements did NOT cover

This is the first table in which the harness generates **the full production traffic mix**:

| Kind | Up to CP15 | Now |
|---|---|---|
| text operations | yes | yes |
| **presence (cursors)** | **no** | every other keystroke |
| **formatting** | **no** | every tenth edit |

The cursor is the most frequent event in the entire system, and the harness was not sending it.
The previous numbers therefore described a lighter system than the one the browser uses — in a
way that is impossible to notice until you check.

### The cost of the mark layer — a fear confirmed in CP15, removed in CP18

Plan v2 assumed that formatting anchors could block the garbage collector: every mark pins two
tombstones and protects them from removal. CP15 was meant to confirm or refute that **with a
number**. It confirmed it, and more strongly than I had assumed.

Measurement: [`MarkCostMeasurement`](../crdt-core/src/test/java/dev/collabedit/crdt/MarkCostMeasurement.java),
1000 characters, all text deleted, 100 marks (= 200 anchors), GC with a fully stable version
vector.

| How the text was written | Without marks | With marks (CP15) | With marks (**after CP18**) |
|---|---:|---:|---:|
| in one run (**1** run-length block) | 1000 | **0** (0%) | **1000** (**100%**) |
| character by character (1000 blocks) | 1000 | 800 (80%) | **1000** (**100%**) |

The middle column is the CP15 measurement, and it is what forced the change. **GC worked on
blocks, not on characters**: a block in which even one character was an anchor stayed whole.
Text written in one run is one block, so a single anchor rescued the entire document. The plan
predicted a cost of "two tombstones per mark"; the real cost was "one whole block per mark", and
block size depends on the history of typing, not on the formatting.

**CP18 removed this problem by taking literally a sentence that had been in
the tombstone-collection design from the beginning.** All three reasons for keeping a
tombstone — being an origin, being an anchor, causal stability — need the **identity** of a
character, never its content. A block that must not be removed therefore **drops its content and
stays in the structure**: it keeps its identifier, its length and its position, and loses only
the characters nobody can see. The mistake was not in the protection conditions — it was in
treating "I must keep the identity" and "I must keep everything" as the same requirement.

The snapshot format went from 2 to 3, at a cost of **one bit per block** — measured as **+1 B** on
a 50 000-character document. The migration was **free**, and that is the second time the the operation-log decision
decision has paid for itself: a snapshot in an unknown version is ignored rather than migrated, so
the state rebuilds from the log.

What CP18 does **not** fix: the block objects themselves stay. A document edited long enough
still accumulates entries in the block list, and that is now the dominant remaining cost.

The snapshot cost of formatting, on the other hand, is negligible: **9 B per mark** (two anchors,
type, value), measured on prose — see the note below for what that word had to be made to mean. A mark does not copy the text it covers — which is precisely what Peritext exists to
avoid.

> **The absolute snapshot sizes that used to stand in this section are withdrawn, and this README
> is the document with the least excuse for having published them.** The sentence read "one bit per
> block: **107 B → 108 B** at 50 000 characters". A hundred and seven bytes for fifty thousand
> characters is not a fact about the snapshot format. `MarkCostMeasurement` builds its document by
> repeating a single character and `SnapshotCodec` gzips the result: gzip of 50 000 identical
> characters is on the order of 80 bytes, gzip of 50 000 characters of prose on the order of
> 25 000. **Around 300×**, and every bit of the difference is a property of the input.
>
> Three hundred and fifty lines below, this file names that exact mistake about a different
> measurement — *"The measurement uses prose rather than `"x"` repeated, and that distinction is
> the difference between a measurement and a compliment — gzip crushes a repeated character and
> would have reported 259×"* — and the lesson learnt there in CP24 never came back to correct this.
> The two paragraphs sat in one file, one of them explaining why the other was wrong.
>
> **Re-measured on prose, and the fixture had a second version of the same mistake in it.** The
> first attempt replaced `"x".repeat(50_000)` with a cycle of thirty-four words and reported
> **706 B** — a ratio of 71:1, where real English gzips at roughly 3:1. A short repeating
> vocabulary sits entirely inside the compressor's window, so it is the same error one step less
> obvious. `MarkCostMeasurement.prose` now draws from an eighty-word vocabulary in a deterministic
> splitmix64 order, and **prints its own compression ratio next to the byte count**, because a
> number that carries its caveat is quotable and one that needs a footnote somebody else has to
> remember is not.
>
> | 50 000 characters | snapshot | ratio |
> |---|---:|---:|
> | `"x"` repeated (what used to be published) | 107 B | ~600:1 |
> | thirty-four words cycled (first attempt) | 706 B | 71:1 |
> | **eighty words, pseudo-random order** | **10 901 B** | **4.6:1** |
>
> With 500 marks over that text the snapshot is **15 779 B**, so the per-mark overhead is **9 B**
> rather than the 8 B measured through the degenerate input — the delta was, as expected, the part
> that was never wrong, because a difference between two snapshots of the same text cancels the
> text out. It moved by one byte, and the conclusion does not move at all: a mark costs a pair of
> anchors plus a type, and does not copy the text it covers.
>
> Still not real English — 4.6:1 against roughly 3:1 — so the baseline remains an underestimate,
> which is why the ratio is printed rather than described.

The third thing measured — **the cost of computing the appearance**, on a 50 000-character
document. The table is before and after CP20, measured in one run on the same machine (median of
11 samples):

| Marks | `O(m²)` — before | sweep — after | Speed-up |
|---:|---:|---:|---:|
| 100 | 0.39 ms | 0.27 ms | 1.5× |
| 400 | 0.58 ms | 0.49 ms | 1.2× |
| 1600 | 4.18 ms | 1.49 ms | 2.8× |
| 6400 | **62.1 ms** | **4.72 ms** | **13×** |

This runs **on every keystroke**. The previous version walked every mark at every segment; now
the segments are walked left to right with the set of active ranges maintained incrementally —
each range enters and leaves exactly once. The usable limit moved from **hundreds** of marks to
**thousands**.

Two things this measurement showed along the way, both more interesting than the speed-up
itself:

1. **At 100 and 400 marks there is practically no difference.** Below roughly a thousand, the
   dominant cost is not picking a winner at all — it is **resolving anchors** through the block
   index, two lookups per mark. The complexity fix starts paying exactly where the README said
   the limit was, and not one bit earlier.
2. **The previous measurement was far too noisy to compare anything with.** It timed **one**
   call and gave 0.8 ms once and 4.7 ms another time at 400 marks — a fivefold spread on
   identical code. The figures 0.6 / 0.8 / 5.3 ms in the previous version of this README came
   from exactly that kind of single shot.

#### A row of that table was measuring two things at once

The version above also carried a **growth per 4× marks** row, quoting ×3.1 → ×3.2 for the
sweep. That row is gone, because the series it summarised was not comparable with itself.

Marks were laid down every `50000 / markCount` characters and each covered a fixed 30. That is
fine while the stride exceeds 30 — and at 6400 marks the stride is **7**. The ranges then
overlapped four to five deep and, all carrying the same type and value, `mergeAdjacent` folded
the whole document into **one** span. So the last row measured 6400 ranges → 1 span while the
row above it measured 1600 ranges → 1600 spans, and the label said only "×4 marks".

The irony is that this row was **added in CP20** precisely to make the `O(m²)` → `O(m log m)`
difference visible. The one row that had to be comparable was the one that was not.

**What survives and what does not.** The before/after columns stand: both implementations were
fed identical input, so the 13× speed-up at 6400 is real. What does not stand is reading a
complexity *shape* off that series. Re-measured with the width capped at `stride - 1`, so the
ranges stay disjoint at every count (median of 11 samples, median of 3 runs):

| Marks | Spans | Resolve | Growth per 4× marks |
|---:|---:|---:|---:|
| 100 | 100 | 0.28 ms | |
| 400 | 400 | 0.48 ms | ×1.7 |
| 1600 | 1600 | 1.43 ms | ×3.0 |
| 6400 | 6400 | 3.27 ms | ×2.3 |

Every ratio is **at or below ×4 for a ×4 in marks** — sub-linear in this range, because a fixed
cost independent of the mark count still dominates. `O(m²)` would predict ×16. The test now
asserts `spans == markCount` at every step, so if a future change makes the ranges overlap
again it fails as a broken measurement rather than reading as a complexity regression.

**Overlap depth turns out to be a separate variable, and a cheap one.** Holding the mark count
at 1600 and varying only the width:

| Width | Approx. depth | Spans | Resolve |
|---:|---:|---:|---:|
| 30 | 1 | 1600 | 1.44 ms |
| 62 | 2 | 1 | 1.21 ms |
| 124 | 4 | 1 | 1.07 ms |
| 248 | 8 | 1 | 1.30 ms |

Flat within noise. The sweep's cost is driven by **how many marks there are**, not by how deeply
they stack — which is what the per-type `TreeSet` predicts, since resolving a segment reads only
the front of each type regardless of how many ranges sit behind it.

**One caveat that applies to every millisecond on this page.** The same code, same marks, same
machine measured **0.28 ms** for 100 marks when the test ran alone and **0.039 ms** when it ran
inside the full suite — a sevenfold difference produced by nothing but JIT warm-up state. That
is a stronger version of the argument CP19 already made when it refused to put a millisecond
threshold in CI: on this rig, absolute times are comparable only against other times measured
the same way, which is why the tables above state how they were run.

### How often the interleaving anomaly actually fires

RGA's interleaving anomaly is the most-cited weakness of the algorithm this project chose, and
until now it was described here in words. `RgaInterleavingTest` proves it **exists** — it builds
the case by hand and pins it. The CRDT decision names Fugue as the fix and
declines it on budget. Neither said how often a real session runs into it.

Measurement:
[`InterleavingMeasurement`](../crdt-core/src/test/java/dev/collabedit/crdt/InterleavingMeasurement.java).
Two authors typing 8 characters each, **at the same point in the document**, each in complete
isolation before exchanging operations in shuffled order — the worst case, so every rate below is
an upper bound. The metric is **fragments**: how many pieces an author's writing is broken into.
One means it survived intact.

The variable is not the author count and not the document size. It is **whether the cursor moves
forward**. RGA orders *siblings* — insertions sharing one origin — by timestamp, and that is the
only place two authors can get mixed. Typing forward makes each character the origin of the next,
so a run is a chain, and a chain cannot be broken by anything arriving concurrently. Going back to
the same spot produces nothing but siblings.

| Cursor advances with probability | Scenarios interleaved | Median fragments | Worst seen |
|---:|---:|---:|---:|
| 1.00 — ordinary typing | **0.0%** | 1 | 1 |
| 0.99 | 1.2% | 1 | 2 |
| 0.95 | 4.9% | 1 | 2 |
| 0.90 | 10.9% | 1 | 3 |
| 0.75 | 25.8% | 1 | 4 |
| 0.50 | 49.2% | 1 | 7 |
| 0.25 | 74.3% | 2 | 8 |
| 0.00 — the hand-built case | **100.0%** | 8 | 8 |

**The 0.0% in the first row is structural, not statistical**, and it is the load-bearing result:
pure forward typing cannot interleave, for the reason above. A separate test asserts it over 2000
trials, so if it ever stops holding the failure is a merge bug rather than a shifted percentage.

More authors meeting at one point, at 90% forward:

| Authors | Interleaved | Worst fragments |
|---:|---:|---:|
| 2 | 10.9% | 3 |
| 3 | 20.3% | 3 |
| 5 | 35.9% | 3 |
| 8 | 52.4% | 3 |

**What this changes about the Fugue decision.** It stays rejected, and now for a measured reason
rather than a budgetary one. The anomaly needs three things at once: two people in the *same
spot*, at the *same time*, with at least one of them *going back to fix something*. When it does
fire the damage is usually one extra fragment, not the shredding the hand-built case shows — that
case is `p = 0`, which is nobody's typing.

<details>
<summary>The first version of this table was an artefact of <code>java.util.Random</code> (expand)</summary>

The measurement initially reported **0% at p = 0.90 and 100% at p = 0.50** — a cliff where the
mechanism predicts a smooth curve. Chasing it found nothing wrong with the merge rule. The fault
was the seeding: trials ran `new Random(0)`, `new Random(1)`, and so on.

```
new Random(0).nextDouble = 0.730968
new Random(1).nextDouble = 0.730878
new Random(2).nextDouble = 0.731147
new Random(9).nextDouble = 0.730162
```

`java.util.Random` is a linear congruential generator whose **first** output barely moves across
consecutive small seeds. Every trial therefore made the same decision at the first keystroke —
and the first keystroke is the only one that matters here, because it decides whether an author
gets two children of the shared origin. Above 0.731 the answer was always "advance" and nothing
interleaved; below it, always "stay" and everything did.

Two thousand trials per row, reported to three significant figures, describing a property of the
random number generator. The seed now goes through splitmix64 first — the same mixing function
`TreapBlockList` uses for heap priorities, for the same reason.

</details>

### The degradation point — settled, and not in my rig's favour

The previous version of this README ended here with "I do not know whether the cause lies in the
system or in the measurement rig". **It was in the rig.**

`TestStompClient` built a `StandardWebSocketClient` without configuring the JSR-356 container,
whose default text buffer is **8 KiB** — eight times smaller than the frame limit this system
declares (the transport decision). Any larger message simply never reached the
test client.

Why this presented as an *unrecoverable* divergence rather than an ordinary dropped frame:

1. Broadcasts went one operation per frame (~70 B), so **the threshold was unreachable** and the
   mechanism looked healthy.
2. When one operation did get lost, the client correctly detected the gap and asked for
   resynchronisation.
3. The resynchronisation response is split into chunks of **300 operations** (a CP8 fix), which
   is about **15 KB** — above 8 KiB. **The recovery mechanism was the only thing the buffer did
   not let through.**

Hence exactly the symptom that was in the README: a server holding every operation, some clients
permanently a few operations behind, and no error in the log. A "fix" that shortened the
resynchronisation chunk would have repaired this on the rig and changed nothing in the system.

After raising the rig's buffer, **all 12 scenarios converge**, and `BroadcastFanoutIT` gives 3/3
repeatable convergences for 2, 10, 25 and 50 clients — including on the code from *before*
broadcast coalescing. Coalescing is therefore justified by **latency, not by fixing
convergence**, and it is described that way above.

What this does not prove: clients and server still run in one process, so the numbers describe
the cost of the system, not the experience over the internet. Splitting the rig across separate
machines remains undone — but it is no longer needed to answer the question "system or rig".

---

