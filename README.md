# Collaborative Document Editor

![Demo: two people editing at once, one loses the network, changes merge on reconnect, a version is rolled back](docs/demo.gif)

*Two browsers, one document. Concurrent editing of the same paragraph, work continuing after
the network is cut, merging on reconnect, and a rollback to an earlier version — with no
conflict and without losing a single character. The recording is produced from a
[script](frontend/e2e/demo.spec.ts), not from manual clicking, so it shows exactly what CI
checks.*

![Demo: one person bolds a range, another types inside it, a comment on a selection, deleting anchored text, restoring a document from the trash](docs/demo-v2.gif)

*The same thing, one layer up. Anna bolds a fragment; Marek — not seeing it yet — types a word
in the **middle** of it, and the formatting covers the new text instead of leaving a hole in
itself. Then a comment on a selection (the highlight appears for the other person immediately,
because the anchor is an ordinary CRDT operation), deleting the anchored text — the thread
stays, marked as detached — and restoring a document from the trash.*

A real-time text editor: many users edit the same document at once, without conflicts and
without losing changes — including over a poor connection, while editing the same fragment,
and after a client disconnects.

The merge algorithm (an RGA-style CRDT) is **written from scratch**, in Java on the server and
in TypeScript on the client. It is not a wrapper around Yjs or Automerge — the reason, and a
comparison with the off-the-shelf options, is in [ADR-0001](docs/adr/0001-crdt-vs-ot.md).

The same core carries **text formatting and comments**. These are not features bolted on beside
the algorithm; they are applications of it. A formatting mark is a range between anchors pinned
to characters, and a comment anchor is that same mechanism with a thread identifier instead of a
formatting type. The character-sequence layer did not change by **a single field** in the
process ([ADR-0010](docs/adr/0010-rich-text.md)).

---

## What is interesting here

If you have three minutes:

1. **[The tests were checked for whether they catch anything at all](docs/crdt-design.md#what-this-taught-me-about-tests).**
   I introduced a mutation into production code and the entire property-test suite passed —
   because every scenario shared one blind spot. Along the way I **withdrew a claim from the
   documentation that I could not confirm**: 200 000 random scenarios showed the algorithm
   converges even without a condition I had believed was necessary.
2. **[ADR-0004](docs/adr/0004-skalowanie-poziome.md) — every backend instance is a CRDT
   replica.** No leader, no distributed lock, no sticky sessions. Redis is an unreliable fast
   path; the operation log in Postgres is the authoritative one. A lost message is therefore a
   latency problem rather than a correctness problem, and a
   [chaos test](app/src/test/java/dev/collabedit/app/ClusterChaosIT.java) stops Redis mid-write
   to check that this is actually true.
3. **[An open performance question turned out to be a flaw in my measurement rig, not in the
   system.](#the-degradation-point--settled-and-not-in-my-rigs-favour)**
   For all of CP8, "above ~10 clients convergence stops being repeatable" looked like a limit of
   the system. It was a default 8 KiB buffer in the test client, which let broadcasts through
   and **blocked only the resynchronisation responses** — that is, the one mechanism capable of
   repairing a lost operation. Incidentally, the treap is 210× faster than a linear scan at
   100 000 characters — and 1.4× **slower** when reading the whole text.
4. **[Rich text in a CRDT is a different problem from formatting in an editor](docs/adr/0010-rich-text.md).**
   One sentence shows why: Ania bolds a range, and Bartek — not seeing it yet — types inside it.
   With formatting stored as a per-character attribute you get a hole nobody made. The solution
   (anchors instead of offsets) is the same principle the project already applied to cursors,
   which is the best argument that the earlier decision was right.

Nine bugs in this project were found by tests, by measurement or by a plan audit rather than by
code review: a document duplicated on open; a snapshot that could not be written above 64 KB; a
resynchronisation response exceeding the frame limit; a display name containing `|` invalidating
its own token; GC deleting formatting anchors; a selection starting at position zero invisible
to the comments panel; **operations rejected by the rate limiter and lost silently**; and —
found only during the final plan audit — **history as a third, unsecured route to the content
and to overwriting someone else's document**; and — found while writing plan v3 against the
code — **a fourth route: a recovered draft created without a valid token landed as a document
editable by everyone**, the exact inverse of the intent of the fix that was supposed to make it
private. All of them are described in the commits and in comments at the point of repair.

One more category joins that list, which did not exist before: **a claim in this README that
turned out to be false once measured.** The explanation of the fat p99 tail ("it is that one
second of waiting") was disproved by its own measurement — details in
[Performance](#performance).

---

## Architecture

```
┌─ frontend (React + TypeScript, Vite) ───────────────────────┐
│  CodeMirror 6 — VIEW LAYER ONLY (ADR-0006)                   │
│  crdt/        — own RGA implementation + mark layer           │
│  transport/   — STOMP + offline buffer + network simulator    │
│  ui/          — document list, history, comments              │
└──────────────────────────┬──────────────────────────────────┘
                           │  WebSocket / STOMP
                           │  (traceparent in the frame header)
┌──────────────────────────▼──────────────────────────────────┐
│ app — Spring Boot 3, Java 21                                 │
│   ws/        STOMP, presence, frame authorization, rate limit │
│   auth/      roles, permissions, tokens                       │
│   domain/    in-memory document replica                       │
│   persist/   operation log + snapshots + document catalog     │
│   history/   time travel                                      │
│   comments/  threads (content outside the CRDT — ADR-0011)    │
│   cluster/   Redis pub/sub + anti-entropy                     │
└────────┬─────────────────────────┬──────────────────────────┘
         │                         │
    PostgreSQL                  Redis
  (source of truth)        (unreliable fast path)

crdt-core/ — a separate Maven module, ZERO dependencies on Spring.
             Algorithm + mark layer + property tests + JMH.
```

Splitting out `crdt-core` without Spring is deliberate: the algorithm can be tested and
benchmarked in isolation, and the compiler enforces that the application layer does not leak
into the core.

---

## Architecture decisions

All of them in [`docs/adr/`](docs/adr/). Every ADR has a **Negative consequences** section that
is never empty, and — where I do not know something yet — an explicit *"to be verified in CP-X"*
rather than manufactured certainty.

| ADR | Decision |
|---|---|
| [0001](docs/adr/0001-crdt-vs-ot.md) | RGA (CRDT) rather than OT — and why not Logoot, not Fugue, not Yjs |
| [0002](docs/adr/0002-transport.md) | WebSocket + STOMP; a justification for every heartbeat and buffer-limit value |
| [0003](docs/adr/0003-historia-zmian.md) | Operation log as the source of truth, snapshots as a disposable cache |
| [0004](docs/adr/0004-skalowanie-poziome.md) | Backend instance as a CRDT replica + anti-entropy |
| [0005](docs/adr/0005-tombstone-gc.md) | Tombstone GC via causal stability |
| [0006](docs/adr/0006-warstwa-edytora.md) | CodeMirror 6 as the view; the document model is our own |
| [0007](docs/adr/0007-zakres-ui.md) | The mockup's visual language accepted; its functional scope rejected — **superseded by 0008** |
| [0008](docs/adr/0008-rozszerzenie-zakresu.md) | Widening the scope from artifact to product, conditional on keeping the core deep |
| [0009](docs/adr/0009-model-uprawnien.md) | Ordered roles, enforced in both layers from one rule |
| [0010](docs/adr/0010-rich-text.md) | Formatting as a separate CRDT layer (Peritext), not a character attribute |
| [0011](docs/adr/0011-komentarze.md) | The comment anchor lives in the CRDT, the conversation does not |
| [0012](docs/adr/0012-autoryzacja-strukturalna.md) | Authorization declared at the endpoint; an endpoint that declares nothing **is not served** |
| [0013](docs/adr/0013-symulacja-deterministyczna.md) | Deterministic simulation as a third testing pillar — a seed instead of a thread interleaving |

Diagrams (architecture, the path of a single edit, connection states):
[`docs/architektura.md`](docs/architektura.md).
Implementation details of the algorithm: [`docs/crdt-design.md`](docs/crdt-design.md).
Work plans and checkpoints: [v1](docs/IMPLEMENTATION_PLAN.md) (CP1–CP9),
[v2](docs/PLAN-v2.md) (CP10–CP15), [v3](docs/PLAN-v3.md) (CP16–CP20 — adds no features, trades
vigilance for construction).

---

## Running it

### Fastest: Docker only

```bash
docker compose --profile app up -d     # → http://localhost:5173
```

Builds the backend and the frontend from this repository and brings everything up together with
the database and Redis. Needs neither a JDK nor Node. The first run takes a few minutes (Maven
fetching dependencies); later ones are instant.

### For working on the code

**Requirements:** JDK 21, Node 22.12+, Docker.

<details>
<summary>If you use colima instead of Docker Desktop (expand)</summary>

Testcontainers does not read the docker context, and Docker 29+ rejects the API version that
docker-java negotiates by default. Three things are needed — each fixes a different symptom of
the same problem, and all of them are misleading because they read like "no Docker":

```bash
# 1. point at the socket (otherwise: "Could not find a valid Docker environment")
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock

# 2. the socket path INSIDE the virtual machine — for the Ryuk cleanup container
#    (otherwise: "error while creating mount source path ... operation not supported")
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock

# 3. API version (otherwise: "client version 1.32 is too old")
echo "api.version=1.44" > ~/.docker-java.properties
```

None of this is needed on GitHub runners — Docker is native there.
</details>

```bash
# Infrastructure dependencies
docker compose up -d postgres redis

# Token signing secret — REQUIRED, with no default (CP16).
# The server refuses to start without it, and that is deliberate: a secret checked into the
# repository would mean any reader could mint a token for any user. Docker Compose and CI set
# it themselves; only running directly needs this line.
export COLLABEDIT_AUTH_SECRET=$(openssl rand -base64 32)

# Backend
./mvnw -pl app spring-boot:run

# Frontend
cd frontend && npm install && npm run dev

# Observability stack (CP7) — Prometheus, Tempo, Grafana on :3001
docker compose --profile observability up -d
```

### Deploying it somewhere that is not a laptop

The frontend resolves the backend address at build time
([`frontend/src/config.ts`](frontend/src/config.ts)) and defaults to **the same origin it was
served from**, with nginx proxying `/api` and `/ws`. That is the only arrangement that works
over TLS: a page served over `https:` may not open a `ws:` socket, and browsers block it as
mixed content with no fallback.

```bash
# Same origin (recommended). BACKEND_ORIGIN tells the container's nginx where the app is.
docker build -t collabedit-web ./frontend
docker run -e BACKEND_ORIGIN=http://app:8080 -p 80:80 collabedit-web

# Separate origins, if the backend genuinely lives elsewhere.
docker build --build-arg VITE_API_BASE=https://api.example.test -t collabedit-web ./frontend
```

Two things the server needs, both defaulting to local-only on purpose:

| Variable | Why it has no permissive default |
|---|---|
| `COLLABEDIT_AUTH_SECRET` | No default at all — a secret in the repository means any reader can mint a token for any user (CP16) |
| `COLLABEDIT_WEBSOCKET_ALLOWEDORIGINPATTERNS` | Defaults to `localhost` only. An allow-list that defaults to `*` works everywhere, including everywhere it should not |

Browser tracing is **off** unless `VITE_OTLP_ENDPOINT` is set at build time. It used to point at
`http://localhost:4318` unconditionally, which on a hosted instance means every visitor's browser
firing a blocked request once a second at a collector on *their* machine.

The project **does not inherit from `spring-boot-starter-parent`**. That is deliberate: if it
did, the `crdt-core` module — which by design has nothing to do with Spring
([ADR-0001](docs/adr/0001-crdt-vs-ot.md)) — would inherit its dependency management and
conventions, and the architectural boundary being claimed would be only a claim. Instead
`spring-boot-dependencies` is imported as a BOM, so versions stay consistent while the
dependency points one way and touches only the `app` module.

---

## Tests

```bash
./mvnw -pl crdt-core test              # property tests (fixed seed, deterministic)
./mvnw -pl crdt-core -Pthorough test   # 10 000 tries — as in the nightly CI run
./mvnw install                         # everything, including integration on a real Postgres
./mvnw -pl crdt-core exec:exec@run-benchmarks                # JMH benchmarks (~7 min)
./mvnw -pl app test -Dtest=LoadHarnessIT -Dbenchmark=true    # load harness (~10 min)
./mvnw -pl app test -Dtest=BroadcastFanoutIT -Dfanout=true   # how many clients converge REPEATABLY
./mvnw -pl app test -Dtest=ClusterSimulationTest             # deterministic simulation (~1 s)
./mvnw -pl app test -Dtest=ClusterSimulationTest -Dsimulation.seeds=20000   # as in nightly CI
cd frontend && npm test                                      # client unit tests (vitest)
cd frontend && npm run e2e                                   # two browsers (needs a running stack)
```

Tests are organised by the failure mode they catch, not by coverage.

| Test | The real failure mode it catches |
|---|---|
| `convergence` | Merging is not commutative — changes silently lost when two people type at once |
| `convergenceWithInterleavedDelivery` | Bugs visible only when a replica writes on state already merged with someone else's changes |
| `noOperationStaysPending` | An operation stuck in the buffer forever — the replicas agree in *not* having the change, so a convergence test alone cannot see it |
| `idempotence` | A retransmission after reconnect, or a repeat from Redis, duplicates text |
| `deliveryOrderDoesNotMatter` | An algorithm that is deterministic but order-dependent — works on one server, breaks when scaled out |
| `singleReplicaBehavesLikeStringBuilder` | A bug mapping position to identifier; every replica is wrong in the same way and converges on a shared but **incorrect** text |
| `rgaLinearizationInvariantHolds` | As above — it looks at the structure itself, so a shared error cannot fool it |
| `sequentialTypingCompressesIntoFewBlocks` | Run-length compression never happens; correctness intact, but a 100 000-character document is 100 000 objects again |
| `naiveAndIndexedProduceIdenticalDocuments` | A bug in the treap aggregates — most often a missing update after marking a tombstone |
| `bothImplementationsAnswerQueriesIdentically` | A wrong position out of the tree after a rotation: the text is right, but the user's cursor lands elsewhere |
| `gcPreservesText` | GC deletes a node that is someone's origin — the structure breaks only at the next edit in that spot |
| `backwardTypingInterleaves_knownLimitation` | Catches no bug — it **pins down a known RGA limitation** ([ADR-0001](docs/adr/0001-crdt-vs-ot.md)) |
| `formattingConverges` | Marks merge differently from text — the document agrees but looks different to two people |
| `textInsertedIntoMarkedRangeInheritsMark` | Typing inside bold creates a hole nobody made — the reason anchors replaced offsets ([ADR-0010](docs/adr/0010-rich-text.md)) |
| `markWaitsForItsAnchors` | A mark arrived before the character it is anchored to — it vanishes silently instead of waiting |
| `garbageCollectionSparesMarkAnchors` | GC deletes an anchor: formatting disappears **not at the edit, but at the next snapshot**, so the culprit is long out of suspicion |
| `textTypedOverDeletedRangeDoesNotInheritItsMark` | The inverse of the above — a protected anchor starts formatting text created after the range was deleted |
| `websocketCannotSubscribeToForeignPrivateDocument` | Knowing an identifier is enough to read someone else's document — a hole **opened** by CP11 and closed by CP12 in both layers |
| `historyEndpointsCheckAccess` | **A third route to the same data.** CP12 secured the WebSocket and `GET /documents/{id}`, while history handed out the full content of any document and allowed it to be **overwritten** — the only place a reader could change someone else's text |
| `documentRoleOverridesWorkspaceRole` | Workspace inheritance overrides an explicit restriction — you cannot revoke access to one document without removing somebody from the team |
| `threadSurvivesDocumentRestore` | Rolling back a version deletes conversations added later |
| `viewerCannotFormatText` | Permissions checked for text but not for formatting — which travels the same channel and is just as much a change to the document |
| `identityComesFromTokenNotFromRequest` | Identity taken from a request parameter instead of the token; another person's identifier is visible in every presence frame |
| `recoveredDraftIsPrivateToItsOwner` | The rescue copy of one person's unsynchronised work is created visible to everyone |
| `a document that cannot be opened` (e2e) | An access denial ends in a blank page — a symptom indistinguishable from a crash |
| `viewerCannotWriteText` / `commenterCannotChangeText` | Permissions enforced only in the interface; a frame sent from the console goes through |
| `everyDocumentScopedEndpointIsCoveredByTheRule` | **A new controller starts without authorization and says nothing about it** — exactly as `HistoryController` did for a whole checkpoint. The architecture test fails the build; the guard refuses to serve it at runtime |
| `publicEndpointsAreExactlyTheseThree` | Somebody silences the guard to make the build green. The list of endpoints without authorization is pinned by name, so the change is visible in the diff |
| `recoveredDraftCannotBeCreatedAnonymously` | An **expired token** makes a recovered draft come out as `link` — editable by every logged-in user. The inverse of the intent of the fix that was supposed to make it private |
| `undeclaredEndpointFailsClosed` | An undeclared endpoint is SERVED rather than refused — which makes the whole construction in ADR-0012 decoration |
| `sameSeedProducesIdenticalHistory` | **The simulation is not deterministic.** Then every scenario built on it is a chaos test with extra steps, and a failure found there still cannot be reproduced |
| `differentSeedsProduceDifferentHistories` | The seed is ignored and every run explores one interleaving — a suite that looks like a thousand scenarios while being one |
| `totalFastPathFailureStillConverges` | The strongest form of the ADR-0004 claim: with Redis dropping **everything**, the log alone must bring the instances into agreement. Otherwise "unreliable fast path" is not a true description of the system |
| `droppedContentBlockKeepsIdentityAndLength` | A block that forgot its length forgets where every character after it lives — the text reads correctly and every cursor past that point is off |
| `visibleBlockRefusesToDropItsContent` | Dropping the content of a visible block deletes text nobody deleted, and does it silently — because the length stays |
| `naiveLookupIsVisiblyLinear` | The complexity-shape gate passes while measuring nothing. The control: the naive index MUST blow the budget the treap stays inside |
| `waitingTheAdvertisedTimeIsEnough` | The wait the server advertises is a millisecond too short, so an honest client is refused a second time — the mechanism amplifies the load it was meant to reduce |
| `threadSurvivesDeletionOfAnchoredText` | Deleting text deletes the conversation about it — losing the context of a decision people come back to months later |

**Whether these tests have teeth — checked, not assumed.** Inverting the RGA scan condition in
production code fails 9 of 13 convergence tests. Removing the aggregate updates in the treap
fails exactly the two that describe it. Disabling anchor protection in GC fails
`garbageCollectionSparesMarkAnchors` — but the **first** time it did not, because the test had
anchored on a character that was already protected as the next block's origin. The test passed
with the mechanism it was checking switched off. That was the **third time** a mutation revealed
a test that verified nothing — [a note on what that
taught me](docs/crdt-design.md#what-this-taught-me-about-tests).

A separate category of the same problem: **a test that does not run**. `pom.xml` carries its own
list of test class-name patterns, because Surefire's defaults do not cover `*Properties` — and
when a `*Measurement` class arrived in CP15, history repeated itself symptom for symptom: the
class passed when run directly, did not run in the build, and the build was green. A warning
about that trap had been sitting in `pom.xml` since CP2 and was not enough. What caught it was
counting the tests in the reports and comparing against the list of files in the repository.

---

## Performance

All numbers come from one machine: **MacBook, Apple Silicon (aarch64), 10 cores, JDK 25-ea, 4 GB
JVM heap**, with Postgres and Redis in containers on the same machine. A number without its
machine is useless, so it is stated next to every table.

### The index structure: O(n) versus O(log n)

JMH benchmark (`./mvnw -pl crdt-core exec:exec@run-benchmarks`), average operation time in
microseconds, 3 warm-up iterations + 5 measurement iterations of 2 s.

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

Measurement: [`MarkCostMeasurement`](crdt-core/src/test/java/dev/collabedit/crdt/MarkCostMeasurement.java),
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
[ADR-0005](docs/adr/0005-tombstone-gc.md) from the beginning.** All three reasons for keeping a
tombstone — being an origin, being an anchor, causal stability — need the **identity** of a
character, never its content. A block that must not be removed therefore **drops its content and
stays in the structure**: it keeps its identifier, its length and its position, and loses only
the characters nobody can see. The mistake was not in the protection conditions — it was in
treating "I must keep the identity" and "I must keep everything" as the same requirement.

The snapshot format went from 2 to 3 (one bit per block: **107 B → 108 B** at 50 000
characters). The migration was **free**, and that is the second time the ADR-0003 decision has
paid for itself: a snapshot in an unknown version is ignored rather than migrated, so the state
rebuilds from the log.

What CP18 does **not** fix: the block objects themselves stay. A document edited long enough
still accumulates entries in the block list, and that is now the dominant remaining cost.

The snapshot cost, on the other hand, is negligible: **8 B per mark** (two anchors, type, value).
A mark does not copy the text it covers — which is precisely what Peritext exists to avoid.

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
the case by hand and pins it. [ADR-0001](docs/adr/0001-crdt-vs-ot.md) names Fugue as the fix and
declines it on budget. Neither said how often a real session runs into it.

Measurement:
[`InterleavingMeasurement`](crdt-core/src/test/java/dev/collabedit/crdt/InterleavingMeasurement.java).
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
new Random(0).nextDouble() = 0.730968
new Random(1).nextDouble() = 0.730878
new Random(2).nextDouble() = 0.731147
new Random(9).nextDouble() = 0.730162
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
declares ([ADR-0002](docs/adr/0002-transport.md)). Any larger message simply never reached the
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

## Failure modes

Exactly what happens when something goes wrong. Every row has the source of the decision, and
those marked as verified have a test that checks it.

| Scenario | Behaviour | Verified |
|---|---|---|
| **Connection lost mid-edit** | Local editing continues. Operations go into an IndexedDB buffer and survive the tab being closed. On reconnect the client exchanges cursors with the server and sends the backlog. | `e2e: network cut`, `e2e: durable buffer` |
| **Instance dies between broadcast and write** | The operation is at the other clients but not in the database. The client retransmits it after reconnecting, because the buffer releases only on confirmation of the **write**, not of the broadcast. | [ADR-0003](docs/adr/0003-historia-zmian.md) |
| **In-memory replica lost / instance restart** | State rebuilt from the operation log: nearest snapshot + tail. Deleting every snapshot does not change the result, only the time. | `stateSurvivesReplicaLoss`, `snapshotsAreOnlyACache` |
| **Redis outage mid-edit** | Editing and writing continue — Redis is the fast path, not the critical one. Instances diverge for the duration of the outage, and the anti-entropy sweep pulls the difference from the log. | `redisOutageIsRepairedByAntiEntropy` |
| **Broadcast to a client lost** | The client detects the gap on receiving an acknowledgement for an operation it does not have, and asks for resynchronisation itself. A `server_seq` cursor would not be enough here — it grows from acknowledgements, so it would look "current" despite the gap. | found and fixed in CP8 |
| **Two people edit the same character** | Both changes kept, deterministic order by `(lamport, replicaId)`, no arbiter. | `concurrentEditOfSameCharacterKeepsBoth`, property `convergence` |
| **Client offline longer than the GC window (7 days)** | Operations **rejected explicitly** (`RESYNC_REQUIRED`), and the local version is saved as a separate "Recovered draft" document. We deliberately do not fake a merge — the information about where those characters belong has been lost. | `staleOperationIsRejectedExplicitly` |
| **An operation arrives ahead of its cause** | It goes into the buffer and is retried after every successful merge. It is NOT confused with the case above — refusal requires both a missing origin and a position below the GC boundary. | `operationAheadOfItsCauseIsNotRejected` |
| **Very large document** | Size has almost no effect on latency (100 000 characters ≈ an empty document) after swapping the index for a treap. Tombstones grow with the number of edits, and GC reclaims less than I had assumed. | [Performance](#performance) section, [ADR-0005](docs/adr/0005-tombstone-gc.md) |
| **50 clients in one document** | Converges repeatably — all 12 harness scenarios, including 50 clients in a 100 000-character document. The earlier observation "above ~10 clients convergence stops being repeatable" turned out to be **a flaw in the measurement rig, not in the system** — see [The degradation point](#the-degradation-point--settled-and-not-in-my-rigs-favour). | `BroadcastFanoutIT`, CP15 harness |
| **Rate limit exceeded** | The server refuses the frame and **sends back `RATE_LIMITED` together with the exact wait**, and the client retries from its unacknowledged buffer with exponential backoff. A refusal for lack of permission is silent (saying "this document exists" would be a leak); a refusal for pace is not — a silent pace refusal is data loss. | CP15 harness: 351 rejections, zero lost operations |
| **Deleting formatted text** | Formatting stays on what survived. Mark anchors are tombstones protected from GC — without that, the document's appearance would vanish during memory cleanup, and not at the moment of deletion but at the next snapshot. | `garbageCollectionSparesMarkAnchors` |
| **Deleting commented text** | The conversation **stays**, marked as detached, with the fragment quoted as of when it was created. Deleting a conversation in response to a text edit is behaviour nobody expects. | `threadSurvivesDeletionOfAnchoredText`, e2e |
| **A role without write permission** | The editor is read-only, the formatting bar greyed out, the comments panel has no input — because the role arrives together with the initial state. The server checks exactly as before; the client merely stops offering a gesture that would be rejected. Without this, a reader typed locally, saw their own text, and their replica silently diverged from everyone else's. | `openedDocumentCarriesCallersRole` |
| **Access revoked mid-session** | Works within 5 s (the lifetime of a permission cache entry). Revocation by the owner invalidates the cache immediately on THAT instance; the others wait for expiry. | `revokingAccessTakesEffect` |

---

## What I would do differently in production

A list of deliberate simplifications and known limits. It was written **during** the work rather
than from memory at the end — which is why it also contains things that only emerged during
implementation and turned out worse than I had assumed.

**Several items on this list have since been done.** I leave them below, struck through, along
with what was confirmed along the way and what was not — a section about the limits of your own
solution loses its point if it shows only the limits that still stand. Full reckoning:
[PLAN-v2](docs/PLAN-v2.md), [PLAN-v3](docs/PLAN-v3.md).

**Algorithm**
- **Fugue instead of RGA.** RGA has an interleaving anomaly Fugue does not have. Still rejected
  on budget — but the entry used to end there, and "documented anomaly" was the one claim in this
  repository carrying an adjective where everything else carries a number. It now carries a
  number too: [how often it actually happens](#how-often-the-interleaving-anomaly-actually-fires).
- ~~**A formatting anchor blocks the WHOLE run-length block**, not just its own tombstone.~~
  **Done (CP18).** Measured at 0% reclamation in CP15, and now 100% — a deleted block drops its
  content and keeps its identity. [Numbers above](#the-cost-of-the-mark-layer--a-fear-confirmed-in-cp15-removed-in-cp18).
- ~~**GC reclaims less than [ADR-0005](docs/adr/0005-tombstone-gc.md) assumes.**~~
  **Done (CP18)**, by the answer this entry itself named: drop the **content** of deleted nodes
  while keeping them in the structure. A tombstone in the middle of the text is still the next
  block's origin and still cannot be removed — CP18 makes that cost a fixed handful of bytes
  instead of the text it used to hold.
- **Two implementations of one algorithm** (Java + TypeScript) is a debt: every change costs
  twice. The conformance-vector corpus protects against silent divergence, but not against the
  cost. In production: one implementation compiled to WebAssembly.
  CP18 was a counterexample worth recording: the change cost nothing on the client, because the
  client has neither GC nor snapshot decoding. The debt is proportional to how much of the
  algorithm the client actually implements, not to the size of the algorithm.
- **The client-side index is naive (O(n)).** On the server the treap arrived after measurement;
  I have not made the equivalent measurement in the browser, so I did not optimise blind. The
  port is mechanical.

**Product scope**
- **Diagnostics live behind `?debug=1`.** The network simulator and the CRDT state view were
  visible in the interface for a while; that was a mistake about the audience
  ([ADR-0007](docs/adr/0007-zakres-ui.md), the revision section). A user should see a product,
  not a measurement rig.
- ~~**No text formatting.**~~ **Done (CP13).** The diagnosis on this list was right — rich text
  in a CRDT really is a separate algorithm, not a character attribute. Peritext arrived as a
  **second layer beside the character sequence**, which did not change by a single field
  ([ADR-0010](docs/adr/0010-rich-text.md)).
- ~~**No comments and no permissions.**~~ **Done (CP12, CP14).** The comment anchor turned out to
  be the same mechanism as formatting and **added not one line to the CRDT layer**
  ([ADR-0011](docs/adr/0011-komentarze.md)). Permissions enforced in both layers from one rule
  ([ADR-0009](docs/adr/0009-model-uprawnien.md)). Remaining limits: comment content does not work
  offline (the buffer covers CRDT operations, not REST), the panel refreshes by polling rather
  than by an event, there are no notifications or mentions, and revoking permissions takes effect
  within the 5 s cache window.
- **Undo works only within a session and does not cover formatting.** `UndoManager` undoes your
  own insertions and deletions and merges continuous typing into one step — but the stack lives
  in the tab's memory and disappears on reload, and mark operations never enter it at all.
  Ctrl+Z after Ctrl+B therefore undoes **the previous text change**, not the bolding. Predictable
  but surprising; mark-aware undo requires extending the stack with operations inverse to
  `AddMark`/`RemoveMark`, which is the same problem as CRDT-aware undo.

**Infrastructure and operations**
- **Password-less authentication**, an HMAC-signed token, with no revocation and no key rotation.
  A token revoked mid-session does not tear down the connection, because we check it once, at
  `CONNECT`.
  ~~The secret has a default value written into the repository.~~ **Fixed in CP16: there is no
  default.** A secret in the repo meant that a deployment forgetting to set the variable signed
  tokens with a key known to every reader — and since the token carries the user identifier,
  knowing the key is enough to mint a token for **any person**. A server with such a key starts,
  serves traffic and reports healthy. It now refuses to start, and the message says how to
  generate a secret. The cost is one environment variable for `spring-boot:run`.
- **The operation log grows without bound.** It needs archival of old segments to object storage,
  and compaction.
- **Presence is instance-local** — a client connected to another instance does not appear in the
  list of people present. Requires a separate channel and entry expiry.
- **The client replays the whole log when opening a document.** The server uses snapshots
  internally; the client should receive a serialised CRDT snapshot rather than the full set of
  operations.
- ~~**No rate limiting**~~ **— there is (CP12), and it taught me something I had not assumed.**
  The first version refused the frame silently, because that is how a refusal for lack of
  permission works. At 100 000 characters and 50 clients that produced 115 rejections and a
  document that **stopped converging** — a rate limit is not a denial of access but a request to
  slow down, so a silent refusal is simply data loss. The server now sends `RATE_LIMITED`, and
  the client retries from its unacknowledged buffer. Measurement found this, not code review.
- **No document sharding.** One document with very many users always loads as many instances as
  hold it in memory.
- **Writes to a single document are serialised** by a row lock, because the continuity of
  `server_seq` is a requirement of gap detection. This is the first place that will limit
  throughput.
- **No SockJS fallback** — a client behind a proxy that blocks `Upgrade` will not connect at all.
- **The GC window (7 days) is a guessed value**, not derived from telemetry. It is also, until
  recently, a value nothing read: [ADR-0005](docs/adr/0005-tombstone-gc.md) defines the replicas
  constraining GC as "connected now, **or disconnected for less than the GC window**", and only the
  first clause was implemented — a disconnected client was forgotten immediately. The failure mode
  below said seven days; the code gave minutes. Now implemented as documented, and pinned by
  `GcWindowTest`.

**Open, unresolved**
- **The resynchronisation chunk limit is counted in operations, not bytes**
  (`SYNC_CHUNK = 300`). That assumes an operation weighs ~100 B — which stops being true when
  someone pastes a large fragment of text, because that is **one** operation of tens of
  kilobytes. The broadcast path moved to a byte budget in CP10; the resynchronisation path did
  not. A known debt, not an oversight.
- ~~**Mark resolution grows superlinearly with their number — measured.**~~
  **Done (CP20): a sweep instead of `O(m²)`.** At 6400 marks **62.1 ms → 4.72 ms**, and growth
  per fourfold increase in marks dropped from ×14.9 to ×3.2. The limit moved from hundreds to
  thousands. The remaining limit is somewhere other than I assumed: below ~1000 marks the
  dominant cost is **resolving anchors** through the block index, not picking a winner, so the
  fix buys nothing there. An interval tree recomputed incrementally would still help, but only
  after that cost.
- **The role is checked on open, not on every change.** The client receives it once, together
  with the initial state; raising permissions mid-session will not unlock the editor without a
  reload. Revocation takes effect immediately where it matters — the server rejects the operation
  anyway — so this is an inconvenience rather than a hole. The right solution is a role-change
  event on the user's channel.
- ~~**Authorization is per controller, therefore incomplete by construction.**~~
  **Done (CP16), and it turned out worse than this entry assumed — because a FOURTH hole of the
  same class showed up along the way.** `POST /api/documents/recovered` set the owner and
  `private` visibility only inside `if (caller != null)`, so an **expired token** produced a
  document with no owner and the default `link` visibility — editable by every logged-in user.
  The exact inverse of the intent. And it is not an edge case: a token lives 12 hours, while that
  endpoint exists **only** for clients offline longer than the 7-day GC window, so the path
  without a valid token is the typical one there.

  Every endpoint now declares `@RequiresDocumentAccess`, `@RequiresAuthentication` or
  `@PublicEndpoint`, and `DocumentAccessGuard` **refuses to serve an endpoint that declares
  nothing** — a 500 with the method name in the log. A new controller starts broken and loud
  instead of open and quiet. On top of that an architecture test fails the build, and the list of
  public endpoints is pinned by name, so silencing the guard requires an entry visible in code
  review ([ADR-0012](docs/adr/0012-autoryzacja-strukturalna.md)).

  What it does **not** give, and this is worth stating plainly: nothing checks whether the
  declared capability is the **right** one. The system now guarantees a decision was made, not
  that it was correct.
- **Authorization looks inside the frame body.** The `commenter` role requires telling a comment
  mark from a formatting mark, and that means parsing the payload in the interceptor. We do it
  **only** for that one role, but it is an authorization layer coupled to a data format — a
  change to the operation format could silently change permissions. In production: the operation
  type in a STOMP header, not in the body.
- **The performance numbers still come from a single process.** Clients, server, Postgres and
  Redis share ten cores, so the harness is itself a serious CPU consumer and network latency is
  zero. The numbers describe the cost of the system, not the experience of a user over the
  internet — for that they would have to be increased by the link's RTT and the rig split across
  separate machines.

---

## Observability

```bash
docker compose --profile observability up -d   # Prometheus, Tempo, Grafana on :3001
```

A single keystroke produces **one trace spanning two services**: the `edit.submit` span is
created in the browser, and `op.receive`, `crdt.merge` and `op.broadcast.local` on the server.
The context travels in a STOMP frame header, because the WebSocket handshake happens once per
session and an HTTP header would describe the whole session rather than one edit.

The write to the log is connected by **links**, not by nesting: it covers many operations at once
and happens on a different thread, so nesting would show the other traces somebody else's write
time.

The [dashboard](ops/grafana/dashboards/collabedit.json) is a file in the repository, not state
clicked together in a UI. Every panel has a description saying what a **bad** value means —
including the fact that a permanently zero count of numbering-gap repairs is not good news but a
signal that the repair mechanism has never run.

---

## Recording the demo

```bash
./tools/reset-demo-data.sh                          # clears documents left by tests
cd frontend && npm run demo                         # records both scenarios (Playwright)
cd .. && ./tools/make-gif.sh core    docs/demo.gif      # core: concurrency, offline, history
       ./tools/make-gif.sh product docs/demo-v2.gif     # product: formatting, comments, trash
```

The scenarios are **tests**, not recordings of manual clicking — if the demo stops working, the
run fails instead of producing a misleading recording. Two separate files, because one would be
either too long for anyone to watch to the end or too shallow to show anything.

In the product scenario **only one of the two browsers** is recorded. Not to save space: the
script that assembles the GIF picks a recording by file size, and that criterion depends on how
much happened in a given window — that is, on chance. One file in the directory removes the
guesswork, and the second person's view loses nothing, because everything Marek does is visible
at Anna's end.

---

## Repository layout

```
crdt-core/     RGA algorithm + property tests + JMH  (zero Spring)
app/           Spring Boot: WebSocket, persistence, history, cluster
frontend/      React + TypeScript + CodeMirror 6
conformance/   test vectors shared by the Java and TypeScript implementations
docs/adr/      architecture decisions
docs/design/   the visual mockup the interface is based on
ops/           Prometheus / Tempo / Grafana / OTel Collector configuration
tools/         helper scripts (demo recording, data cleanup)
```

---

## Project status

Every checkpoint of three plans is closed: nine from [plan v1](docs/IMPLEMENTATION_PLAN.md)
(CRDT core, transport, history, cluster, observability), six from
[plan v2](docs/PLAN-v2.md) (broadcast performance, multi-document, permissions, formatting,
comments, re-measurement), and five from [plan v3](docs/PLAN-v3.md).

**v3 added not a single feature**, and that is its thesis: everywhere the system relied on
somebody remembering, noticing, or getting lucky, a construction takes over that does not allow
otherwise. An endpoint with no declared authorization is not served; a cluster failure arrives
with the seed that reproduces it; a block that must not be removed gives back memory instead of
holding it; a CI gate watches the shape of the complexity the numbers in this README are about.

The README was written alongside the code, section by section — not reconstructed from memory at
the end. The same principle applies to the section [on
limits](#what-i-would-do-differently-in-production): items the later plans closed are **struck
through rather than deleted**, together with what was confirmed along the way and what turned out
to be false.

| Layer | Tests |
|---|---:|
| `crdt-core` — property, invariants, conformance, snapshots, marks, content-dropped blocks, complexity shape | 53 |
| `app` — integration against a real Postgres and Redis, including cluster chaos, permissions and the architecture test | 108 |
| `frontend` — agreement with the Java implementation, the bridge to CodeMirror | 51 |
| end-to-end — two real browsers | 17 |
| **total** | **229** |
| *separately:* `app` — **deterministic simulation** of the cluster | *806* |

**The simulation is listed separately and outside the total, because otherwise this table lies.**
806 is a count of **seeds**, not of distinct assertions: four scenarios multiplied by two hundred
event interleavings. Folding it into the total would turn an honest number into a marketing one —
all the more so because the nightly run raises it to 20 000 seeds per scenario, at which point
"number of tests" stops meaning anything. The value is that **a failure arrives with the seed
that reproduces it**, not in how many there are.

The load harness, the JMH benchmarks and the fan-out measurement are excluded from the normal
run — they take minutes and measure the rig's throughput as much as the system's, so they are not
correctness tests. They are run explicitly; the commands are in the [Tests](#tests) section.
