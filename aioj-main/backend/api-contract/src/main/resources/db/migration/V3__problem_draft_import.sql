ALTER TABLE problem_drafts
    ADD COLUMN imported_problem_id BIGINT NULL;

CREATE INDEX idx_problem_drafts_imported_problem
    ON problem_drafts (imported_problem_id);
