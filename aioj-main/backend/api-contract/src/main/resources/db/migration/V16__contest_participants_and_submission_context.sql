CREATE TABLE IF NOT EXISTS contest_runs (
    id BIGINT PRIMARY KEY,
    contest_id BIGINT NOT NULL,
    run_kind VARCHAR(32) NOT NULL,
    title VARCHAR(160) NOT NULL,
    status VARCHAR(32) NOT NULL,
    start_at DATETIME(3) NOT NULL,
    end_at DATETIME(3) NOT NULL,
    freeze_at DATETIME(3),
    source_run_id BIGINT,
    created_by BIGINT NOT NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    CONSTRAINT fk_contest_runs_contest FOREIGN KEY (contest_id) REFERENCES contests(id),
    CONSTRAINT fk_contest_runs_source FOREIGN KEY (source_run_id) REFERENCES contest_runs(id),
    CONSTRAINT fk_contest_runs_creator FOREIGN KEY (created_by) REFERENCES users(id),
    INDEX idx_contest_runs_contest (contest_id, status, start_at),
    INDEX idx_contest_runs_creator (created_by, created_at)
);

CREATE TABLE IF NOT EXISTS contest_participants (
    id BIGINT PRIMARY KEY,
    contest_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    participant_type VARCHAR(32) NOT NULL DEFAULT 'INDIVIDUAL',
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    account_snapshot VARCHAR(80) NOT NULL,
    display_name_snapshot VARCHAR(120) NOT NULL,
    email_snapshot VARCHAR(160),
    scope_group_id BIGINT,
    group_name_snapshot VARCHAR(160),
    registered_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    CONSTRAINT fk_contest_participants_contest FOREIGN KEY (contest_id) REFERENCES contests(id),
    CONSTRAINT fk_contest_participants_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_contest_participants_group FOREIGN KEY (scope_group_id) REFERENCES learning_groups(id),
    UNIQUE KEY uk_contest_participants_contest_user (contest_id, user_id),
    INDEX idx_contest_participants_contest_status (contest_id, status, registered_at),
    INDEX idx_contest_participants_user (user_id, contest_id, status)
);

CREATE TABLE IF NOT EXISTS contest_team_members (
    id BIGINT PRIMARY KEY,
    participant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    role VARCHAR(32) NOT NULL,
    account_snapshot VARCHAR(80) NOT NULL,
    display_name_snapshot VARCHAR(120) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    CONSTRAINT fk_contest_team_members_participant FOREIGN KEY (participant_id) REFERENCES contest_participants(id),
    CONSTRAINT fk_contest_team_members_user FOREIGN KEY (user_id) REFERENCES users(id),
    UNIQUE KEY uk_contest_team_members_participant_user (participant_id, user_id),
    INDEX idx_contest_team_members_user (user_id, participant_id)
);

CREATE TABLE IF NOT EXISTS contest_participant_snapshots (
    id BIGINT PRIMARY KEY,
    contest_id BIGINT NOT NULL,
    participant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    account_snapshot VARCHAR(80) NOT NULL,
    display_name_snapshot VARCHAR(120) NOT NULL,
    email_snapshot VARCHAR(160),
    scope_group_id BIGINT,
    group_name_snapshot VARCHAR(160),
    participant_status VARCHAR(32) NOT NULL,
    snapshot_reason VARCHAR(64) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    CONSTRAINT fk_contest_participant_snapshots_contest FOREIGN KEY (contest_id) REFERENCES contests(id),
    CONSTRAINT fk_contest_participant_snapshots_participant FOREIGN KEY (participant_id) REFERENCES contest_participants(id),
    CONSTRAINT fk_contest_participant_snapshots_user FOREIGN KEY (user_id) REFERENCES users(id),
    INDEX idx_contest_participant_snapshots_contest (contest_id, created_at),
    INDEX idx_contest_participant_snapshots_participant (participant_id, created_at)
);

CREATE TABLE IF NOT EXISTS contest_problem_reveal_rules (
    id BIGINT PRIMARY KEY,
    contest_id BIGINT NOT NULL,
    contest_problem_id BIGINT,
    policy VARCHAR(32) NOT NULL,
    reveal_at DATETIME(3),
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    CONSTRAINT fk_contest_problem_reveal_rules_contest FOREIGN KEY (contest_id) REFERENCES contests(id),
    CONSTRAINT fk_contest_problem_reveal_rules_problem FOREIGN KEY (contest_problem_id) REFERENCES contest_problems(id),
    UNIQUE KEY uk_contest_problem_reveal_rules_scope (contest_id, contest_problem_id),
    INDEX idx_contest_problem_reveal_rules_contest (contest_id, policy)
);

ALTER TABLE submissions
    ADD COLUMN contest_id BIGINT NULL,
    ADD COLUMN contest_problem_id BIGINT NULL,
    ADD COLUMN contest_participant_id BIGINT NULL,
    ADD COLUMN submitted_at_contest_millis BIGINT NULL,
    ADD COLUMN visible_to_participant TINYINT(1) NOT NULL DEFAULT 1,
    ADD CONSTRAINT fk_submissions_contest FOREIGN KEY (contest_id) REFERENCES contests(id),
    ADD CONSTRAINT fk_submissions_contest_problem FOREIGN KEY (contest_problem_id) REFERENCES contest_problems(id),
    ADD CONSTRAINT fk_submissions_contest_participant FOREIGN KEY (contest_participant_id) REFERENCES contest_participants(id);

CREATE INDEX idx_submissions_contest_created
    ON submissions (contest_id, created_at);

CREATE INDEX idx_submissions_contest_participant
    ON submissions (contest_id, contest_participant_id, created_at);

CREATE INDEX idx_submissions_contest_problem
    ON submissions (contest_id, contest_problem_id, created_at);
