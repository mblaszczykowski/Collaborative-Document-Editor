-- Previous replicas could label snapshots with a non-contiguous operation-log cursor.
-- Snapshots are derived caches; preserve the authoritative operation log and rebuild
-- lazily on document load under the corrected contiguous-cursor invariants.
DELETE FROM snapshots;
