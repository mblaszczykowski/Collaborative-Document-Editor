-- Reserve payload identity before speculative CRDT merge on any application instance.
-- Claims are removed atomically when the canonical operation log receives the operation.
CREATE TABLE operation_identity_claims (
    doc_id UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    replica_id INTEGER NOT NULL,
    lamport BIGINT NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    PRIMARY KEY (doc_id, replica_id, lamport)
);
