CREATE TABLE ai_learning_profile (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    category VARCHAR(48) NOT NULL,
    profile_key VARCHAR(128) NOT NULL,
    label VARCHAR(200) NOT NULL,
    state VARCHAR(32) NOT NULL DEFAULT 'CANDIDATE',
    confidence DECIMAL(5,4) NOT NULL DEFAULT 0.0000,
    evidence_count INT NOT NULL DEFAULT 0,
    disabled_at DATETIME(3) NULL,
    deleted_at DATETIME(3) NULL,
    last_evidence_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_ai_learning_profile_key (user_id, category, profile_key),
    KEY idx_ai_learning_profile_user_state (user_id, state, updated_at),
    KEY idx_ai_learning_profile_user_category (user_id, category, state)
);

CREATE TABLE ai_learning_profile_evidence (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    profile_id BIGINT NOT NULL,
    evidence_type VARCHAR(48) NOT NULL,
    source_type VARCHAR(48) NOT NULL,
    source_id VARCHAR(128) NULL,
    summary MEDIUMTEXT NOT NULL,
    confidence DECIMAL(5,4) NOT NULL DEFAULT 0.0000,
    code_hash VARCHAR(128) NULL,
    created_at DATETIME(3) NOT NULL,
    KEY idx_ai_learning_profile_evidence_profile (profile_id, created_at),
    KEY idx_ai_learning_profile_evidence_user (user_id, source_type, source_id)
);

CREATE TABLE ai_submission_analysis (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    submission_id BIGINT NOT NULL,
    problem_id BIGINT NULL,
    contest_id BIGINT NULL,
    contest_run_id BIGINT NULL,
    contest_problem_id BIGINT NULL,
    status VARCHAR(48) NULL,
    language VARCHAR(32) NULL,
    code_hash VARCHAR(128) NULL,
    root_cause_tags JSON NULL,
    summary MEDIUMTEXT NOT NULL,
    ai_message_id BIGINT NULL,
    confidence DECIMAL(5,4) NOT NULL DEFAULT 0.6000,
    created_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_ai_submission_analysis_user_submission (user_id, submission_id),
    KEY idx_ai_submission_analysis_problem (user_id, problem_id, created_at),
    KEY idx_ai_submission_analysis_contest (user_id, contest_run_id, contest_problem_id, created_at)
);
