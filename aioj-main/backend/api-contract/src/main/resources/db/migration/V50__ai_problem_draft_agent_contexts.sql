CREATE TABLE IF NOT EXISTS ai_problem_draft_agent_contexts (
    id BIGINT PRIMARY KEY,
    draft_id BIGINT NULL,
    job_id BIGINT NOT NULL,
    job_item_id BIGINT NULL,
    attempt_no INT NOT NULL,
    agent_role VARCHAR(32) NOT NULL,
    stream_type VARCHAR(32) NOT NULL,
    content_json JSON NULL,
    content_preview VARCHAR(1000) NULL,
    estimated_tokens INT NOT NULL DEFAULT 0,
    compressed TINYINT(1) NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    INDEX idx_ai_problem_draft_agent_contexts_draft_attempt (draft_id, attempt_no),
    INDEX idx_ai_problem_draft_agent_contexts_job_item (job_id, job_item_id, attempt_no),
    INDEX idx_ai_problem_draft_agent_contexts_role_stream (draft_id, agent_role, stream_type, attempt_no)
);
