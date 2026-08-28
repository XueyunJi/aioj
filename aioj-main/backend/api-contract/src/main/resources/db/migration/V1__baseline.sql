CREATE TABLE IF NOT EXISTS users (
    id BIGINT PRIMARY KEY,
    account VARCHAR(64) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    display_name VARCHAR(80) NOT NULL,
    email VARCHAR(160),
    enabled TINYINT NOT NULL DEFAULT 1,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL
);

CREATE TABLE IF NOT EXISTS user_roles (
    user_id BIGINT NOT NULL,
    role VARCHAR(32) NOT NULL,
    PRIMARY KEY (user_id, role),
    CONSTRAINT fk_user_roles_user FOREIGN KEY (user_id) REFERENCES users(id)
);

CREATE TABLE IF NOT EXISTS classes (
    id BIGINT PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    owner_user_id BIGINT NOT NULL,
    created_at DATETIME(3) NOT NULL,
    INDEX idx_classes_owner (owner_user_id)
);

CREATE TABLE IF NOT EXISTS problems (
    id BIGINT PRIMARY KEY,
    title VARCHAR(120) NOT NULL,
    difficulty VARCHAR(32) NOT NULL,
    statement MEDIUMTEXT NOT NULL,
    tags JSON NOT NULL,
    time_limit_millis INT NOT NULL,
    memory_limit_kb INT NOT NULL,
    ai_generated TINYINT NOT NULL DEFAULT 0,
    created_by BIGINT,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    deleted TINYINT NOT NULL DEFAULT 0,
    INDEX idx_problems_difficulty (difficulty),
    INDEX idx_problems_created_at (created_at)
);

CREATE TABLE IF NOT EXISTS problem_test_cases (
    id BIGINT PRIMARY KEY,
    problem_id BIGINT NOT NULL,
    input MEDIUMTEXT NOT NULL,
    expected_output MEDIUMTEXT NOT NULL,
    sample TINYINT NOT NULL DEFAULT 0,
    sort_order INT NOT NULL DEFAULT 0,
    CONSTRAINT fk_test_cases_problem FOREIGN KEY (problem_id) REFERENCES problems(id),
    INDEX idx_test_cases_problem (problem_id)
);

CREATE TABLE IF NOT EXISTS submissions (
    id BIGINT PRIMARY KEY,
    problem_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    language VARCHAR(32) NOT NULL,
    code MEDIUMTEXT NOT NULL,
    status VARCHAR(40) NOT NULL,
    judge_message VARCHAR(512),
    time_millis BIGINT,
    memory_kb BIGINT,
    retry_count INT NOT NULL DEFAULT 0,
    idempotency_key VARCHAR(128),
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    judged_at DATETIME(3),
    UNIQUE KEY uk_submission_idempotency (idempotency_key),
    INDEX idx_submissions_user_created (user_id, created_at),
    INDEX idx_submissions_problem_created (problem_id, created_at),
    INDEX idx_submissions_status (status)
);

CREATE TABLE IF NOT EXISTS ai_conversations (
    id VARCHAR(64) PRIMARY KEY,
    user_id BIGINT NOT NULL,
    problem_id BIGINT,
    title VARCHAR(160),
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    INDEX idx_ai_conversations_user (user_id, updated_at)
);

CREATE TABLE IF NOT EXISTS ai_messages (
    id BIGINT PRIMARY KEY,
    conversation_id VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    role VARCHAR(24) NOT NULL,
    content MEDIUMTEXT NOT NULL,
    model VARCHAR(120),
    created_at DATETIME(3) NOT NULL,
    CONSTRAINT fk_ai_messages_conversation FOREIGN KEY (conversation_id) REFERENCES ai_conversations(id),
    INDEX idx_ai_messages_conversation (conversation_id, created_at)
);

CREATE TABLE IF NOT EXISTS ai_usage_records (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    class_id BIGINT,
    provider VARCHAR(64) NOT NULL,
    model VARCHAR(120) NOT NULL,
    prompt_tokens BIGINT NOT NULL DEFAULT 0,
    completion_tokens BIGINT NOT NULL DEFAULT 0,
    success TINYINT NOT NULL,
    created_at DATETIME(3) NOT NULL,
    INDEX idx_ai_usage_user_created (user_id, created_at)
);

CREATE TABLE IF NOT EXISTS problem_drafts (
    id BIGINT PRIMARY KEY,
    creator_user_id BIGINT NOT NULL,
    title VARCHAR(120) NOT NULL,
    difficulty VARCHAR(32) NOT NULL,
    draft_json JSON NOT NULL,
    validation_status VARCHAR(32) NOT NULL,
    validation_errors JSON,
    model VARCHAR(120),
    status VARCHAR(32) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    reviewed_at DATETIME(3),
    reviewed_by BIGINT,
    INDEX idx_problem_drafts_status (status, created_at)
);
