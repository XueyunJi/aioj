CREATE TABLE auth_handoff_tickets (
    id BIGINT PRIMARY KEY,
    ticket_hash CHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    audience VARCHAR(32) NOT NULL,
    next_path VARCHAR(255) NOT NULL,
    expires_at DATETIME(3) NOT NULL,
    consumed_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_auth_handoff_ticket_hash (ticket_hash),
    KEY idx_auth_handoff_user_created (user_id, created_at),
    KEY idx_auth_handoff_expires (expires_at)
);
