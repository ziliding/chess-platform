-- Deleting a user keeps their matches for the opponent's history; the deleted player's side becomes NULL.
ALTER TABLE match_records
    ALTER COLUMN white_user_id DROP NOT NULL,
    ALTER COLUMN black_user_id DROP NOT NULL,
    DROP CONSTRAINT match_records_white_user_id_fkey,
    DROP CONSTRAINT match_records_black_user_id_fkey,
    ADD CONSTRAINT match_records_white_user_id_fkey FOREIGN KEY (white_user_id) REFERENCES users (id) ON DELETE SET NULL,
    ADD CONSTRAINT match_records_black_user_id_fkey FOREIGN KEY (black_user_id) REFERENCES users (id) ON DELETE SET NULL;
