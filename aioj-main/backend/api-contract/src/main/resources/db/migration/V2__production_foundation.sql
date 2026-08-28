CREATE TABLE IF NOT EXISTS refresh_tokens (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    token_hash CHAR(64) NOT NULL UNIQUE,
    expires_at DATETIME(3) NOT NULL,
    revoked_at DATETIME(3),
    created_at DATETIME(3) NOT NULL,
    CONSTRAINT fk_refresh_tokens_user FOREIGN KEY (user_id) REFERENCES users(id),
    INDEX idx_refresh_tokens_user (user_id, created_at),
    INDEX idx_refresh_tokens_expires (expires_at)
);

CREATE TABLE IF NOT EXISTS problem_solutions (
    id BIGINT PRIMARY KEY,
    problem_id BIGINT NOT NULL,
    language VARCHAR(32) NOT NULL,
    content MEDIUMTEXT NOT NULL,
    created_by BIGINT,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    CONSTRAINT fk_problem_solutions_problem FOREIGN KEY (problem_id) REFERENCES problems(id),
    INDEX idx_problem_solutions_problem (problem_id)
);

CREATE TABLE IF NOT EXISTS judge_audit_logs (
    id BIGINT PRIMARY KEY,
    submission_id BIGINT NOT NULL,
    from_status VARCHAR(40),
    to_status VARCHAR(40) NOT NULL,
    worker_id VARCHAR(120),
    message VARCHAR(512),
    created_at DATETIME(3) NOT NULL,
    CONSTRAINT fk_judge_audit_submission FOREIGN KEY (submission_id) REFERENCES submissions(id),
    INDEX idx_judge_audit_submission (submission_id, created_at)
);

CREATE TABLE IF NOT EXISTS prompt_versions (
    id BIGINT PRIMARY KEY,
    code VARCHAR(80) NOT NULL,
    version VARCHAR(40) NOT NULL,
    content MEDIUMTEXT NOT NULL,
    enabled TINYINT NOT NULL DEFAULT 1,
    created_by BIGINT,
    created_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_prompt_versions_code_version (code, version),
    INDEX idx_prompt_versions_enabled (code, enabled)
);

CREATE TABLE IF NOT EXISTS ai_quota_policies (
    id BIGINT PRIMARY KEY,
    scope_type VARCHAR(24) NOT NULL,
    scope_id BIGINT,
    daily_limit BIGINT NOT NULL,
    monthly_limit BIGINT NOT NULL,
    enabled TINYINT NOT NULL DEFAULT 1,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_ai_quota_scope (scope_type, scope_id),
    INDEX idx_ai_quota_enabled (enabled)
);

CREATE TABLE IF NOT EXISTS class_members (
    class_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    role VARCHAR(32) NOT NULL,
    joined_at DATETIME(3) NOT NULL,
    PRIMARY KEY (class_id, user_id),
    CONSTRAINT fk_class_members_class FOREIGN KEY (class_id) REFERENCES classes(id),
    CONSTRAINT fk_class_members_user FOREIGN KEY (user_id) REFERENCES users(id),
    INDEX idx_class_members_user (user_id)
);

CREATE INDEX idx_problems_title ON problems (title);
CREATE INDEX idx_problem_drafts_creator_status ON problem_drafts (creator_user_id, status, created_at);
