CREATE TABLE ai_turns (
    id VARCHAR(64) PRIMARY KEY,
    conversation_id VARCHAR(64) NOT NULL,
    client_turn_id VARCHAR(80) NOT NULL,
    turn_seq BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    user_message_id VARCHAR(64) NULL,
    assistant_message_id VARCHAR(64) NULL,
    quota_reservation_id VARCHAR(64) NULL,
    provider_request_id VARCHAR(64) NULL,
    state_version BIGINT NOT NULL DEFAULT 0,
    context_snapshot_id VARCHAR(64) NULL,
    context_manifest_json JSON NULL,
    error_code VARCHAR(64) NULL,
    created_at DATETIME(3) NOT NULL,
    completed_at DATETIME(3) NULL,
    UNIQUE KEY uk_turn_client (conversation_id, client_turn_id),
    UNIQUE KEY uk_turn_seq (conversation_id, turn_seq),
    KEY idx_turn_status (status, created_at)
);

ALTER TABLE ai_conversation_problems
    ADD COLUMN set_id VARCHAR(64) NULL,
    ADD COLUMN set_ordinal INT NULL,
    ADD COLUMN conversation_ordinal INT NULL;

ALTER TABLE ai_conversation_task_states
    ADD COLUMN state_version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE ai_memory_claims
    ADD COLUMN volatility VARCHAR(16) NULL,
    ADD COLUMN review_after DATETIME(3) NULL,
    ADD COLUMN last_confirmed_at DATETIME(3) NULL;

ALTER TABLE ai_conversations
    ADD COLUMN current_snapshot_id VARCHAR(64) NULL;

UPDATE ai_conversations c
JOIN (
    SELECT conversation_id, MAX(id) AS snapshot_id
    FROM ai_code_snapshots
    WHERE is_latest = 1
    GROUP BY conversation_id
) latest ON latest.conversation_id = c.id
SET c.current_snapshot_id = latest.snapshot_id;
