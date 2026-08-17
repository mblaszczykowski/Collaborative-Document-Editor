ALTER TABLE comment_threads ADD COLUMN client_id TEXT;
ALTER TABLE comment_messages ADD COLUMN client_id TEXT;

CREATE UNIQUE INDEX comment_threads_by_client_id
    ON comment_threads (client_id)
    WHERE client_id IS NOT NULL;

CREATE UNIQUE INDEX comment_messages_by_client_id
    ON comment_messages (client_id)
    WHERE client_id IS NOT NULL;
