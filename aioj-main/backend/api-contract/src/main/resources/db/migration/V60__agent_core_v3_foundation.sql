-- Agent Core V3 foundation: trusted control plane tables.
-- Sole authority: docs/AIOJ_AGENT_CORE_V3_IMPLEMENTATION_DESIGN.md §7.2.
-- All tables are additive; legacy pipeline tables remain untouched.

CREATE TABLE ai_agent_runs (
    id BIGINT PRIMARY KEY,
    turn_id VARCHAR(64) NOT NULL,
    conversation_id VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    provider VARCHAR(64) NOT NULL,
    model VARCHAR(160) NOT NULL,
    status VARCHAR(32) NOT NULL,
    step_count INT NOT NULL DEFAULT 0,
    tool_call_count INT NOT NULL DEFAULT 0,
    budget_json JSON NULL,
    policy_snapshot_id VARCHAR(64) NULL,
    output_mode VARCHAR(32) NULL,
    error_code VARCHAR(64) NULL,
    started_at DATETIME(3) NOT NULL,
    completed_at DATETIME(3) NULL,
    UNIQUE KEY uk_agent_run_turn (turn_id),
    KEY idx_agent_run_user (user_id, started_at),
    KEY idx_agent_run_status (status, started_at)
);

CREATE TABLE ai_policy_snapshots (
    id VARCHAR(64) PRIMARY KEY,
    user_id BIGINT NOT NULL,
    turn_id VARCHAR(64) NOT NULL,
    participant_status VARCHAR(32) NOT NULL,
    contest_ids JSON NULL,
    policy_json JSON NOT NULL,
    policy_version VARCHAR(64) NOT NULL,
    calculated_at DATETIME(3) NOT NULL,
    valid_until DATETIME(3) NOT NULL,
    KEY idx_policy_snapshot_turn (turn_id),
    KEY idx_policy_snapshot_user (user_id, calculated_at)
);

CREATE TABLE ai_tool_calls (
    id BIGINT PRIMARY KEY,
    agent_run_id BIGINT NOT NULL,
    turn_id VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    call_id VARCHAR(128) NOT NULL,
    call_seq INT NOT NULL,
    tool_name VARCHAR(128) NOT NULL,
    tool_version VARCHAR(32) NOT NULL,
    arguments_redacted JSON NULL,
    policy_decision_id VARCHAR(64) NULL,
    status VARCHAR(32) NOT NULL,
    result_classification VARCHAR(48) NULL,
    result_hash VARCHAR(128) NULL,
    result_tokens INT NULL,
    latency_ms INT NULL,
    error_code VARCHAR(64) NULL,
    created_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_tool_call (agent_run_id, call_id),
    KEY idx_tool_calls_turn (turn_id, call_seq),
    KEY idx_tool_calls_tool (tool_name, status, created_at)
);

CREATE TABLE ai_context_manifests (
    id BIGINT PRIMARY KEY,
    turn_id VARCHAR(64) NOT NULL,
    agent_run_id BIGINT NOT NULL,
    call_seq INT NOT NULL,
    model VARCHAR(160) NOT NULL,
    prompt_version VARCHAR(64) NOT NULL,
    policy_snapshot_id VARCHAR(64) NULL,
    sections_json JSON NOT NULL,
    tool_definitions_hash VARCHAR(128) NULL,
    context_hash VARCHAR(128) NULL,
    input_tokens INT NULL,
    cache_hit_tokens INT NULL,
    warnings_json JSON NULL,
    created_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_manifest_call (agent_run_id, call_seq),
    KEY idx_manifest_turn (turn_id)
);

CREATE TABLE ai_guard_decisions (
    id BIGINT PRIMARY KEY,
    turn_id VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    conversation_id VARCHAR(64) NOT NULL,
    layer VARCHAR(32) NOT NULL,
    decision VARCHAR(32) NOT NULL,
    matched_problem_refs JSON NULL,
    reason_code VARCHAR(96) NULL,
    detail_json JSON NULL,
    degraded TINYINT(1) NOT NULL DEFAULT 0,
    latency_ms INT NULL,
    created_at DATETIME(3) NOT NULL,
    KEY idx_guard_turn (turn_id, layer),
    KEY idx_guard_user_time (user_id, created_at),
    KEY idx_guard_decision (decision, layer, created_at)
);

CREATE TABLE ai_turn_digests (
    id BIGINT PRIMARY KEY,
    turn_id VARCHAR(64) NOT NULL,
    conversation_id VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    summary MEDIUMTEXT NULL,
    structured_digest JSON NULL,
    search_text MEDIUMTEXT NULL,
    source_hash VARCHAR(128) NOT NULL,
    digest_version INT NOT NULL DEFAULT 1,
    curator_model VARCHAR(160) NULL,
    curator_prompt_version VARCHAR(64) NULL,
    status VARCHAR(32) NOT NULL,
    token_estimate INT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_digest_turn (turn_id, digest_version),
    KEY idx_digest_conversation (user_id, conversation_id, updated_at),
    KEY idx_digest_status (status, updated_at)
);

CREATE TABLE ai_async_jobs (
    id BIGINT PRIMARY KEY,
    job_type VARCHAR(80) NOT NULL,
    status VARCHAR(32) NOT NULL,
    idempotency_key VARCHAR(191) NOT NULL,
    payload_json JSON NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    max_attempts INT NOT NULL DEFAULT 5,
    next_retry_at DATETIME(3) NOT NULL,
    lease_owner VARCHAR(128) NULL,
    lease_expires_at DATETIME(3) NULL,
    last_error VARCHAR(1000) NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    completed_at DATETIME(3) NULL,
    UNIQUE KEY uk_ai_async_jobs_idempotency (idempotency_key),
    KEY idx_ai_async_jobs_status_due (status, next_retry_at),
    KEY idx_ai_async_jobs_type_status (job_type, status, created_at)
);

ALTER TABLE ai_turns
    ADD COLUMN policy_snapshot_id VARCHAR(64) NULL,
    ADD COLUMN output_mode VARCHAR(32) NULL;
