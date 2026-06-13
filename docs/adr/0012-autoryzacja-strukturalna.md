# ADR-0012 — Authorization enforced by construction, not by vigilance

**Status:** Accepted (CP16)

## Context

Three times in this project a path to a document shipped with no authorization on it. All
three were found by a person reading code, never by anything automatic:

| When | What | How it was found |
|---|---|---|
| CP11 | IDOR — knowing a document id was enough to read it | code review during CP12 |
| CP14 | `HistoryController` had **no check at all** on three endpoints, one of which let a reader overwrite somebody else's document | end-of-plan audit |
| CP16 | `POST /api/documents/recovered` and `POST /api/documents` set owner and visibility only inside `if (caller != null)`, so a missing or **expired** token produced an ownerless, link-visible document | writing plan v3 against the code |

The second one is the one that settles the argument. Plan v2 §4.3 named that exact path,
in writing, as needing a second line of defence — and it shipped without one anyway. The
conclusion recorded in the v2 audit was:

> A safeguard that depends on somebody remembering a point from a document is not a safeguard.

Before CP16 authorization lived in roughly fifteen call sites across four files. Every one of
them was correct. Nothing required the next one to exist, and nothing reported its absence: a
new controller started **open and quiet**.

The third instance is worth reading closely, because it shows the failure is not carelessness.
`POST /api/documents/recovered` exists **only** for clients that were offline longer than the
7-day GC window. A token lives 12 hours. The population that endpoint was written for is the
population whose token has certainly expired — so the unauthenticated branch was not an edge
case, it was the *typical* case, and the `if (caller != null)` guarding it read as defensive
programming while doing the opposite.

## Options considered

**A. Keep per-controller checks, add more review discipline.**
This is the status quo plus hope. It has now failed three times, twice with the problem
written down in advance. Rejected.

**B. Spring Security.**
The framework answer. `TokenAuthFilter` already argues against it for this project — the whole
identity mechanism is a few dozen lines, and a filter chain plus its own vocabulary is a large
cost for three access rules. That argument still holds for *authentication*. For
*authorization* the cost would be worth paying if the rules were numerous; they are not, and
the failure mode here is not "the rules are hard" but "the rule was absent". Spring Security
would not have caught an endpoint nobody thought to configure, which is precisely what
happened. Rejected — with the same caveat as before: in production, with dozens of rules, this
inverts.

**C. One path rule over `/api/documents/{id}/**`.**
What the README proposed. It would have covered all three historical holes, since all three
sat under that prefix. Two things it does not do:
- `/api/comments/{threadId}/…` reaches the same document through a different noun and would
  stay uncovered;
- it cannot express that restore needs **write** while preview needs **read** — and confusing
  those two is exactly how the restore endpoint stayed open to readers.

**D. Declaration at the point of use, refused when absent.** ← chosen

Every request-mapped method declares one of three things: `@RequiresDocumentAccess(capability)`,
`@RequiresAuthentication(reason)`, or `@PublicEndpoint(reason)`. `DocumentAccessGuard`
resolves the role once and **refuses to serve any endpoint that declares nothing**.

## Decision

Two layers, because neither is sufficient alone:

**Runtime — fail closed.** An undeclared endpoint responds 500 and logs loudly. A new
controller therefore starts *broken and loud* instead of open and quiet. That inversion is the
entire decision; everything else is detail.

500 rather than 404 is deliberate: this is a mistake in our code, not the caller doing
something wrong, and it should read that way in logs and in tests.

**Build time — refuse to compile the mistake.** `EndpointAuthorizationArchitectureTest` walks
every request-mapped method and fails if any lacks a declaration, so the feedback arrives
before the deploy rather than after it. The set of endpoints carrying `@PublicEndpoint` is
additionally **pinned by name**, so silencing the guard requires editing a list — a diff line
that reads "this endpoint needs no authorization", which is a sentence that deserves a second
reader.

Both layers were verified by mutation, per house convention: removing the annotation from
`HistoryController#restore` fails the architecture test naming that exact method; swapping it
for `@PublicEndpoint` fails the pin; making the guard's fail-closed branch permissive fails
three of its unit tests.

**Three annotations, not two.** `@RequiresAuthentication` exists because CP16 turned up
endpoints that fit neither of the other two: they *create* a document, so there is nothing to
authorize against, but they must not run for an anonymous caller. Collapsing that into
"public" would have kept both holes open while looking deliberate.

**WebSocket is unchanged.** `DocumentAccessInterceptor` already enforces one rule over all
frames by parsing the destination, which is the same shape of solution one layer down. The
architecture test now asserts that every registered `@MessageMapping` destination is one that
interceptor's parser can actually resolve a document from — so the two cannot drift apart
silently.

## Consequences

**Positive**

- Forgetting to authorize is no longer a silent state. It is a failing build, and failing that,
  a 500 with a log line naming the method.
- The requirement is visible where the endpoint is written, rather than in an `if` further down
  or a config file elsewhere.
- `Capability` separates what an endpoint *demands* from what a caller *is*, so inserting a new
  role into the ordering cannot silently change what a dozen endpoints require.
- The role is resolved once per request and handed to the controller. Asking twice could
  straddle the 5-second permission cache expiry and let a controller act on a different role
  than the one it was admitted with — a race that existed before and was never observed.

**Negative — and these are real**

- **A reader of the controller no longer sees the check.** The `if` is gone; what protects the
  method is an annotation whose enforcement lives in another class. This is a genuine loss of
  local readability, traded for a gain in global completeness.
- **The `commenter` role still requires reading the frame body.** The guard covers REST; the
  WebSocket path must still parse the payload to tell a comment mark from a formatting mark.
  That remains an authorization layer coupled to a data format, and CP16 does not fix it. Still
  in the README as a known limit.
- **The pinned public-endpoint list is a second place to update.** Deliberate, but it is
  friction, and friction gets routed around. If it is ever edited without comment in review,
  the mechanism has failed.
- **Nothing enforces that the declared capability is the *right* one.** A developer can write
  `@RequiresDocumentAccess(READ)` on an endpoint that writes, and both layers will pass. The
  system now guarantees a decision was made, not that it was correct. That is a smaller
  guarantee than it may look — and it is the honest description of what this ADR buys.

**To be verified in CP17–CP20:** whether the annotation survives contact with endpoints that do
not fit its three shapes. The first one that needs a fourth category is the signal that this
design is too narrow, and it should be recorded here rather than worked around with a
`@PublicEndpoint` and an apologetic reason string.
