ALTER TABLE users
    ADD COLUMN password_reset_required TINYINT NOT NULL DEFAULT 0 AFTER enabled;

CREATE INDEX idx_refresh_tokens_created_user ON refresh_tokens (created_at, user_id);
CREATE INDEX idx_users_created ON users (created_at);
CREATE INDEX idx_ai_usage_created_success ON ai_usage_records (created_at, success);
CREATE INDEX idx_submissions_created_status ON submissions (created_at, status);
