ALTER TABLE ai_conversations
    ADD COLUMN source VARCHAR(32) NULL,
    ADD COLUMN mode VARCHAR(32) NULL,
    ADD COLUMN summary MEDIUMTEXT NULL,
    ADD COLUMN recent_problem_id BIGINT NULL,
    ADD COLUMN summary_updated_at DATETIME(3) NULL;

ALTER TABLE ai_messages
    ADD COLUMN problem_id BIGINT NULL,
    ADD COLUMN client_message_id VARCHAR(80) NULL,
    ADD COLUMN context_snapshot JSON NULL;

CREATE TABLE ai_conversation_problems (
    id BIGINT PRIMARY KEY,
    conversation_id VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    problem_id BIGINT NULL,
    title VARCHAR(160),
    difficulty VARCHAR(32),
    statement_snapshot MEDIUMTEXT,
    tags JSON,
    latest_language VARCHAR(32),
    latest_code MEDIUMTEXT,
    ai_solution_summary MEDIUMTEXT,
    user_followups_summary MEDIUMTEXT,
    last_active_at DATETIME(3) NOT NULL,
    INDEX idx_ai_conv_problem (conversation_id, last_active_at),
    INDEX idx_ai_user_problem (user_id, problem_id)
);

CREATE TABLE ai_user_memories (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    memory_type VARCHAR(48) NOT NULL,
    content VARCHAR(1000) NOT NULL,
    confidence DECIMAL(4,3) NOT NULL,
    source_conversation_id VARCHAR(64),
    source_message_id BIGINT,
    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    last_used_at DATETIME(3),
    INDEX idx_ai_memory_user_type (user_id, memory_type, status)
);

CREATE TABLE ai_retrieval_chunks (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    owner_type VARCHAR(40) NOT NULL,
    owner_id VARCHAR(80) NOT NULL,
    chunk_text MEDIUMTEXT NOT NULL,
    embedding_model VARCHAR(120),
    embedding_dimension INT,
    embedding_json MEDIUMTEXT,
    text_hash VARCHAR(64) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_ai_chunk_owner_hash (owner_type, owner_id, text_hash),
    INDEX idx_ai_chunk_user_type (user_id, owner_type, updated_at)
);
