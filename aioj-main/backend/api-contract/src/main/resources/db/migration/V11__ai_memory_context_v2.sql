CREATE TABLE ai_memory_claims (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    legacy_memory_id BIGINT NULL,
    scope_type VARCHAR(32) NOT NULL DEFAULT 'GLOBAL',
    scope_id VARCHAR(128) NULL,
    category VARCHAR(32) NOT NULL,
    memory_key VARCHAR(96) NOT NULL,
    value_json JSON NULL,
    canonical_text MEDIUMTEXT NOT NULL,
    confidence DECIMAL(5,4) NOT NULL DEFAULT 0.0000,
    stability_score DECIMAL(5,4) NOT NULL DEFAULT 0.0000,
    support_count INT NOT NULL DEFAULT 0,
    contradiction_count INT NOT NULL DEFAULT 0,
    source_mode VARCHAR(32) NOT NULL DEFAULT 'AI_EXTRACTED',
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    sensitivity_level VARCHAR(32) NOT NULL DEFAULT 'LOW',
    ambiguity_level VARCHAR(32) NOT NULL DEFAULT 'LOW',
    first_seen_at DATETIME(3) NOT NULL,
    last_seen_at DATETIME(3) NOT NULL,
    last_used_at DATETIME(3) NULL,
    expires_at DATETIME(3) NULL,
    pinned TINYINT(1) NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 1,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_ai_memory_claim_key (user_id, scope_type, scope_id, category, memory_key),
    KEY idx_ai_memory_claim_user_status (user_id, status, category, updated_at),
    KEY idx_ai_memory_claim_legacy (legacy_memory_id)
);

CREATE TABLE ai_memory_candidates (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    category VARCHAR(32) NOT NULL,
    memory_key VARCHAR(96) NOT NULL,
    canonical_text MEDIUMTEXT NOT NULL,
    value_json JSON NULL,
    scope_type VARCHAR(32) NOT NULL DEFAULT 'GLOBAL',
    scope_id VARCHAR(128) NULL,
    evidence_type VARCHAR(48) NOT NULL,
    extraction_confidence DECIMAL(5,4) NOT NULL DEFAULT 0.0000,
    write_score DECIMAL(5,4) NOT NULL DEFAULT 0.0000,
    is_long_term TINYINT(1) NOT NULL DEFAULT 0,
    is_problem_specific TINYINT(1) NOT NULL DEFAULT 0,
    is_hypothetical TINYINT(1) NOT NULL DEFAULT 0,
    is_quoted TINYINT(1) NOT NULL DEFAULT 0,
    needs_confirmation TINYINT(1) NOT NULL DEFAULT 0,
    quality_flags JSON NULL,
    ambiguity_flags JSON NULL,
    source_conversation_id VARCHAR(64) NULL,
    source_message_id BIGINT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'CANDIDATE',
    rejected_reason VARCHAR(500) NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    KEY idx_ai_memory_candidate_user_status (user_id, status, created_at),
    KEY idx_ai_memory_candidate_source (user_id, source_conversation_id, source_message_id),
    KEY idx_ai_memory_candidate_key (user_id, category, memory_key)
);

CREATE TABLE ai_memory_evidence (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    claim_id BIGINT NULL,
    candidate_id BIGINT NULL,
    conversation_id VARCHAR(64) NULL,
    message_id BIGINT NULL,
    evidence_type VARCHAR(48) NOT NULL,
    evidence_text MEDIUMTEXT NOT NULL,
    confidence DECIMAL(5,4) NOT NULL DEFAULT 0.0000,
    reason VARCHAR(500) NULL,
    created_at DATETIME(3) NOT NULL,
    KEY idx_ai_memory_evidence_claim (claim_id, created_at),
    KEY idx_ai_memory_evidence_candidate (candidate_id, created_at),
    KEY idx_ai_memory_evidence_source (user_id, conversation_id, message_id)
);

CREATE TABLE ai_memory_versions (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    claim_id BIGINT NOT NULL,
    version INT NOT NULL,
    canonical_text MEDIUMTEXT NOT NULL,
    value_json JSON NULL,
    status VARCHAR(32) NOT NULL,
    change_reason VARCHAR(500) NULL,
    source_candidate_id BIGINT NULL,
    created_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_ai_memory_version (claim_id, version),
    KEY idx_ai_memory_version_user (user_id, claim_id, created_at)
);

CREATE TABLE ai_memory_recall_logs (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    conversation_id VARCHAR(64) NULL,
    message_id BIGINT NULL,
    claim_id BIGINT NULL,
    legacy_memory_id BIGINT NULL,
    recall_score DECIMAL(6,4) NOT NULL DEFAULT 0.0000,
    selected TINYINT(1) NOT NULL DEFAULT 0,
    used_in_prompt TINYINT(1) NOT NULL DEFAULT 0,
    reason_json JSON NULL,
    user_feedback VARCHAR(32) NULL,
    created_at DATETIME(3) NOT NULL,
    KEY idx_ai_memory_recall_user_message (user_id, conversation_id, message_id),
    KEY idx_ai_memory_recall_claim (claim_id, created_at),
    KEY idx_ai_memory_recall_legacy (legacy_memory_id, created_at)
);

CREATE TABLE ai_conversation_task_states (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    conversation_id VARCHAR(64) NOT NULL,
    current_problem_id BIGINT NULL,
    current_goal VARCHAR(64) NULL,
    language VARCHAR(32) NULL,
    latest_code_snapshot_id BIGINT NULL,
    latest_error_json JSON NULL,
    state_json JSON NOT NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_ai_conversation_state (user_id, conversation_id),
    KEY idx_ai_conversation_state_problem (user_id, current_problem_id)
);

CREATE TABLE ai_conversation_summaries (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    conversation_id VARCHAR(64) NOT NULL,
    summary_type VARCHAR(32) NOT NULL,
    narrative_summary MEDIUMTEXT NULL,
    structured_summary JSON NULL,
    message_start_id BIGINT NULL,
    message_end_id BIGINT NULL,
    token_estimate INT NOT NULL DEFAULT 0,
    embedding_owner_type VARCHAR(32) NULL,
    embedding_owner_id VARCHAR(64) NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    KEY idx_ai_summary_conversation (user_id, conversation_id, message_end_id),
    KEY idx_ai_summary_user_type (user_id, summary_type, updated_at)
);

CREATE TABLE ai_code_snapshots (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    conversation_id VARCHAR(64) NOT NULL,
    message_id BIGINT NOT NULL,
    language VARCHAR(32) NULL,
    code_hash VARCHAR(128) NOT NULL,
    code_text MEDIUMTEXT NOT NULL,
    code_summary MEDIUMTEXT NULL,
    is_latest TINYINT(1) NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL,
    KEY idx_ai_code_conversation (user_id, conversation_id, created_at),
    KEY idx_ai_code_hash (user_id, code_hash)
);

CREATE TABLE ai_clarification_requests (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    conversation_id VARCHAR(64) NOT NULL,
    source_message_id BIGINT NULL,
    request_key VARCHAR(96) NOT NULL,
    priority VARCHAR(32) NOT NULL,
    question MEDIUMTEXT NOT NULL,
    input_schema JSON NOT NULL,
    default_action VARCHAR(32) NULL,
    assumption MEDIUMTEXT NULL,
    status VARCHAR(32) NOT NULL,
    answer_json JSON NULL,
    created_at DATETIME(3) NOT NULL,
    answered_at DATETIME(3) NULL,
    KEY idx_ai_clarification_user_status (user_id, conversation_id, status, created_at)
);

CREATE TABLE ai_learning_weaknesses (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    knowledge_node VARCHAR(128) NOT NULL,
    symptom VARCHAR(128) NOT NULL DEFAULT '',
    tags JSON NULL,
    mastery_score DECIMAL(5,4) NOT NULL DEFAULT 0.5000,
    weak_signal_count INT NOT NULL DEFAULT 0,
    recent_error_count INT NOT NULL DEFAULT 0,
    teaching_boost DECIMAL(5,4) NOT NULL DEFAULT 1.0000,
    evidence_json JSON NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    last_evidence_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_ai_weakness_user_node (user_id, knowledge_node, symptom),
    KEY idx_ai_weakness_user_status (user_id, status, updated_at)
);
