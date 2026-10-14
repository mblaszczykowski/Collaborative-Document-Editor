DROP INDEX comment_threads_by_client_id;
DROP INDEX comment_messages_by_client_id;

ALTER TABLE comment_threads ADD COLUMN initial_body TEXT;
UPDATE comment_threads t SET initial_body = (
    SELECT m.body FROM comment_messages m WHERE m.thread_id = t.id
    ORDER BY CASE WHEN t.client_id IS NOT NULL
                       AND m.client_id = t.client_id || ':first' THEN 0 ELSE 1 END,
             m.created_at, m.id LIMIT 1
);

CREATE UNIQUE INDEX comment_threads_by_client_id
    ON comment_threads (doc_id, created_by, client_id) WHERE client_id IS NOT NULL;
CREATE UNIQUE INDEX comment_messages_by_client_id
    ON comment_messages (thread_id, author_id, client_id) WHERE client_id IS NOT NULL;
