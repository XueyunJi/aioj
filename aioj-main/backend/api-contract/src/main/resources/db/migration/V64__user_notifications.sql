-- Durable, user-scoped notification facts. Payload is deliberately optional:
-- clients receive only IDs/types through SSE and fetch authorized details via REST.
CREATE TABLE user_notifications (
    id BIGINT PRIMARY KEY,
    recipient_user_id BIGINT NOT NULL,
    notification_type VARCHAR(64) NOT NULL,
    subject_type VARCHAR(64) NOT NULL,
    subject_id VARCHAR(128) NOT NULL,
    scope_type VARCHAR(64) NULL,
    scope_id VARCHAR(128) NULL,
    payload_json JSON NULL,
    deduplication_key VARCHAR(192) NOT NULL,
    read_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_user_notification_deduplication (recipient_user_id, deduplication_key),
    KEY idx_user_notification_unread (recipient_user_id, notification_type, read_at, created_at),
    KEY idx_user_notification_subject (recipient_user_id, subject_type, subject_id, created_at),
    KEY idx_user_notification_scope (recipient_user_id, scope_type, scope_id, created_at)
);
