ALTER TABLE ai_problem_draft_generation_jobs
    ADD COLUMN ai_round_count INT NOT NULL DEFAULT 0 AFTER progress_message;

