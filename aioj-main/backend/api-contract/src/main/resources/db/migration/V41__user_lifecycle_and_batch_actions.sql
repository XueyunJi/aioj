ALTER TABLE users
    ADD COLUMN archived_at DATETIME(3) NULL AFTER password_reset_required,
    ADD COLUMN deleted_at DATETIME(3) NULL AFTER archived_at,
    ADD COLUMN deleted_by BIGINT NULL AFTER deleted_at;

CREATE INDEX idx_users_lifecycle_created ON users (deleted_at, archived_at, created_at);

ALTER TABLE users
    ADD CONSTRAINT fk_users_deleted_by FOREIGN KEY (deleted_by) REFERENCES users(id);
