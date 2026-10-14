# Collaborative Document Editor Review

The application has a substantial foundation: separate CRDT and application modules, shared Java and TypeScript conformance vectors, property tests, transactional operation logging, snapshots, access declarations, and cluster simulations. The next investment should be correctness and release reliability before expanding the feature set. The review found reproducible confidentiality, synchronization, garbage collection, and undo defects alongside deployment blockers and usability gaps.

This review covers the Java and TypeScript CRDTs, editor integration, browser transport and storage, backend persistence and clustering, authentication and authorization, comments, suggestions, history, document discovery, UI, migrations, CI, Docker, observability, and relevant tests. Three specialist reviewers examined security, synchronization, and frontend behavior, followed by a reviewer for delivery and test coverage. The findings below describe the original baseline. Authorized repairs and subsequent independent regression reviews are recorded in [review-fix-status.md](review-fix-status.md); consult that record for current behavior and outstanding enhancements.

## Evidence and priorities

**Reproduced** means the behavior was observed in the running local app, a local API probe, or an isolated probe executing production classes. The individual finding identifies the level of reproduction. **Source confirmed** means the implementation establishes the defect or unsafe interleaving, but the full failure was not injected end to end. **Improvement** identifies a proposed product or engineering enhancement.

- **P1:** address before relying on the application for confidential or important collaborative work, or before shipping a working release.
- **P2:** address in the next reliability and usability iteration.
- **P3:** useful refinements and longer-term capabilities.

Baseline validation results, before repairs:

| Check | Result |
| --- | --- |
| Maven test suite | Passed after supplying Colima's container socket override. CRDT reports contain 91 tests; application reports contain 1,468 tests, including two skipped tests. No reported failures or errors in the successful run. |
| Frontend unit tests | 151 tests passed, but Vitest reported an unhandled rejection and returned failure. |
| Frontend type checking | Failed in application and test code. |
| Frontend production build | Failed during TypeScript compilation. |
| Frontend lint | Completed with an empty destructuring warning in the E2E fixture. |
| npm dependency audit | Reported three affected packages, one high and two moderate, representing two advisories. Fixes are available. These are development/build dependencies; production exploitation was not established. |
| Local browser smoke review | Exercised development sign-in, document editing, saving, reload, rename, selection, comments, and sharing error presentation against a separate review database. |
| Targeted probes | Reproduced private comment disclosure, invalid suggestion failure, cluster cursor skipping, insert resurrection after GC, deletion metadata divergence, expired WebSocket acceptance, permission cache race, several undo errors, caret mapping, and transport retry gaps. |

At baseline the complete Playwright suite, real federated provider login, a production deployment, sustained capacity testing, every accessibility interaction, and real-device mobile/IME behavior had not been independently executed. Subsequent complete browser and production offline-shell results are recorded in the repair status. Component probes establish their stated behavior; they do not substitute for end-to-end regressions. The review does not prove that every possible execution path is correct.

The existing local database could not start with the repository's migrations because Flyway reported checksum mismatches. A separate database started successfully. That establishes local schema-history drift; it does not establish when or why the original migrations changed. Existing data was left untouched.

## Security and access control

### S01 Private comment disclosure through global retry keys

**P1 · Reproduced through HTTP.** Comment deduplication looks up `clientId` globally and returns the existing thread without validating its document or author. User B, unable to read user A's private document, can submit A's known retry key to B's own document and receive A's thread, including its message body. This requires knowing or colliding with the key; it is an authorization boundary failure even though normal browser keys are random.

Source: [CommentService.java](../app/src/main/java/dev/collabedit/app/comments/CommentService.java#L38), [CommentController.java](../app/src/main/java/dev/collabedit/app/comments/CommentController.java#L83), [V8 comment idempotency migration](../app/src/main/resources/db/migration/V8__comment_idempotency.sql).

**Fix:** scope thread keys by document and author, and reply keys by thread and author. Reject reuse with different content or scope. Verify access to the returned resource, including the conflict path. Add a two-user, two-private-document regression that proves neither thread disclosure nor silent cross-thread reply suppression is possible.

### S02 Connected WebSocket sessions outlive their tokens

**P1 · Reproduced with production interceptors.** A synthetic short-lived token passed `CONNECT`, expired, and the same session's subsequent `SEND` still passed authentication and document access interception. Authentication occurs only at connection establishment; session attributes carry no expiry.

Source: [StompConnectInterceptor.java](../app/src/main/java/dev/collabedit/app/ws/StompConnectInterceptor.java#L56), [SessionAttributes.java](../app/src/main/java/dev/collabedit/app/ws/SessionAttributes.java), [TokenService.java](../app/src/main/java/dev/collabedit/app/auth/TokenService.java).

**Fix:** persist verified expiry with session identity and enforce it on inbound and outbound traffic, or terminate the connection at expiry. Return an explicit session-expired response so the client can preserve pending work and request sign-in. Test editing and receiving content after expiry without a socket reconnect. Correct the README claim that this behavior has already been fixed.

### S03 Broadcast authorization fails open during lookup errors

**P1 · Source confirmed.** `RevokedAccessFilter` catches permission lookup failures and explicitly delivers the frame. If access cannot be established after cache expiry or during an outage, confidential content may continue flowing to a session whose membership was revoked.

Source: [RevokedAccessFilter.java](../app/src/main/java/dev/collabedit/app/ws/RevokedAccessFilter.java#L65).

**Fix:** pause or drop content delivery when authorization is unknown, expose the degraded state, and reconnect or retry after access can be established. Preserve the access-change notification needed to explain revocation. Fault-inject database failures before and after revocation.

### S04 An old permission lookup can refill the cache after revocation

**P1 · Reproduced with a deterministic concurrency probe.** A role lookup starts before revocation; revocation invalidates the cache; the old lookup then stores `EDITOR` again. Subsequent access remains allowed until the five-second TTL expires.

Source: [PermissionService.java](../app/src/main/java/dev/collabedit/app/auth/PermissionService.java#L63), [invalidation](../app/src/main/java/dev/collabedit/app/auth/PermissionService.java#L145).

**Fix:** associate permission generations with cache entries and reject a fill if invalidation occurred after its lookup began. Test the exact interleaving locally and across instances. Keep the residual TTL fallback separate from the guarantee of immediate revocation.

### S05 Invite redemption is not atomic with revocation or role changes

**P2 · Source confirmed.** Invite validity, current role, and membership grant are separate operations. Revocation between validation and grant can still allow entry; a concurrent redemption can overwrite a newer administrative role decision.

Source: [InviteService.java](../app/src/main/java/dev/collabedit/app/auth/InviteService.java#L74).

**Fix:** use a transaction with appropriate locking or a conditional database mutation that establishes invite validity and intended role change together. Test revoke-versus-redeem and two simultaneous role grants. Make expired, revoked, and invalid invitation errors visible in the UI instead of silently dropping the invite parameter.

### S06 Offline edits comments and undo are shared across accounts

**P1 · Source confirmed.** IndexedDB keys and queries are scoped by document, not user. Signing out leaves this state behind. A different account opening the same document can load the previous account's undo stack, resend its edits, or post its queued comments under the new identity.

Source: [offlineStore.ts](../frontend/src/transport/offlineStore.ts#L6), [restoreOfflineBuffer](../frontend/src/transport/connection.ts#L84), [loadUndo](../frontend/src/editor/useCollaborativeDocument.ts#L172), [session.ts](../frontend/src/ui/session.ts).

**Fix:** namespace durable local state by user and document, preserve ownership during migrations, and prevent one account from draining another's queue. Define sign-out behavior for unsent work without deleting it silently. Add account-switch, cross-tab session-change, and shared-browser regressions.

### S07 Replica ownership validation is only local and temporary

**P2 · Source confirmed.** Incoming operation IDs are rejected only when that replica ID belongs to another currently connected local session. Unassigned IDs, disconnected IDs, negative server IDs, and IDs active on other instances are not durably tied to an owner.

Source: [DocumentStompController.java](../app/src/main/java/dev/collabedit/app/ws/DocumentStompController.java#L67), [SessionLifecycle.java](../app/src/main/java/dev/collabedit/app/ws/SessionLifecycle.java#L45).

**Fix:** define durable ownership of client replica IDs, retain the ability to replay a user's previous offline operations, and reserve server IDs. Test two instances, reconnects, forged IDs, and conflicting operation payloads. Treat this as integrity protection rather than restricting all replay to the current socket's newly assigned ID.

### S08 Separate local and deployed security configurations

**P1 when exposed beyond trusted local development · Improvement.** Compose supplies a known fallback signing secret, publishes application ports on all interfaces, and exposes anonymous Grafana Admin in the observability profile. These are reasonable demo conveniences only within a clearly isolated local profile.

Source: [docker-compose.yml](../docker-compose.yml#L60), [Grafana configuration](../docker-compose.yml#L132), [SecurityConfig.java](../app/src/main/java/dev/collabedit/app/auth/SecurityConfig.java).

**Fix:** require an explicit secret in deployment, bind local tooling to loopback, secure metrics and dashboards, and provide deployment-specific TLS, origins, forwarded headers, and secret handling. Add a configuration check that rejects development login and the known secret in a deployment profile. Decide whether logout must revoke existing bearer tokens; currently client sign-out alone does not provide server-side revocation.

## Synchronization persistence and recovery

### D01 A local flush can skip a missing cluster operation permanently

**P1 · Reproduced at the replica level.** Node A commits sequence 1, node B misses the Redis publication, and B independently commits sequence 2. `flushCompleted` moves B's applied cursor to 2 even though sequence 1 is absent. Anti-entropy sees head 2 and does nothing. The probe captured a snapshot labeled sequence 2 containing only B's text. Loading that snapshot skips the missing operation.

Source: [DocumentReplica.java](../app/src/main/java/dev/collabedit/app/domain/DocumentReplica.java#L285), [antiEntropySweep](../app/src/main/java/dev/collabedit/app/domain/DocumentService.java#L435), [DocumentRegistry.java](../app/src/main/java/dev/collabedit/app/domain/DocumentRegistry.java#L101).

**Fix:** track the contiguous applied log prefix separately from the highest observed or locally committed sequence. Repair missing earlier operations before advancing the replay boundary or writing a snapshot. Add a cluster regression combining missed Redis delivery, a simultaneous local write, snapshot creation, eviction, and restart.

### D02 Retrying an insert after garbage collection resurrects deleted text

**P1 · Reproduced with the Java CRDT.** Insert `a`, delete it, collect its block, then replay its original insert: the result is `APPLIED` and text becomes `a`. Insert deduplication relies on the remaining block structure. A root insert has no origin, so stale-origin validation does not reject it. Database deduplication occurs after the live replica has already changed; a later snapshot can preserve the resurrection.

Source: [RgaDocument.java](../crdt-core/src/main/java/dev/collabedit/crdt/RgaDocument.java#L173), [stale-origin check](../app/src/main/java/dev/collabedit/app/domain/DocumentReplica.java#L335), [OperationStore.java](../app/src/main/java/dev/collabedit/app/persist/OperationStore.java#L27).

**Fix:** preserve operation identity across collection or validate accepted replay against durable identity before mutating the replica. Do not simply classify everything below the version-vector maximum as duplicate: Lamport jumps and out-of-order delivery make that unsafe. Test collected root inserts and inserts whose origins survive, across Java replay and browser reconnect.

### D03 A unique concurrent delete can be discarded without acknowledgment

**P1 · Reproduced at the CRDT level; persistence consequence follows from source.** When two replicas delete the same characters, the second delete is classified as duplicate because the targets are already deleted, despite having a different operation ID. It is not enqueued, stored, or acknowledged. The browser can remain in `Saving…` indefinitely. Replicas also retain different version and deletion metadata after exchanging the deletes.

Source: [Java duplicate detection](../crdt-core/src/main/java/dev/collabedit/crdt/RgaDocument.java#L176), [DocumentReplica.merge](../app/src/main/java/dev/collabedit/app/domain/DocumentReplica.java#L108), [TypeScript duplicate detection](../frontend/src/crdt/document.ts#L279).

**Fix:** distinguish a repeated operation from a new operation with no visible effect. Observe the causal metadata, durably record the operation when required, and acknowledge it. Test both delivery orders, browser pending-count clearance, GC metadata, and reconnect.

### D04 Snapshot content and its replay boundary are not captured consistently

**P1 · Source confirmed.** The HTTP snapshot endpoint reads the database head independently from the live replica snapshot. A lagging instance can return old content with a newer replay boundary, causing the browser to skip missing log operations. The background snapshot path also checks pending operations before acquiring the capture lock; a newly buffered remote operation can be omitted from the serialized state.

Source: [DocumentSnapshotController.java](../app/src/main/java/dev/collabedit/app/api/DocumentSnapshotController.java#L32), [DocumentService snapshot checks](../app/src/main/java/dev/collabedit/app/domain/DocumentService.java#L238), [withConsistentState](../app/src/main/java/dev/collabedit/app/domain/DocumentReplica.java#L297).

**Fix:** atomically capture content, contiguous sequence, and pending-state eligibility from a replica repaired to that boundary. Define whether the API snapshot includes uncommitted operations; do not infer durable acknowledgment merely from presence in such a snapshot. Test delayed gossip, concurrent flush, pending dependencies, and process termination between snapshot delivery and persistence.

### D05 Server generated changes return success before durable persistence

**P1 · Source confirmed.** Suggestions, version restores, and recovered-document content call `generateAndSubmit`, which mutates the replica and enqueues a later write. HTTP success can arrive before the operation log commits. Unlike browser edits, these operations have no browser outbox that reliably retransmits them after a server crash. Suggestion rows can be created or marked resolved while their CRDT changes are lost.

Source: [generateAndSubmit](../app/src/main/java/dev/collabedit/app/domain/DocumentService.java#L93), [SuggestionService.java](../app/src/main/java/dev/collabedit/app/suggestions/SuggestionService.java#L122), [HistoryController.java](../app/src/main/java/dev/collabedit/app/history/HistoryController.java#L89), [createRecovered](../app/src/main/java/dev/collabedit/app/api/DocumentRestController.java#L130).

**Fix:** commit these commands durably before success, or persist an idempotent command/outbox that can finish after restart. Coordinate suggestion state with the command lifecycle. Test termination after HTTP success and before the scheduled flush.

### D06 Recovery can clear edits made after the recovered text was captured

**P1 · Source confirmed.** Recovery sends the current plain text to create a recovered document, awaits HTTP, then clears the entire current buffer. The editor remains writable during that wait. New edits can therefore be absent from the recovered text and removed from the outbox. Multiple rejected operations can start separate recovery requests; errors are unhandled.

Source: [recoverAfterRejection](../frontend/src/editor/useCollaborativeDocument.ts#L125), [RESYNC_REQUIRED handler](../frontend/src/editor/useCollaborativeDocument.ts#L215), [abandonBuffer](../frontend/src/transport/connection.ts#L386).

**Fix:** use one explicit recovery state, capture an operation boundary, freeze editing or retain later operations, and clear only the recovered set after durable success. Show retry/export options on failure. Preserve formatting or explicitly state what is recovered; the current plain-text recovery cannot support an unconditional “Nothing was lost” claim. Test typing during delayed recovery, rejected batches, and failed recovery requests.

### D07 A full dependency buffer refuses the operation needed to unblock it

**P2 · Source confirmed.** The server checks the pending cap before trying to apply an operation. Once full, it refuses even a missing cause that would drain the buffer. Client synchronization does not clear this server state, and pending operations prevent normal snapshotting.

Source: [DocumentService.submit](../app/src/main/java/dev/collabedit/app/domain/DocumentService.java#L134), [refuseIfPendingBufferFull](../app/src/main/java/dev/collabedit/app/domain/DocumentService.java#L149).

**Fix:** enforce the cap only when an unresolved new operation would increase it. Permit duplicates and dependency-resolving operations. Test a full buffer followed by its missing origin.

### D08 Pending edits have no normal online reconciliation deadline

**P2 · Reproduced for the network simulator; acknowledgment failure is source confirmed.** A dropped simulated edit remains pending after ten progress ticks, with zero operation retries. Reducing loss to zero does not recover it while the socket remains connected. Progress reporting also does not reconcile an ordinary lost acknowledgment. Duplicate server submissions do not necessarily produce a new acknowledgment.

Source: [reportProgress](../frontend/src/transport/connection.ts#L172), [send](../frontend/src/transport/connection.ts#L345), [NetworkSimulator](../frontend/src/transport/simulator.ts#L55), [flush acknowledgment](../app/src/main/java/dev/collabedit/app/domain/DocumentService.java#L303).

**Fix:** reconcile aged pending operations, provide idempotent acknowledgment lookup, and use bounded retry/backoff. Keep deliberate simulated loss recoverable. Test dropped submission and commit-success/ack-failure without disconnecting the socket.

### D09 Durable unsent edits are not restored into the visible local model

**P2 · Reproduced with the real connection class and a transport harness.** `restoreOfflineBuffer` returns pending operations but does not apply them; the caller discards the returned list. Restored edits become visible only after server acceptance and broadcast. Opening a document requires a successful REST call before reading the outbox, so a tab reopened offline cannot restore a cached editing session.

Source: [restoreOfflineBuffer](../frontend/src/transport/connection.ts#L84), [caller](../frontend/src/editor/useCollaborativeDocument.ts#L249), [initial REST load](../frontend/src/editor/useCollaborativeDocument.ts#L142).

**Fix:** apply owned persisted operations into a compatible local model before allowing editing, and persist a checkpoint if offline reopening is part of the product promise. Distinguish already durable state from uncommitted fast-path state before discarding entries. Test close/reopen offline, API outage with a working browser network, and replay after restart.

### D10 IndexedDB writes resolve before the transaction commits

**P2 · Source confirmed.** Most storage helpers resolve on the request's `onsuccess`, so a later transaction abort cannot change the already resolved promise. A comment can appear queued without having committed. Only the buffer-clearing helper waits for transaction completion.

Source: [transact](../frontend/src/transport/offlineStore.ts#L76), [transactUntilComplete](../frontend/src/transport/offlineStore.ts#L96).

**Fix:** resolve writes on transaction completion, capture request results separately, and propagate abort/error consistently. Test request success followed by transaction abort using realistic IndexedDB behavior, including quota exhaustion.

### D11 A server crash shortens offline anchor protection

**P2 · Source confirmed.** Gracefully departed replicas constrain GC for seven days. Replicas whose departure was not recorded, including after server termination, are excluded after the 60-second unresponsive timeout. Equivalent offline editing sessions therefore have different retention guarantees depending on how the server stopped.

Source: [ReplicaProgressStore.liveFor](../app/src/main/java/dev/collabedit/app/persist/ReplicaProgressStore.java#L46), [SessionLifecycle departure](../app/src/main/java/dev/collabedit/app/ws/SessionLifecycle.java#L98), [application configuration](../app/src/main/resources/application.yml#L58).

**Fix:** make offline protection crash-safe and explicit. Revisit reconnect progress when a socket receives a new replica ID but the model retains the old ID. Test server termination with unsubmitted anchored edits and concurrent deletion elsewhere.

### D12 Replica identifiers are not guaranteed unique through restart and failover

**P2 · Source confirmed.** Client IDs depend on a Redis counter whose configured persistence is disabled, with randomly seeded local fallbacks; server IDs are selected from about one million possibilities. Redis reset, simultaneous fallback allocation, or backend collisions can create identities that existing documents already use.

Source: [ReplicaIdAllocator.java](../app/src/main/java/dev/collabedit/app/auth/ReplicaIdAllocator.java), [DocumentRegistry.java](../app/src/main/java/dev/collabedit/app/domain/DocumentRegistry.java#L25), [Redis configuration](../docker-compose.yml#L33).

**Fix:** allocate durable globally unique identities with an explicit client/server namespace and restart policy. Preserve cross-language wire compatibility. Test counter reset, Redis outage across instances, and backend restarts against existing documents.

## Editor and undo correctness

### E01 Two unrelated deletions can undo into the wrong location

**P1 · Reproduced with the TypeScript implementation.** Starting with `abcdef`, delete `b`, then delete `e` within one second. The document becomes `acdf`; undo produces `acdebf`, rather than restoring one deletion or the original text. The coalescing logic merges all nearby-in-time deletions without proving positional adjacency.

Source: [UndoManager.recordDelete](../frontend/src/crdt/undo.ts#L134).

**Fix:** coalesce only compatible adjacent deletion runs and end a group when the selection moves. Test backspace, forward delete, clicking elsewhere, and multi-cursor changes.

### E02 Redo of a large paste creates an oversized frame

**P1 · Reproduced with the TypeScript implementation.** A 100,000-character paste generates 13 inserts of at most 8,192 characters. Undo and redo generate one 100,000-character insertion with a roughly 100,083-byte JSON operation, exceeding the configured 65,536-byte WebSocket message limit.

Source: [paste chunking](../frontend/src/editor/bridge.ts#L58), [undo coalescing](../frontend/src/crdt/undo.ts#L113), [inverse insertion](../frontend/src/crdt/undo.ts#L262), [WebSocket limits](../app/src/main/resources/application.yml#L31).

**Fix:** chunk every inverse insertion with a serialized byte budget, preserving surrogate pairs. Test large ASCII, emoji, and non-Latin paste → undo → redo across browsers and server persistence.

### E03 Undo and redo move local text across remote insertions

**P1 · Reproduced with the TypeScript implementation.** Local `ab` plus a remote `X` inserted between them gives `aXb`. Undo and redo of the local run produce `abX`. The inverse collapses surviving local characters into one anchored insertion, losing their positions around the collaborator's text.

Source: [UndoManager.revertInsert](../frontend/src/crdt/undo.ts#L227).

**Fix:** retain separate anchored runs and recreate each run in its intended position. Test remote inserts between local characters, remote deletes, and repeated undo/redo cycles.

### E04 Replacement is not one undo transaction

**P2 · Reproduced with the TypeScript implementation.** Replace `abc` with `xyz`. One undo removes `xyz` and leaves an empty document; a second undo restores `abc`. A single editor replacement records separate deletion and insertion entries.

Source: [changesToOps](../frontend/src/editor/bridge.ts#L44).

**Fix:** group the complete CodeMirror transaction, including all selections and replacement operations, into one undo transaction. Define coalescing separately from transaction boundaries.

### E05 Undoing full deletion loses formatting

**P2 · Reproduced with the TypeScript implementation.** Bold `abc`, delete all of it, then undo: text returns as plain `abc`. Deletion history preserves text and anchor but not marks. Partial deletions can appear to work because some old anchors survive, so whole-range deletion needs its own regression.

Source: [recordDelete](../frontend/src/crdt/undo.ts#L134), [revertDelete](../frontend/src/crdt/undo.ts#L250).

**Fix:** record applicable formatting runs and restore them alongside recreated text, with a deliberate policy for comments and suggestions.

### E06 Undoing a formatting extension erases preexisting formatting

**P2 · Reproduced with the TypeScript implementation and the hook's recorded state.** If part of a selection is already bold, extending bold to the larger selection records the prior value as absent. Undo removes bold from the whole selection, including the original bold range. A single scalar prior value also cannot represent mixed links or heading values.

Source: [toggleMark](../frontend/src/editor/useCollaborativeDocument.ts#L298), [recordMark](../frontend/src/crdt/undo.ts#L199).

**Fix:** record the prior mark runs across the full affected range and restore them individually. Test partially marked and mixed-value selections, including another author's formatting.

### E07 Batched remote edits can jump the local caret

**P2 · Reproduced using production change generation and CodeMirror mapping.** Transforming `abcdef` into `aXbcdeYf` creates one replacement of the preserved middle. Mapping a caret at position 3 yields position 1 instead of position 4. The same broad replacement can collapse a selection that still refers to surviving text.

Source: [minimalChange](../frontend/src/editor/bridge.ts#L74), [remote dispatch](../frontend/src/editor/Editor.tsx#L125).

**Fix:** retain selection as CRDT anchors through remote application or generate multiple disjoint changes. Test distant batched edits, reconnect catchup, and an active selection during remote changes. Include composition behavior in browser testing.

### E08 Heading switching and multi-paragraph formatting behave incorrectly

**P2 · Source confirmed.** Switching H1 to H2 removes the heading because the toggle checks only the presence of the mark type. Block formatting finds only the paragraph at the selection start, so a multi-paragraph selection does not become a complete list or quote. Empty paragraphs cannot be formatted through the current range model.

Source: [toggleBlock](../frontend/src/editor/useCollaborativeDocument.ts#L326).

**Fix:** compare current and requested values, apply the chosen block style to all selected paragraphs, and define behavior for empty/new paragraphs and Enter continuation.

## Comments suggestions and history

### C01 Lost HTTP responses can duplicate comments and replies

**P2 · Source confirmed.** The immediate request and its queued retry call `clientId()` separately. A server commit with a lost response is retried under a new identity, defeating server idempotency. The initial catch also queues permanent HTTP failures as though they were temporary network failures.

Source: [CommentsPanel.open](../frontend/src/ui/CommentsPanel.tsx#L58), [reply](../frontend/src/ui/CommentsPanel.tsx#L90).

**Fix:** generate one key per command and keep it through all retries. Classify errors before queueing, preserve refused content for copying, and show validation errors. Test commit-success/response-loss and oversized or unauthorized comments.

### C02 An immediately posted comment uses the latest selection

**P2 · Source confirmed.** Comment creation captures the selection anchor, but its success path ignores that capture and calls `onAnchor` using the current selection. Selecting a different range while the HTTP request is pending can attach the comment to the wrong text.

Source: [CommentsPanel.tsx](../frontend/src/ui/CommentsPanel.tsx#L59), [anchorComment](../frontend/src/editor/useCollaborativeDocument.ts#L353).

**Fix:** use the captured CRDT anchors for both immediate and queued posting. Test selection changes and remote edits during a delayed response.

### C03 Comment retry belongs to the panel lifecycle

**P2 · Source confirmed.** The outbox drains only on Comments panel mount and the browser's `online` event. A backend-only outage can leave comments queued while the browser remains online. Closing the panel stops its drain. There is no coordinated single drain across browser tabs.

Source: [CommentsPanel retry effect](../frontend/src/ui/CommentsPanel.tsx#L169).

**Fix:** move draining to an account/document service, trigger it on backend recovery, and retry with backoff. Coordinate concurrent drainers. Persist draft/outbox status and make queued messages inspectable.

### C04 Queued anchors are forgotten before the model is ready

**P2 · Source confirmed.** The drain handles `gone` but treats `not-ready` as success, then removes the queued command. A thread can be posted while its anchoring work is silently abandoned.

Source: [CommentsPanel.flush](../frontend/src/ui/CommentsPanel.tsx#L130), [anchorCommentAt](../frontend/src/editor/useCollaborativeDocument.ts#L368).

**Fix:** track posting and anchoring as separate durable stages. Retain the anchor until it succeeds or is explicitly declared detached. Test a slow initial model load and role resolution during outbox replay.

### C05 Panels do not react to many collaborative changes

**P2 · Source confirmed.** History and Comments refresh from character count; Suggestions refresh from character count plus the number of formatting spans. Formatting-only edits, equal-length replacements, replies, resolve/reopen, and some suggestion decisions do not change those values. Other participants' panels can remain stale until another triggering edit or remount.

Source: [panel revisions in App.tsx](../frontend/src/App.tsx#L616), [usePanelData](../frontend/src/ui/usePanelData.ts#L35).

**Fix:** use an actual operation revision and explicit metadata events, with a reconnect refresh for non-CRDT records. Invalidate after persistence acknowledgment as appropriate. Test two users commenting/resolving without changing document length.

### C06 Invalid suggestion anchors cause a server error and leave orphan proposals

**P2 · Reproduced through HTTP.** An insertion proposal referencing a missing character returns HTTP 500, caused by null unboxing in the conditional expression. The suggestion row was already inserted and remains open but detached.

Source: [SuggestionService.proposeInsertion](../app/src/main/java/dev/collabedit/app/suggestions/SuggestionService.java#L54).

**Fix:** avoid primitive unboxing of a nullable lookup, validate the anchor before committing the proposal, and roll back or durably coordinate row creation and generated operations. Test missing/deleted anchors and deletion between validation and submission.

### C07 History restoration can restore proposal marks without proposal state

**P2 · Source confirmed.** History restoration excludes comment marks but restores suggestion marks. Suggestion acceptance/rejection state lives separately in database rows. Restoring a version containing an already resolved suggestion can reintroduce proposal styling while the Suggestions panel omits the resolved row.

Source: [HistoryService.restoreMarks](../app/src/main/java/dev/collabedit/app/history/HistoryService.java#L241), [isConversation](../app/src/main/java/dev/collabedit/app/history/HistoryService.java#L305), [SuggestionService.openFor](../app/src/main/java/dev/collabedit/app/suggestions/SuggestionService.java#L182).

**Fix:** define whether restoration preserves, reopens, or excludes suggestions, and restore the CRDT marks and proposal records consistently. Test restoring before and after accepting/rejecting each suggestion type. Attribute restores and suggestion-generated operations to the acting user instead of submitting them with `null` author.

### C08 Enter submission ignores busy and composition state

**P2 · Source confirmed.** The suggestion input can submit repeatedly during an in-flight request; it has no explicit submit button and does not distinguish Enter used to commit IME input. Reply submission needs the same composition guard.

Source: [SuggestionsPanel.tsx](../frontend/src/ui/SuggestionsPanel.tsx#L70), [CommentsPanel reply](../frontend/src/ui/CommentsPanel.tsx#L285).

**Fix:** disable or serialize in-flight submission, provide a visible submit action, and ignore submission during composition. Test rapid Enter, Chinese/Japanese composition, and keyboard-only use.

### C09 Protocol-error role refresh discards its result

**P2 · Source confirmed.** On a STOMP error the hook calls `myRole` but applies neither the returned role nor a revoked-access result. A missed access notification can leave editable controls enabled while operations are refused. Some denial paths silently drop frames, leaving pending operations without actionable explanations.

Source: [error handler](../frontend/src/editor/useCollaborativeDocument.ts#L226), [DocumentAccessInterceptor.deny](../app/src/main/java/dev/collabedit/app/ws/DocumentAccessInterceptor.java#L205).

**Fix:** unify role refresh and unavailable handling, return structured refusal messages, and use explicit blocked/recovering states instead of indefinite saving or reconnection. Preserve local edits while explaining what requires user action.

## User interface and product improvements

### U01 Pointer users cannot reveal document action buttons

**P2 · Source confirmed.** Hover selectors such as `.sb-row:hover.sb-delete` match an element carrying both classes rather than a delete button inside a hovered row. Rename/delete/restore actions remain transparent on pointer hover; keyboard focus still exposes them.

Source: [styles.css](../frontend/src/styles.css#L204).

**Fix:** correct the descendant selectors and keep actions discoverable on touch. Include pointer, keyboard, and touch regressions. Prefer a clearly labeled row action menu if the controls remain compact.

### U02 Renaming leaves the open document title stale

**P2 · Reproduced in the browser.** Renaming updates the sidebar but the page heading and menubar retain the original title until reload. Other clients have no title-update event.

Source: [DocumentList.rename](../frontend/src/ui/DocumentList.tsx#L59), [title initialization](../frontend/src/editor/useCollaborativeDocument.ts#L180).

**Fix:** store document metadata centrally and publish/invalidate rename events. Make the page title directly editable, subject to capability checks. Keep search results synchronized with renamed/deleted entries.

### U03 Loading failures look like empty panels

**P2 · Source confirmed.** `usePanelData` logs errors but does not expose them, so the UI can say “No comments” or “No changes yet” during a failed request. The sharing dialog can show an error alongside “Loading…” without a retry action. Search errors also only reach the console.

Source: [usePanelData.ts](../frontend/src/ui/usePanelData.ts#L23), [DocumentList search](../frontend/src/ui/DocumentList.tsx#L40), [SharePanel.tsx](../frontend/src/ui/SharePanel.tsx#L187).

**Fix:** model loading, loaded-empty, stale-data, and failure separately. Retain useful prior data, show retry, and avoid indefinite loaders. Distinguish unreachable backend from deleted or inaccessible document errors.

### U04 The desktop layout has no narrow-screen strategy

**P2 · Improvement.** The sidebar and right panel consume 496 CSS pixels before editor padding. The stylesheet has no responsive breakpoints, uses `100vh`, and hides body overflow. Small screens and high browser zoom leave little usable editor space.

Source: [styles.css](../frontend/src/styles.css#L30), [sidebar](../frontend/src/styles.css#L137), [page](../frontend/src/styles.css#L230), [right panel](../frontend/src/styles.css#L280).

**Improve:** use collapsible document and collaboration drawers, allow closing History, reduce page padding, make the toolbar wrap/scroll deliberately, and account for the mobile keyboard with dynamic viewport sizing. Test 320–430px widths, tablet, 200% zoom, and long titles. Keep the editor as the primary use of space.

### U05 Accessibility needs a complete workflow pass

**P2 · Improvement.** Many fields rely on placeholders; the editing region lacks a useful accessible name; save feedback is not a live status; formatting controls do not expose pressed/mixed state. Several controls are around 22–28 pixels and faint metadata uses low-contrast gray. Focus outlines are removed in several fields.

Source: [Editor.tsx](../frontend/src/editor/Editor.tsx), [toolbar](../frontend/src/App.tsx#L646), [SaveIndicator.tsx](../frontend/src/ui/SaveIndicator.tsx), [styles.css](../frontend/src/styles.css).

**Improve:** label every field, name the document editing region, provide toolbar/group semantics, expose `aria-pressed`, announce errors/save/recovery states, increase targets, and audit contrast/focus indicators. Preserve the Share dialog's existing focus-trap foundation while verifying background inertness and focus restoration. Test keyboard navigation and a screen reader through sign-in, editing, comments, sharing, and restore.

### U06 Toolbar state and link behavior need to match editor expectations

**P2 · Source confirmed and improvement.** Toolbar buttons do not show current or mixed formatting, and undo/redo ignore `canUndo`/`canRedo`. Formatting an empty selection silently does nothing. Links are styled spans with the URL only in a tooltip; creation uses `window.prompt` and there is no open/edit/remove flow. Strike exists in the model but has no toolbar action.

Source: [FormatButtons](../frontend/src/App.tsx#L646), [formatting decorations](../frontend/src/editor/formatting.ts#L57), [link prompt](../frontend/src/editor/useCollaborativeDocument.ts#L306), [UndoManager capability getters](../frontend/src/crdt/undo.ts#L106).

**Improve:** display formatting state, disable unavailable history actions, support pending typing styles, and use a selection-preserving link popover with validation and accessible open/edit/remove controls. Use platform-aware shortcut labels instead of Mac-only hints.

### U07 Suggestions and document actions should respect capabilities visibly

**P2 · Source confirmed.** Viewers cannot open Suggestions because the entire toggle is disabled, although read-only suggestion rendering exists. Sidebar rename/delete buttons appear regardless of role because summaries contain no capabilities; readers discover their restriction only after a failed operation.

Source: [Suggestions toggle](../frontend/src/App.tsx#L529), [DocumentList.tsx](../frontend/src/ui/DocumentList.tsx#L146), [DocumentSummary](../frontend/src/api.ts#L17).

**Fix:** allow viewing proposals, gate composing and deciding separately, and return or resolve summary capabilities for row actions. Show consistent owner/editor/commenter/viewer descriptions and explain why a disabled action is unavailable.

### U08 Document URLs and navigation are not always canonical

**P2 · Source confirmed.** Automatic initial selection does not put the active document in the URL. A non-owner Share action copies the current location, which may be the app root and open a different document for the recipient. Back navigation to a URL without `doc` does not change the open document. Recovery navigation drops other query settings.

Source: [Workspace bootstrap](../frontend/src/App.tsx#L237), [popstate](../frontend/src/App.tsx#L291), [share](../frontend/src/App.tsx#L392), [recovery link](../frontend/src/App.tsx#L566).

**Fix:** derive copied URLs explicitly from document identity, maintain a canonical route for initial selection, define root/back behavior, and preserve appropriate query state. Show invalid/expired invite feedback rather than silently continuing.

### U09 Counts presence and timestamps can mislead

**P2 · Source confirmed.** Word count is estimated as characters divided by six; the review text contained ten words but showed nine. Character counts use UTF-16 units. Presence counts sessions as people while hiding every session with the current user's ID, so another device belonging to the same user disappears. The footer still reports the current user online during disconnection. Old history dates show only a time of day.

Source: [format.ts](../frontend/src/ui/format.ts#L10), [presence filters](../frontend/src/editor/useCollaborativeDocument.ts#L439), [status bar](../frontend/src/App.tsx#L606).

**Fix:** count real words with a defined multilingual policy, label character semantics, separate people from sessions, exclude only the current replica, reflect connection status, show avatar overflow, and include dates for older history. Update relative timestamps without requiring unrelated document changes.

### U10 History previews do not represent all formatting

**P2 · Source confirmed.** The preview omits headings, lists, quotes, and link behavior. The comparison operates on character identity and reports no content change for formatting-only changes. Restore is a single immediate action with no contextual preview of what will be overwritten.

Source: [FormattedPreview](../frontend/src/ui/HistoryPanel.tsx#L10), [Comparison](../frontend/src/ui/HistoryPanel.tsx#L48), [HistoryService.diff](../app/src/main/java/dev/collabedit/app/history/HistoryService.java#L102).

**Improve:** share a rendering model for preview/export, show formatting changes separately, include actor and restore context, and offer a review/undo path for restores. Paginate history instead of silently limiting it to the latest 200 groups.

### U11 Drafts and asynchronous actions need durable user feedback

**P2 · Source confirmed and improvement.** Switching panels or documents unmounts local comment/reply/suggestion drafts. Rapid document creation is not guarded by a busy state. Renaming exits the editor before the server confirms success. Queued comments show a count without a review/retry/remove interface.

Source: [panel mounting](../frontend/src/App.tsx#L616), [CommentsPanel drafts](../frontend/src/ui/CommentsPanel.tsx#L32), [SuggestionsPanel draft](../frontend/src/ui/SuggestionsPanel.tsx#L25), [createDocument](../frontend/src/App.tsx#L299).

**Improve:** preserve drafts per account/document, retain failed rename input, disable repeated mutations, and expose unsent work. Provide undo for moving a document to the bin, and choose a valid open document after deletion. Serialize sharing mutations or apply request ordering so delayed responses cannot overwrite newer selections.

### U12 Make commenting and proposing edits work directly in the document

**P3 · Improvement.** Comments and suggestions rely on a sidebar and manual selection rather than a linked document interaction. Suggestion insertion goes to the document start when nothing is selected, even if the caret is elsewhere.

Source: [proposeInsertion](../frontend/src/editor/useCollaborativeDocument.ts#L391), [CommentsPanel.tsx](../frontend/src/ui/CommentsPanel.tsx), [SuggestionsPanel.tsx](../frontend/src/ui/SuggestionsPanel.tsx).

**Improve:** connect highlights to threads, scroll/select the relevant range from a thread, support comment shortcuts and contextual selection actions, and use the caret as the insertion point. Add a real edit/suggest/view mode with clear proposal previews and bulk review only after correctness is established.

## Build deployment and delivery

### R08 Update dependencies with published security fixes

**P2 · Confirmed by npm audit and published advisories.** The installed tree includes `vitest` and `@vitest/mocker` 4.1.10 and `source-map-js` 1.2.1 through Vite/PostCSS. npm reports one high and two moderate affected packages. The Vitest issue affects redirect mocks under specific reachable development-server conditions; its presence does not establish a remotely exploitable production editor vulnerability. [Vitest advisory](https://github.com/advisories/GHSA-82fw-gwwq-j7x9), [source-map-js advisory](https://github.com/advisories/GHSA-68fv-2mgg-jv7q).

**Fix:** update the lockfile to patched compatible versions and rerun build/tests/audit. Add dependency and container scanning, update coverage for Docker base images, and pin deployment inputs where reproducibility matters. A clean npm audit alone does not cover Java dependencies, container packages, or application vulnerabilities.

### R01 The frontend cannot pass type checking or build

**P1 · Reproduced by commands.** `App.tsx:323` passes a callback accepting an optional string directly as a mouse handler; the event is incompatible and can also become a non-renderable notice if invoked on the failure screen. `api.test.ts:24` supplies a mock whose inferred callable/constructable type does not satisfy the unauthorized callback signature.

Source: [App.tsx](../frontend/src/App.tsx#L323), [api.test.ts](../frontend/src/api.test.ts#L24), [package scripts](../frontend/package.json#L7).

**Fix:** wrap the sign-out callback in a zero-argument click handler, type the mock explicitly, and require clean type checking and production build in the release gate.

### R02 The frontend test run has a missing assertion

**P1 · Reproduced by Vitest.** The abort test ends with `.rejects.toThrow` without invoking the matcher. The rejected promise is unhandled, yet the individual test is reported passed. This also fails to exercise the request-success/transaction-abort scenario in D10.

Source: [offlineStore.test.ts](../frontend/src/transport/offlineStore.test.ts#L207).

**Fix:** invoke and await the matcher, ensure unhandled rejections fail CI, and improve the transaction stub so its behavior matches IndexedDB commit semantics.

### R03 The shipped frontend proxies to the wrong backend port

**P1 · Source confirmed.** The frontend image defaults to `BACKEND_ORIGIN=http://app:8080`, while the application defaults to port 8090. Compose does not override the proxy target. Static frontend health can pass while every proxied API and WebSocket call fails.

Source: [frontend Dockerfile](../frontend/Dockerfile#L25), [application port](../app/src/main/resources/application.yml#L24), [Compose frontend](../docker-compose.yml#L75).

**Fix:** align the upstream port explicitly and add a full-container smoke check that signs in, loads a document, and establishes collaboration. Check backend reachability separately from static page health.

### R04 Federated login routes do not reach the backend in the Docker UI

**P1 · Source confirmed.** With an empty `VITE_API_BASE`, provider links point to same-origin `/oauth2/authorization/...`. Nginx proxies only `/api/` and `/ws`; provider authorization and callback routes hit the SPA fallback. Compose also does not pass deployment app URL and allowed-origin configuration.

Source: [provider link](../frontend/src/App.tsx#L170), [nginx.conf.template](../frontend/nginx.conf.template#L38), [Compose environment](../docker-compose.yml#L48).

**Fix:** proxy OAuth initiation and callback paths, configure the external app URL, and ensure forwarded host/protocol handling generates valid redirects. Test an actual provider through the deployed origin, including non-localhost hostnames and account switching.

### R05 CI container build commands have no valid build context

**P1 · Source confirmed.** The commands concatenate the tag with the intended context: `collabedit-app:ci.` and `collabedit-frontend:ci./frontend`. Neither supplies a separate context argument.

Source: [ci.yml](../.github/workflows/ci.yml#L66).

**Fix:** use separate tag and context arguments, then actually start the built images for a smoke test. This catches R03/R04, which Vite-based E2E tests do not cover.

### R06 E2E CI starts the backend with development login disabled

**P1 · Source confirmed.** E2E fixtures call `/api/login` and fill the development name form, but CI only supplies the signing secret. Development login defaults to false and no identity provider is configured.

Source: [ci.yml backend launch](../.github/workflows/ci.yml#L145), [E2E helpers](../frontend/e2e/helpers.ts#L9), [application configuration](../app/src/main/resources/application.yml#L67).

**Fix:** explicitly enable development login only for the isolated test backend. Keep separate tests proving it is disabled by default and forbidden in deployment.

### R07 The nightly measurement setup is malformed

**P1 · Source confirmed.** `- run:./mvnw -B install -DskipTests` is a scalar entry instead of a step mapping because the space after `run:` is missing. It cannot serve as a valid GitHub Actions run step.

Source: [nightly.yml](../.github/workflows/nightly.yml#L127).

**Fix:** correct the syntax and validate workflow files with an Actions-aware linter. Validate workflow execution, rather than assuming a committed workflow has run successfully.

## Search scale and maintainability

### M01 Renamed titles remain in the search index

**P2 · Source confirmed.** Rename changes the title but does not rebuild `search_vector`, which contains the previous title from the last preview update. Search combines the stored vector with the current title, so the old title can keep matching until a later content snapshot. New content also waits for snapshot-driven indexing.

Source: [DocumentCatalog.rename](../app/src/main/java/dev/collabedit/app/persist/DocumentCatalog.java#L98), [updatePreview](../app/src/main/java/dev/collabedit/app/persist/DocumentCatalog.java#L115), [search](../app/src/main/java/dev/collabedit/app/persist/DocumentCatalog.java#L80).

**Fix:** update title indexing in the rename transaction and update content indexing on a bounded independent schedule after durable edits. Test search immediately after rename and after writes without another snapshot.

### M02 Document search and listing need explicit scale boundaries

**P2 · Source confirmed and improvement.** Lists/search/trash silently stop at 200 results without pagination. Controller filtering also performs per-document permission resolution after the SQL scope filter. The title expression already has a matching index in V10; the original suggestion to add that index was withdrawn during implementation verification.

Source: [DocumentRestController LIST_LIMIT](../app/src/main/java/dev/collabedit/app/api/DocumentRestController.java#L68), [DocumentCatalog search](../app/src/main/java/dev/collabedit/app/persist/DocumentCatalog.java#L80), [V9 migration](../app/src/main/resources/db/migration/V9__search_content.sql).

**Improve:** use cursor pagination and a visible result limit, measure query plans on representative data, index the actual query shape, and avoid redundant per-row authorization queries while preserving policy parity. Add search loading/failure states and query-length validation to the client.

### M03 Workspace support is incomplete across API and UI

**P2 · Source confirmed.** The API permits a caller-supplied workspace ID when creating a document without checking membership. Authorization recognizes workspace members, while workspace listing only recognizes owners. Creation does not provide a complete membership-management flow, and the frontend exposes no workspace management.

Source: [createOwned](../app/src/main/java/dev/collabedit/app/api/DocumentRestController.java#L99), [workspacesOf](../app/src/main/java/dev/collabedit/app/persist/DocumentCatalog.java#L133), [PermissionService](../app/src/main/java/dev/collabedit/app/auth/PermissionService.java#L100), [frontend API](../frontend/src/api.ts).

**Fix:** authorize workspace attachment and define owner/member semantics consistently. Either complete workspace listing, membership, move-document, and UI flows, or accurately present this as an unfinished internal capability.

### M04 Browser work scales with total blocks and marks

**P2 · Source confirmed; performance impact needs measurement.** The Java side has a treap, but the browser uses a block array with linear identity/position scans. Formatting rebuilds anchor indexes; editor synchronization reads complete strings; rendering refreshes formatting after edits. Tombstones and mark history accumulate during long sessions.

Source: [TypeScript RgaDocument](../frontend/src/crdt/document.ts#L188), [locateVisible and locateId](../frontend/src/crdt/document.ts#L466), [refreshStats](../frontend/src/editor/useCollaborativeDocument.ts#L117), [remote application](../frontend/src/editor/Editor.tsx#L125).

**Improve:** measure typing latency and memory at realistic document sizes, fragmented edits, many marks, and long-lived sessions. Then add indexed position/identity access, incremental formatting, and bounded work per frame. Reuse the simpler implementation as a correctness oracle rather than replacing it without parity tests.

### M05 Backend replay buffers and retained history need memory budgets

**P2 · Source confirmed and improvement.** Cold loading reads the full log tail; the loaded-document limit counts documents rather than bytes; unwritten operations are not bounded by the dependency-pending cap; generated commands can bypass that cap. During a database outage, unflushed replicas cannot be evicted. Historical mark operations continue to retain metadata and anchors.

Source: [DocumentRegistry.load](../app/src/main/java/dev/collabedit/app/domain/DocumentRegistry.java#L107), [DocumentReplica queues](../app/src/main/java/dev/collabedit/app/domain/DocumentReplica.java#L30), [generateAndEnqueue](../app/src/main/java/dev/collabedit/app/domain/DocumentReplica.java#L160), [GC mark references](../crdt-core/src/main/java/dev/collabedit/crdt/RgaDocument.java#L342).

**Improve:** paginate replay, bound queued bytes and document size, introduce backpressure before mutating accepted state, and design safe historical-mark compaction. Measure actual memory per loaded document. Avoid inferring safety from a count of 200 loaded documents.

### M06 Snapshot decoding needs consistent validation and browser memory protection

**P2 · Source confirmed and improvement.** The browser decompresses the whole snapshot before validating it, allocates placeholders proportional to contentless block lengths, and accepts count/length values with little structural validation. Java and TypeScript restore paths discard or handle different metadata. The normal source is trusted backend data, but corrupted snapshots can cause large allocations or repeated fallback loading.

Source: [snapshot.ts](../frontend/src/crdt/snapshot.ts#L69), [SnapshotCodec.java](../crdt-core/src/main/java/dev/collabedit/crdt/SnapshotCodec.java#L39), [restoreFrom](../frontend/src/crdt/document.ts#L65).

**Improve:** enforce compressed/decompressed and structural budgets, validate safe integer ranges and block relationships, represent contentless blocks without full placeholder strings, and test corruption/version mismatch symmetrically. Tie fallback behavior to a visible recoverable state rather than repeated reopen loops.

### M07 Operational metrics should reveal stalled collaboration

**P2 · Improvement.** Counts and latency histograms do not by themselves identify permanently pending edits, gaps skipped by a cursor, delayed indexing, or a snapshot that no longer matches its boundary. Shared scheduled flow work also means slow database work can delay broadcasts and acknowledgments for unrelated documents.

Source: [DocumentMetrics.java](../app/src/main/java/dev/collabedit/app/observability/DocumentMetrics.java), [SchedulingConfig.java](../app/src/main/java/dev/collabedit/app/config/SchedulingConfig.java), [ops configuration](../ops/).

**Improve:** measure oldest pending age, contiguous sequence lag, flush age/failures, queued bytes, snapshot eligibility failures, recovery success, authorization failures, and client reconnect loops. Add alerts with runbooks. Verify trace propagation and browser export through the deployed origin; Compose points browser tracing at localhost even when the observability profile is absent.

### M08 Preserve schema history and test real upgrade paths

**P2 · Improvement supported by local startup failure.** Existing local Flyway history had checksum mismatches for several migrations. Fresh-schema integration tests do not verify upgrade compatibility with an already used installation.

Source: [migrations](../app/src/main/resources/db/migration/), [IntegrationTestBase.java](../app/src/test/java/dev/collabedit/app/IntegrationTestBase.java), [README](../README.md).

**Improve:** treat applied migrations as immutable, use forward migrations for changes, provide upgrade fixtures, and document a safe local recovery process distinct from destructive demo resets. Add backup/restore drills covering operations, snapshots, users, memberships, comments, suggestions, and replica progress. Verify actual graceful shutdown drains durable commands; waiting for scheduled tasks is not itself a flush guarantee.

### M09 Reduce implementation leakage and duplicated state

**P3 · Improvement.** The UI exposes replica-assignment detail, clustered-presence diagnostics, and hashed-invite storage implementation in ordinary product flows. `App` and `useCollaborativeDocument` coordinate numerous separate readiness, role, connection, recovery, selection, and metadata states, allowing invalid combinations.

Source: [App.tsx](../frontend/src/App.tsx), [useCollaborativeDocument.ts](../frontend/src/editor/useCollaborativeDocument.ts), [SharePanel.tsx](../frontend/src/ui/SharePanel.tsx).

**Improve:** define explicit document lifecycle states and a small session/document service layer, keep diagnostic explanations behind the diagnostics surface, and share request/error handling across panels. Add runtime validation for stored sessions and meaningful API response shapes. Use cancellation or request generations so an old request cannot sign out or update a newer session.

### M10 Prioritize portable document workflows after correctness

**P3 · Improvement.** Useful additions include export/import, print layout, named versions, document duplication, an actual document home view, workspace navigation, sorting/favorites, find/replace, clearer trash recovery, and a keyboard shortcut/help surface.

**Improve:** begin with export of the existing text and supported marks, especially local recovery export during server failure. Specify preservation of comments/suggestions and unsupported formatting. Add advanced rich text, attachments, tables, and notifications only after the underlying operation, undo, and permission guarantees are stable.

## Test coverage that should change

### T01 Snapshot tests do not establish a valid snapshot boundary

**P2 · Source confirmed.** The test named “the sequence number is never ahead of the state in the snapshot” waits for writes on one instance and only compares the returned sequence to the last acknowledgment. It does not establish that every operation through the sequence exists in the serialized state, particularly on a lagging cluster node.

Source: [SnapshotEndpointIT.java](../app/src/test/java/dev/collabedit/app/SnapshotEndpointIT.java#L101).

**Fix:** compare decoded snapshot plus tail with canonical replay through the claimed boundary under concurrent writes, delayed gossip, lost messages, and pending dependencies.

### T02 Load tests can pass without content convergence

**P2 · Source confirmed.** The load harness and broadcast fanout wait for equal lengths. The fanout text is identical `x` characters, so ordering/content errors can be invisible. The load harness can print a negative convergence result without failing the test.

Source: [LoadHarnessIT.java](../app/src/test/java/dev/collabedit/app/LoadHarnessIT.java#L115), [BroadcastFanoutIT.java](../app/src/test/java/dev/collabedit/app/BroadcastFanoutIT.java#L95).

**Fix:** use distinct per-client content and compare canonical text, marks, identifiers where relevant, server state, and persisted replay. Make correctness failure fatal even when performance thresholds are informational. Include reconnect, snapshot, and restart in the same scenario.

### T03 Latency measurement omits fast receipts and misidentifies warmup

**P2 · Source confirmed.** Send timestamps are recorded after dispatch, so sufficiently fast receipts can arrive before their timestamp exists. The harness sorts latency samples and removes the fastest 20% as “steady” samples; that is not chronological warmup removal.

Source: [LoadHarnessIT.java](../app/src/test/java/dev/collabedit/app/LoadHarnessIT.java#L99), [sample processing](../app/src/test/java/dev/collabedit/app/LoadHarnessIT.java#L130), [TestStompClient.java](../app/src/test/java/dev/collabedit/app/TestStompClient.java#L203).

**Fix:** record timestamps before dispatch, preserve sample order/time, discard warmup by elapsed interval, and report dropped/unmatched samples. Avoid relying on existing benchmark numbers until the measurement path is corrected.

### T04 Add workflow regressions around the strong algorithm tests

**P2 · Improvement.** Existing property and conformance tests are valuable, but the reproduced failures occur at boundaries: GC plus replay, persistence cursor plus missed gossip, undo plus remote edits, access cache plus invalidation, and request retry plus metadata creation.

Add deterministic regressions for:

1. Private retry-key collisions across users/documents and replies.
2. WebSocket expiry, revocation races, invite revocation, and authorization lookup failures.
3. Missing Redis operation followed by a local flush, snapshot, and restart.
4. GC followed by duplicate replay, offline reconnect, and concurrent no-op deletes.
5. Full dependency buffer followed by its missing cause.
6. Commit-success/ack-failure without disconnect and byte-limited redo.
7. Recovery typing races, failed recovery, and server-generated command crash durability.
8. One-transaction undo, arbitrary deletion locations, formatting restoration, and remote insertion ordering.
9. Caret/selection and composition preservation during batched remote edits.
10. Comment response loss, anchor readiness, account switching, and backend-only outages.
11. Two-client metadata updates without a document-length change.
12. Real built-container routing, OAuth redirects, workflow syntax, and upgrade fixtures.
13. Responsive layout, keyboard/screen-reader access, touch controls, and real IME input.

### T05 E2E fixtures hide setup and unrelated network failures

**P2 · Source confirmed.** Fixture login, document creation, and visibility changes do not assert response success. Console filtering globally allows resource-load failures, failed WebSocket connections, and history-loading errors, even outside intentional outage scenarios.

Source: [E2E helpers](../frontend/e2e/helpers.ts#L9).

**Fix:** assert each setup response before consuming its body, and allow expected errors only within the specific fault-injection window. Add response-level diagnostics so an unrelated broken API/proxy causes a useful test failure.

## Suggested implementation order

| Stage | Work | Acceptance criteria |
| --- | --- | --- |
| 1 | S01–S04, D01–D06, E01–E03 | No private-thread disclosure; expired/revoked sessions stop; canonical replay survives missed gossip/GC/restart; new work survives recovery; undo preserves remote ordering and transport limits. |
| 2 | R01–R07 and T01–T03 | Typecheck, unit tests, production build, CI workflows, and real container smoke test all succeed; correctness and latency measurements are meaningful. |
| 3 | Remaining D and C findings, S05–S07, E04–E08 | Reliable offline replay and retries; account-owned queues; consistent metadata events; suggestions and history remain coherent; editor transactions undo correctly. |
| 4 | U01–U11 and M01–M08 | Usable mobile/zoom/keyboard flows; accurate save/errors/counts; responsive metadata; paginated indexed discovery; measured memory/performance; upgrade and recovery runbooks. |
| 5 | U12, M09–M10 | Clear edit/suggest/view modes, maintainable lifecycle state, export and richer document organization built on verified behavior. |

Do not replace the custom CRDT or rewrite the entire UI as the first response to these findings. The existing tests, conformance corpus, and simple reference implementations are useful assets. Repair the specific invariants, add regressions that fail on the current behavior, and then improve the architecture around measured bottlenecks.
