-- Causal stability, made durable and cluster-wide.
--
-- THE TWO BUGS THIS EXISTS TO CLOSE. The tombstone-collection design bases garbage collection on causal
-- stability: a tombstone may be removed once every replica that could still send an operation
-- anchored to it has seen the deletion. `DocumentReplica.stableVersion` computed that from a
-- ConcurrentHashMap on the replica object, and that map had two properties nobody had written down.
--
--   1. IT WAS PER INSTANCE. It was filled only by `reportProgress`, whose callers are this
--      instance's own STOMP sessions. Nothing carried version vectors between instances — not the
--      presence channel, not the operation channel, not the permission channel. So on the
--      multi-instance deployment the cluster design designs for, instance A collected tombstones that a
--      client connected to instance B still needed, and that client's next operation arrived at A
--      anchored to a character A no longer had. It buffers there forever: `applyRemote` does not go
--      through the `isPermanentlyUnanchored` refusal that `submit` does, so there is not even an
--      honest RESYNC_REQUIRED — the operation is simply invisible, and `crdt.pending.operations`
--      climbs, which its own description calls corruption.
--
--   2. IT DID NOT SURVIVE EVICTION. A replica with no clients is dropped after five minutes and
--      reloaded empty, so a client that departed with unsynchronised work stopped constraining GC
--      the moment the document went quiet. The GC window says seven days; the real bound was
--      `idle-document-eviction-ms`. This is the exact mirror of the bug the cluster design records as
--      fixed for the WATERMARK, which was made durable in the snapshot while the constraint set
--      that feeds GC was left in memory — so the same failure survived facing the other way, over-
--      collecting instead of under-refusing.
--
-- WHY A TABLE RATHER THAN THE SNAPSHOT. The watermark went into the snapshot because it is part of
-- the document's state. This is not: it is what the *cluster* currently knows about who has seen
-- what, it changes every fifteen seconds per client, and a snapshot is a cache that may be deleted
-- at any moment (the operation-log design). Putting a GC safety constraint into a deletable cache would make
-- deleting the cache unsafe, which is precisely the property the operation-log design is protecting.
--
-- WHY POSTGRES RATHER THAN REDIS. The cluster design is explicit that Redis is a best-effort fast path and
-- Postgres is the source of truth. Losing a progress report has to mean "GC waits", never "GC
-- proceeds without knowing" — so this belongs on the authoritative side. It is the same reasoning
-- that puts replica-id allocation on the one Redis key that IS authoritative, resolved the other
-- way because here the safe failure is the durable one.
CREATE TABLE replica_progress (
    doc_id      UUID        NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    -- The CRDT replica identifier: positive for clients, negative for backend instances.
    replica_id  INTEGER     NOT NULL,
    -- The replica's version vector, as {replicaId: maxLamport}. JSONB rather than a row per entry:
    -- it is read and written whole, never queried into, and one row per (document, replica) is what
    -- makes the upsert below a single statement.
    version     JSONB       NOT NULL,
    -- Written from now() rather than from the application clock, deliberately. These timestamps are
    -- compared against a threshold by whichever instance happens to run the snapshot sweep, so a
    -- clock skew between instances would otherwise decide whether somebody's unsynchronised work is
    -- still protected. One clock, the database's, for a decision that spans the cluster.
    reported_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- NULL while connected. Set on departure, and the row then keeps constraining GC for the GC
    -- window rather than being deleted — that window exists exactly so somebody who closed their
    -- laptop can come back and still merge.
    departed_at TIMESTAMPTZ,

    PRIMARY KEY (doc_id, replica_id)
);

-- The one query this table serves: "everybody who still counts for this document". Covering
-- `version` so the read is index-only — it runs on the maintenance pool while the document lock is
-- NOT held, but it runs once per snapshot per document.
CREATE INDEX replica_progress_live
    ON replica_progress (doc_id, reported_at)
    INCLUDE (version, departed_at);
