ALTER TABLE ai_conversations
    ADD COLUMN deleted_at DATETIME(3) NULL;

ALTER TABLE ai_user_memories
    ADD COLUMN source VARCHAR(32) NOT NULL DEFAULT 'AI_EXTRACTED' AFTER confidence;
