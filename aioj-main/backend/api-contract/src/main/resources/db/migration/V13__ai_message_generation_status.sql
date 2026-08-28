ALTER TABLE ai_messages
    ADD COLUMN status VARCHAR(24) NOT NULL DEFAULT 'COMPLETED',
    ADD COLUMN error_message VARCHAR(500) NULL,
    ADD COLUMN completed_at DATETIME(3) NULL,
    ADD COLUMN updated_at DATETIME(3) NULL;

UPDATE ai_messages
SET status = 'COMPLETED',
    completed_at = created_at,
    updated_at = created_at
WHERE completed_at IS NULL
   OR updated_at IS NULL;

CREATE INDEX idx_ai_messages_generation
    ON ai_messages (user_id, conversation_id, status, created_at);

CREATE INDEX idx_ai_messages_client_message
    ON ai_messages (user_id, client_message_id);
