# Diagrams

## Architecture

```mermaid
flowchart TB
    subgraph browser["Browser"]
        CM["CodeMirror 6<br/><i>view layer only</i>"]
        CRDT_TS["crdt/<br/><b>RGA replica</b><br/>+ mark layer"]
        BUF["offline buffer<br/>IndexedDB"]
        SIM["network simulator"]
        CM <--> CRDT_TS
        CRDT_TS --> BUF
        CRDT_TS --> SIM
    end

    subgraph instance1["Backend instance #1"]
        AUTH1["frame authorization<br/><i>role → operation type</i>"]
        WS1["STOMP"]
        REP1["RGA replica<br/>+ marks"]
        WS1 --> AUTH1
        AUTH1 <--> REP1
    end

    subgraph instance2["Backend instance #2"]
        WS2["STOMP"]
        REP2["RGA replica<br/>+ marks"]
        WS2 <--> REP2
    end

    PG[("PostgreSQL<br/><b>operation log</b><br/>SOURCE OF TRUTH")]
    RD[("Redis pub/sub<br/><i>fast path<br/>UNRELIABLE</i>")]

    SIM <-->|"WebSocket / STOMP"| WS1
    REP1 -->|"write"| PG
    REP2 -->|"write"| PG
    REP1 <-->|"best-effort"| RD
    REP2 <-->|"best-effort"| RD
    PG -.->|"anti-entropy<br/>gap repair"| REP1
    PG -.->|"anti-entropy<br/>gap repair"| REP2

    style PG fill:#2E8B6B,color:#fff
    style RD fill:#B8862E,color:#fff
    style CRDT_TS fill:#4C5FD5,color:#fff
    style REP1 fill:#4C5FD5,color:#fff
    style REP2 fill:#4C5FD5,color:#fff
    style AUTH1 fill:#C1592E,color:#fff
```

All four RGA replicas on the diagram — two in browsers and two in backend instances — are **peers**.
There is no document owner and no ordering arbiter
([ADR-0004](adr/0004-skalowanie-poziome.md)).

Authorization is drawn on only one instance so as not to clutter the picture, but it works on every
one: **every SUBSCRIBE and SEND frame** goes through a role check
([ADR-0009](adr/0009-model-uprawnien.md)). It is not a check of "do you have access to the
document" but of "does your role permit THIS operation" — comments and edits travel the same
channel, so the distinction has to descend to the operation type.

---

## The path of a single edit

```mermaid
sequenceDiagram
    autonumber
    participant A as Anna<br/>(browser)
    participant S as Instance #1
    participant DB as Operation log
    participant R as Redis
    participant S2 as Instance #2
    participant M as Marek<br/>(browser)

    A->>A: insert into the local replica<br/>(text visible IMMEDIATELY)
    A->>S: operation + traceparent<br/>in the frame header
    S->>S: frame authorization<br/>(role → operation type)
    S->>S: merge into the replica
    S-->>M: broadcast (batched, 10 ms window)
    Note over S,M: the step before the write — this is<br/>what the user actually sees

    S->>DB: batched write (every 50 ms)
    DB-->>S: server_seq assigned
    S-->>A: ACKNOWLEDGEMENT (server_seq)
    Note over A: only now does the operation<br/>leave the offline buffer

    S->>R: publish (may be lost)
    R-->>S2: operation
    S2->>S2: merge
    S2-->>M: broadcast

    rect rgb(255, 245, 235)
        Note over S2,DB: when a message is lost:<br/>a gap in server_seq → pull from the log
        S2->>DB: the missing range
        DB-->>S2: operations
    end
```

The order of the steps is not arbitrary — each one is justified in
[ADR-0003](adr/0003-historia-zmian.md) and [ADR-0004](adr/0004-skalowanie-poziome.md).

---

## Client connection states

```mermaid
stateDiagram-v2
    [*] --> Connecting
    Connecting --> Resynchronising: connected
    Resynchronising --> Online: missing operations delivered

    Online --> Offline: connection lost<br/>or no heartbeat (25 s)
    Offline --> Connecting: retry<br/>(exponential backoff + jitter)

    Online --> Resynchronising: GAP DETECTED<br/>an acknowledgement for an<br/>operation we do not have

    Online --> RecoveredDraft: RESYNC_REQUIRED<br/>GC removed the anchor point
    RecoveredDraft --> [*]: local version saved<br/>as a separate document

    note right of Offline
        Editing continues.
        Operations go to IndexedDB
        and survive the tab closing.
    end note

    note right of Online
        Gap detection relies on the
        acknowledgement carrying the
        IDENTITY of an operation —
        a server_seq cursor would
        not be enough here.
    end note
```

---

## A comment — two routes, only one of which is a CRDT

The most common misunderstanding when reading this code is: "you have a CRDT, so the comments must
be in the CRDT too". They are not — and [ADR-0011](adr/0011-komentarze.md) explains why. The diagram
shows what that means for the flow.

```mermaid
sequenceDiagram
    autonumber
    participant M as Marek<br/>(browser)
    participant REST as REST<br/>/api/comments
    participant DB as PostgreSQL<br/>conversation tables
    participant WS as STOMP<br/>/app/doc/{id}/op
    participant A as Anna<br/>(browser)

    M->>REST: thread content + quote
    REST->>DB: INSERT thread + message
    DB-->>REST: thread identifier
    REST-->>M: thread identifier

    Note over M,WS: only NOW is the anchor created —<br/>it needs the thread identifier

    M->>WS: AddMark(type=comment,<br/>value=thread id)
    WS-->>A: operation broadcast
    A->>A: fragment highlighted<br/>IMMEDIATELY

    Note over A,REST: the conversation content arrives<br/>separately, when the panel refreshes
    A->>REST: the document's threads
    REST-->>A: conversations
```

Two things are visible here that are easy to miss in the code:

1. **The anchor is created AFTER the thread**, because it needs the thread's identifier. Between the
   two there is a window in which the thread exists without an anchor — and that is a known,
   documented consequence of this split, not an oversight.
2. **The highlight and the content arrive by different routes and at different speeds.** The
   highlight is a CRDT operation, so it appears for the other person immediately; the conversation
   travels over REST and waits for the panel to refresh. It looks like an inconsistency and is not:
   what has to merge without an arbiter is the range, not a sentence written by one person.
