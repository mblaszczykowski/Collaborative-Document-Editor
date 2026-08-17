ALTER TABLE users ADD COLUMN provider TEXT;
ALTER TABLE users ADD COLUMN subject  TEXT;
ALTER TABLE users ADD COLUMN email    TEXT;

CREATE UNIQUE INDEX users_by_federated_identity
    ON users (provider, subject)
    WHERE provider IS NOT NULL;

CREATE TABLE login_codes (
    code_hash  TEXT        PRIMARY KEY,
    user_id    TEXT        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX login_codes_by_age ON login_codes (created_at);
