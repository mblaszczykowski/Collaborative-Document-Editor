CREATE SEQUENCE client_replica_id_seq AS INTEGER MINVALUE 1 MAXVALUE 2147483647 NO CYCLE;
CREATE SEQUENCE server_replica_id_seq AS INTEGER MINVALUE 1 MAXVALUE 2147483647 NO CYCLE;
CREATE TABLE client_replica_owners (
    replica_id INTEGER PRIMARY KEY CHECK (replica_id > 0),
    user_id TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- Ambiguous historical identities are reserved, rather than attributed to one account.
INSERT INTO client_replica_owners (replica_id, user_id)
SELECT replica_id, CASE WHEN count(DISTINCT user_id) = 1 AND count(*) = count(user_id)
                       THEN min(user_id) ELSE NULL END
FROM operations WHERE replica_id > 0 GROUP BY replica_id;
SELECT setval('client_replica_id_seq', COALESCE(max(replica_id), 1), count(*) > 0)
FROM client_replica_owners;
SELECT setval('server_replica_id_seq', COALESCE(max(-(replica_id::BIGINT)), 1), count(*) > 0)
FROM operations WHERE replica_id < 0;
