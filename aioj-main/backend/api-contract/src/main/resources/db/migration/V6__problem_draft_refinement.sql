ALTER TABLE problem_drafts
    ADD COLUMN refined_from_draft_id BIGINT NULL COMMENT 'Original draft if this one is a refinement' AFTER imported_problem_id,
    ADD COLUMN refine_note VARCHAR(500) NULL COMMENT 'Reason or instruction provided by reviewer when creating this refined draft';

CREATE INDEX idx_problem_drafts_refined_from ON problem_drafts (refined_from_draft_id);
