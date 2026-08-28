CREATE TABLE ai_clarification_answers (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    conversation_id VARCHAR(64) NOT NULL,
    request_id BIGINT NULL,
    request_key VARCHAR(96) NULL,
    question MEDIUMTEXT NULL,
    answer_text MEDIUMTEXT NOT NULL,
    selected_option_ids_json JSON NULL,
    interpreted_delta_json JSON NULL,
    merged_to_state TINYINT(1) NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL,
    KEY idx_ai_clarification_answer_conversation (user_id, conversation_id, created_at),
    KEY idx_ai_clarification_answer_request (request_id, created_at),
    KEY idx_ai_clarification_answer_key (user_id, conversation_id, request_key, created_at)
);
