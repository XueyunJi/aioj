ALTER TABLE ai_user_memories
    ADD COLUMN category VARCHAR(32) NOT NULL DEFAULT 'memory' AFTER user_id,
    ADD COLUMN title VARCHAR(160) NULL AFTER category,
    MODIFY COLUMN content MEDIUMTEXT NOT NULL;

ALTER TABLE ai_user_memories
    ADD INDEX idx_ai_memory_user_category_status (user_id, category, status);
