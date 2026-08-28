ALTER TABLE users
    DROP INDEX account;

ALTER TABLE users
    ADD COLUMN active_account VARCHAR(64)
        GENERATED ALWAYS AS (CASE WHEN deleted_at IS NULL THEN account ELSE NULL END) STORED,
    ADD UNIQUE KEY uk_users_active_account (active_account);

ALTER TABLE ai_conversations
    ADD COLUMN contest_id BIGINT NULL AFTER problem_id,
    ADD COLUMN contest_run_id BIGINT NULL AFTER contest_id,
    ADD COLUMN contest_problem_id BIGINT NULL AFTER contest_run_id;

CREATE INDEX idx_ai_conversations_contest_context
    ON ai_conversations (contest_id, contest_run_id, contest_problem_id, updated_at);

ALTER TABLE ai_messages
    ADD COLUMN contest_id BIGINT NULL AFTER problem_id,
    ADD COLUMN contest_run_id BIGINT NULL AFTER contest_id,
    ADD COLUMN contest_problem_id BIGINT NULL AFTER contest_run_id;

CREATE INDEX idx_ai_messages_contest_context
    ON ai_messages (contest_id, contest_run_id, contest_problem_id, created_at);

ALTER TABLE ai_usage_records
    ADD COLUMN contest_id BIGINT NULL AFTER class_id,
    ADD COLUMN contest_run_id BIGINT NULL AFTER contest_id,
    ADD COLUMN contest_problem_id BIGINT NULL AFTER contest_run_id;

CREATE INDEX idx_ai_usage_contest_context
    ON ai_usage_records (contest_id, contest_run_id, contest_problem_id, created_at);
