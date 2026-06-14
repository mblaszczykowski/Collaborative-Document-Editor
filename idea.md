Collaborative Document Editor — project brief

Concept
A real-time text editor (Google Docs light) letting many users edit the same document
simultaneously without conflicts or data loss — even over a poor connection, with concurrent
editing of the same fragment of text, or a momentary client disconnect.

The problem it solves
Naive text synchronisation (e.g. "last write wins") loses changes when two users edit the same
fragment at once. The project shows how to build a system in which changes from many clients always
merge without conflict while preserving each author's intent — the classic, hard problem of
distributed engineering (distributed consistency without central locking).

Architecture and technical approach
Synchronisation algorithm — to choose between / compare:
CRDT (Conflict-free Replicated Data Type) — e.g. a structure based on RGA/Peritext, where every
character has a unique positional identifier; merging is mathematically guaranteed without a
central arbiter
Operational Transform (OT) — transforming operations against each other on the server (the approach
historically used by Google Docs)
Recommendation: start with a CRDT (simpler to implement correctly yourself, fewer "magic" edge
cases than OT) and describe deliberately in the README why that choice was made, with a comparison
of both approaches' trade-offs.

Stack:
Backend: Java (Spring Boot) + WebSocket (STOMP) for propagating operations in real time
Persistence: PostgreSQL — document snapshots + an operation log (event sourcing for change history)
Frontend: React + TypeScript, a custom text editor (e.g. based on ContentEditable, or a library such
as Slate.js/ProseMirror as the UI layer, with our own CRDT logic underneath)
Reconnect handling: an offline operation buffer + resynchronisation once the connection returns

Key features
Multi-user real-time editing — other users' cursors and selections visible (as in Google Docs)
Change history / versioning — the ability to roll back to any point in time, with a "who changed
what" view (audit trail)
Offline and reconnect support — editing works locally with no network, and once the connection
returns the changes merge automatically
Presence — a list of active users in the document, "who is typing now" statuses
Conflict simulator — a demo mode showing deliberately artificial network delays and simultaneous
editing of the same fragment, to demonstrate the algorithm's correctness (this "sells" the project
very strongly in an interview)

What this project demonstrates (senior signal)
An understanding of distributed consistency theory (the CAP theorem in practice, eventual
consistency)
The ability to implement a non-trivial algorithm from scratch rather than merely using an
off-the-shelf library
Designing a real-time communication protocol (WebSocket) while thinking about edge cases: race
conditions, network partition, reconnect
Full-stack: complex domain logic on the backend + a demanding, stateful UI on the frontend

Risks / what to watch out for
CRDTs for text are harder than they look at first glance (the memory overhead of tombstones,
performance on long documents). It is worth limiting the scope to a simple text editor (no rich
formatting to begin with) and describing clearly in the README the deliberate simplifications and
"what I would do differently in production" — that is itself a senior-level signal.
