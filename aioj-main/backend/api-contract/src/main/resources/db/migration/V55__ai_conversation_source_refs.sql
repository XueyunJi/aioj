ALTER TABLE ai_conversations
    ADD COLUMN source_ref_type VARCHAR(32) NULL AFTER source,
    ADD COLUMN source_ref_id VARCHAR(64) NULL AFTER source_ref_type;

CREATE INDEX idx_ai_conversations_source_ref
    ON ai_conversations (user_id, source, source_ref_type, source_ref_id, updated_at);
