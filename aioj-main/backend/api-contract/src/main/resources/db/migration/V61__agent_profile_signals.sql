-- Agent Core V3 P2: learning-profile signal layer (design doc §6.6/§7.2).
-- Raw signals from judged submissions (JUDGED_SUBMISSION) and curated chat turns
-- (CHAT_TURN) land here as PENDING; the PROFILE_AGGREGATE async job folds them
-- into ai_learning_profile. Signals are evidence, never shown to the model raw.
CREATE TABLE ai_profile_signals (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    signal_type VARCHAR(48) NOT NULL,
    knowledge_node VARCHAR(128) NULL,
    polarity VARCHAR(16) NOT NULL,
    score DECIMAL(6,4) NULL,
    source_type VARCHAR(48) NOT NULL,
    source_id VARCHAR(128) NULL,
    payload_json JSON NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    created_at DATETIME(3) NOT NULL,
    KEY idx_profile_signal_user (user_id, knowledge_node, created_at),
    KEY idx_profile_signal_status (status, created_at)
);
