ALTER TABLE operations
    ADD COLUMN chars_inserted INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN chars_deleted  INTEGER NOT NULL DEFAULT 0;

UPDATE operations
   SET chars_inserted = COALESCE(length(payload ->> 's'), 0)
 WHERE op_type = 'i';

UPDATE operations
   SET chars_deleted = COALESCE((
           SELECT SUM((range_entry ->> 2)::int)
             FROM jsonb_array_elements(payload -> 'g') AS range_entry
       ), 0)
 WHERE op_type = 'd';

COMMENT ON COLUMN operations.chars_inserted IS
    'Characters this operation added. Recorded at write time; see V6 for why not derived.';
COMMENT ON COLUMN operations.chars_deleted IS
    'Characters this operation removed. Recorded at write time; see V6 for why not derived.';
