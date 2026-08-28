ALTER TABLE ai_problem_draft_generation_jobs
    ADD COLUMN job_type VARCHAR(32) NOT NULL DEFAULT 'GENERATE' AFTER creator_user_id,
    ADD COLUMN source_draft_id BIGINT NULL AFTER job_type,
    ADD INDEX idx_ai_problem_draft_jobs_type_source (job_type, source_draft_id, updated_at),
    ADD INDEX idx_ai_problem_draft_jobs_updated (updated_at);
