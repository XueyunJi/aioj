ALTER TABLE contests
    ADD COLUMN deleted_at DATETIME(3) NULL,
    ADD COLUMN deleted_by BIGINT NULL,
    ADD CONSTRAINT fk_contests_deleted_by FOREIGN KEY (deleted_by) REFERENCES users(id),
    ADD INDEX idx_contests_deleted_status (deleted_at, status, updated_at);

ALTER TABLE contest_runs
    ADD COLUMN status_before_archive VARCHAR(32) NULL,
    ADD COLUMN deleted_at DATETIME(3) NULL,
    ADD COLUMN deleted_by BIGINT NULL,
    ADD COLUMN public_scoreboard_unfrozen_at DATETIME(3) NULL,
    ADD COLUMN public_scoreboard_unfrozen_by BIGINT NULL,
    ADD CONSTRAINT fk_contest_runs_deleted_by FOREIGN KEY (deleted_by) REFERENCES users(id),
    ADD CONSTRAINT fk_contest_runs_unfrozen_by FOREIGN KEY (public_scoreboard_unfrozen_by) REFERENCES users(id),
    ADD INDEX idx_contest_runs_deleted_status (contest_id, deleted_at, status, start_at),
    ADD INDEX idx_contest_runs_public_unfrozen (contest_id, public_scoreboard_unfrozen_at);

ALTER TABLE contest_resolver_sessions
    ADD COLUMN status_before_archive VARCHAR(32) NULL,
    ADD COLUMN deleted_at DATETIME(3) NULL,
    ADD COLUMN deleted_by BIGINT NULL,
    ADD CONSTRAINT fk_resolver_sessions_deleted_by FOREIGN KEY (deleted_by) REFERENCES users(id),
    ADD INDEX idx_resolver_sessions_deleted_status (contest_run_id, deleted_at, status, created_at);
