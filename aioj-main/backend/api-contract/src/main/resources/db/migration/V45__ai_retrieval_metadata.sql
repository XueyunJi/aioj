ALTER TABLE ai_retrieval_chunks
    ADD COLUMN metadata_json JSON NULL AFTER chunk_text,
    ADD COLUMN sensitivity VARCHAR(48) NOT NULL DEFAULT 'USER_PRIVATE_SAFE' AFTER metadata_json,
    ADD COLUMN problem_id BIGINT NULL AFTER sensitivity,
    ADD COLUMN submission_id BIGINT NULL AFTER problem_id,
    ADD COLUMN contest_id BIGINT NULL AFTER submission_id,
    ADD COLUMN contest_run_id BIGINT NULL AFTER contest_id,
    ADD COLUMN contest_problem_id BIGINT NULL AFTER contest_run_id,
    ADD COLUMN algorithm_key VARCHAR(128) NULL AFTER contest_problem_id,
    ADD COLUMN profile_key VARCHAR(128) NULL AFTER algorithm_key,
    ADD INDEX idx_ai_chunk_user_problem (user_id, problem_id, updated_at),
    ADD INDEX idx_ai_chunk_user_submission (user_id, submission_id, updated_at),
    ADD INDEX idx_ai_chunk_user_algorithm (user_id, algorithm_key, updated_at),
    ADD INDEX idx_ai_chunk_user_profile (user_id, profile_key, updated_at);
