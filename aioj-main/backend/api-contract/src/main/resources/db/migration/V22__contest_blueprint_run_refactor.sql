ALTER TABLE contests
    MODIFY COLUMN scope_group_id BIGINT NULL,
    MODIFY COLUMN visibility VARCHAR(32) NULL,
    MODIFY COLUMN start_at DATETIME(3) NULL,
    MODIFY COLUMN end_at DATETIME(3) NULL,
    MODIFY COLUMN freeze_at DATETIME(3) NULL;

ALTER TABLE contest_runs
    ADD COLUMN registration_access VARCHAR(32) NOT NULL DEFAULT 'INVITE_ONLY',
    ADD COLUMN approval_required TINYINT(1) NOT NULL DEFAULT 0,
    ADD COLUMN contest_title_snapshot VARCHAR(160) NULL,
    ADD COLUMN contest_description_snapshot VARCHAR(2000) NULL,
    ADD COLUMN mode_snapshot VARCHAR(32) NULL,
    ADD COLUMN penalty_minutes_snapshot INT NULL,
    ADD COLUMN ce_penalty_snapshot TINYINT(1) NULL,
    ADD INDEX idx_contest_runs_access (contest_id, registration_access, approval_required);

UPDATE contest_runs
SET registration_access = CASE registration_policy
        WHEN 'PUBLIC_SELF_REGISTER' THEN 'PUBLIC'
        WHEN 'GROUP_SELF_REGISTER' THEN 'GROUPS'
        WHEN 'APPROVAL_REQUIRED' THEN 'PUBLIC'
        ELSE 'INVITE_ONLY'
    END,
    approval_required = CASE registration_policy
        WHEN 'APPROVAL_REQUIRED' THEN 1
        ELSE 0
    END;

CREATE TABLE IF NOT EXISTS contest_run_allowed_groups (
    id BIGINT PRIMARY KEY,
    contest_id BIGINT NOT NULL,
    contest_run_id BIGINT NOT NULL,
    group_id BIGINT NOT NULL,
    created_at DATETIME(3) NOT NULL,
    CONSTRAINT fk_run_allowed_groups_contest FOREIGN KEY (contest_id) REFERENCES contests(id),
    CONSTRAINT fk_run_allowed_groups_run FOREIGN KEY (contest_run_id) REFERENCES contest_runs(id),
    CONSTRAINT fk_run_allowed_groups_group FOREIGN KEY (group_id) REFERENCES learning_groups(id),
    UNIQUE KEY uk_run_allowed_groups_run_group (contest_run_id, group_id),
    INDEX idx_run_allowed_groups_contest (contest_id, contest_run_id),
    INDEX idx_run_allowed_groups_group (group_id, contest_run_id)
);
