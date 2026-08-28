ALTER TABLE contest_runs
    ADD COLUMN registration_policy VARCHAR(32) NOT NULL DEFAULT 'INVITE_ONLY',
    ADD COLUMN registration_start_at DATETIME(3) NULL,
    ADD COLUMN registration_end_at DATETIME(3) NULL,
    ADD COLUMN max_participants INT NULL,
    ADD COLUMN archived_at DATETIME(3) NULL,
    ADD COLUMN archive_reason VARCHAR(500) NULL,
    ADD INDEX idx_contest_runs_registration (contest_id, registration_policy, registration_start_at, registration_end_at);

CREATE TABLE IF NOT EXISTS contest_registrations (
    id BIGINT PRIMARY KEY,
    contest_id BIGINT NOT NULL,
    contest_run_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    requested_at DATETIME(3) NOT NULL,
    reviewed_by BIGINT NULL,
    approved_at DATETIME(3) NULL,
    rejected_at DATETIME(3) NULL,
    cancelled_at DATETIME(3) NULL,
    reject_reason VARCHAR(500) NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    CONSTRAINT fk_contest_registrations_contest FOREIGN KEY (contest_id) REFERENCES contests(id),
    CONSTRAINT fk_contest_registrations_run FOREIGN KEY (contest_run_id) REFERENCES contest_runs(id),
    CONSTRAINT fk_contest_registrations_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_contest_registrations_reviewer FOREIGN KEY (reviewed_by) REFERENCES users(id),
    UNIQUE KEY uk_contest_registrations_run_user (contest_run_id, user_id),
    INDEX idx_contest_registrations_run_status (contest_run_id, status, requested_at),
    INDEX idx_contest_registrations_user (user_id, status, requested_at)
);

CREATE TABLE IF NOT EXISTS contest_run_problem_snapshots (
    id BIGINT PRIMARY KEY,
    contest_id BIGINT NOT NULL,
    contest_run_id BIGINT NOT NULL,
    contest_problem_id BIGINT NOT NULL,
    problem_id BIGINT NOT NULL,
    label VARCHAR(16) NOT NULL,
    display_title VARCHAR(160) NOT NULL,
    statement MEDIUMTEXT NOT NULL,
    notes MEDIUMTEXT NULL,
    tags VARCHAR(1000) NULL,
    difficulty VARCHAR(32) NULL,
    time_limit_millis INT NULL,
    memory_limit_kb INT NULL,
    score INT NOT NULL DEFAULT 0,
    sort_order INT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL,
    CONSTRAINT fk_run_problem_snapshots_contest FOREIGN KEY (contest_id) REFERENCES contests(id),
    CONSTRAINT fk_run_problem_snapshots_run FOREIGN KEY (contest_run_id) REFERENCES contest_runs(id),
    CONSTRAINT fk_run_problem_snapshots_contest_problem FOREIGN KEY (contest_problem_id) REFERENCES contest_problems(id),
    CONSTRAINT fk_run_problem_snapshots_problem FOREIGN KEY (problem_id) REFERENCES problems(id),
    UNIQUE KEY uk_run_problem_snapshot (contest_run_id, contest_problem_id),
    INDEX idx_run_problem_snapshots_run (contest_run_id, sort_order)
);

CREATE TABLE IF NOT EXISTS contest_scoreboard_timeline_ticks (
    id BIGINT PRIMARY KEY,
    contest_id BIGINT NOT NULL,
    contest_run_id BIGINT NOT NULL,
    view_type VARCHAR(32) NOT NULL,
    bucket_millis BIGINT NOT NULL,
    snapshot_id BIGINT NOT NULL,
    checksum VARCHAR(128) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    CONSTRAINT fk_timeline_ticks_contest FOREIGN KEY (contest_id) REFERENCES contests(id),
    CONSTRAINT fk_timeline_ticks_run FOREIGN KEY (contest_run_id) REFERENCES contest_runs(id),
    CONSTRAINT fk_timeline_ticks_snapshot FOREIGN KEY (snapshot_id) REFERENCES contest_scoreboard_snapshots(id),
    UNIQUE KEY uk_scoreboard_timeline_tick (contest_run_id, view_type, bucket_millis),
    INDEX idx_timeline_ticks_snapshot (snapshot_id)
);

ALTER TABLE contest_participants
    ADD COLUMN contest_run_id BIGINT NULL,
    ADD CONSTRAINT fk_contest_participants_run FOREIGN KEY (contest_run_id) REFERENCES contest_runs(id),
    DROP INDEX uk_contest_participants_contest_user,
    ADD UNIQUE KEY uk_contest_participants_run_user (contest_id, contest_run_id, user_id),
    ADD INDEX idx_contest_participants_run_status (contest_run_id, status, registered_at);

ALTER TABLE contest_participant_snapshots
    ADD COLUMN contest_run_id BIGINT NULL,
    ADD CONSTRAINT fk_contest_participant_snapshots_run FOREIGN KEY (contest_run_id) REFERENCES contest_runs(id),
    ADD INDEX idx_contest_participant_snapshots_run (contest_run_id, created_at);

ALTER TABLE submissions
    ADD COLUMN contest_run_id BIGINT NULL,
    ADD CONSTRAINT fk_submissions_contest_run FOREIGN KEY (contest_run_id) REFERENCES contest_runs(id),
    ADD INDEX idx_submissions_contest_run_created (contest_id, contest_run_id, created_at),
    ADD INDEX idx_submissions_contest_run_status (contest_id, contest_run_id, status, judged_at);

ALTER TABLE contest_scoreboard_snapshots
    ADD COLUMN contest_run_id BIGINT NULL,
    ADD CONSTRAINT fk_scoreboard_snapshots_run FOREIGN KEY (contest_run_id) REFERENCES contest_runs(id),
    ADD INDEX idx_scoreboard_snapshots_run_time (contest_run_id, view_type, contest_time_millis);

ALTER TABLE contest_scoreboard_rows
    ADD COLUMN contest_run_id BIGINT NULL,
    ADD CONSTRAINT fk_scoreboard_rows_run FOREIGN KEY (contest_run_id) REFERENCES contest_runs(id),
    ADD INDEX idx_scoreboard_rows_run_participant (contest_run_id, participant_id);

ALTER TABLE contest_scoreboard_cells
    ADD COLUMN contest_run_id BIGINT NULL,
    ADD CONSTRAINT fk_scoreboard_cells_run FOREIGN KEY (contest_run_id) REFERENCES contest_runs(id),
    ADD INDEX idx_scoreboard_cells_run_participant (contest_run_id, participant_id);

ALTER TABLE submission_code_access_logs
    ADD COLUMN contest_run_id BIGINT NULL,
    ADD CONSTRAINT fk_submission_code_access_logs_run FOREIGN KEY (contest_run_id) REFERENCES contest_runs(id),
    ADD INDEX idx_submission_code_access_logs_run (contest_run_id, created_at);

ALTER TABLE plagiarism_jobs
    ADD COLUMN contest_run_id BIGINT NULL,
    ADD CONSTRAINT fk_plagiarism_jobs_run FOREIGN KEY (contest_run_id) REFERENCES contest_runs(id),
    ADD INDEX idx_plagiarism_jobs_run_status (contest_run_id, status, created_at);
