ALTER TABLE ai_learning_weaknesses
    MODIFY knowledge_node VARCHAR(128) NOT NULL,
    MODIFY symptom VARCHAR(500) NOT NULL DEFAULT '';
