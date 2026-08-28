ALTER TABLE contests
    ADD COLUMN penalty_minutes INT NOT NULL DEFAULT 20,
    ADD COLUMN ce_penalty TINYINT(1) NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS contest_scoreboard_snapshots (
    id BIGINT PRIMARY KEY,
    contest_id BIGINT NOT NULL,
    snapshot_kind VARCHAR(32) NOT NULL,
    view_type VARCHAR(32) NOT NULL,
    snapshot_at DATETIME(3) NOT NULL,
    contest_time_millis BIGINT NOT NULL,
    scoring_version INT NOT NULL,
    frozen TINYINT(1) NOT NULL DEFAULT 0,
    rows_json MEDIUMTEXT NOT NULL,
    checksum VARCHAR(128) NOT NULL,
    created_by BIGINT NULL,
    created_at DATETIME(3) NOT NULL,
    CONSTRAINT fk_scoreboard_snapshots_contest FOREIGN KEY (contest_id) REFERENCES contests(id),
    CONSTRAINT fk_scoreboard_snapshots_creator FOREIGN KEY (created_by) REFERENCES users(id),
    INDEX idx_scoreboard_snapshots_contest_time (contest_id, contest_time_millis),
    INDEX idx_scoreboard_snapshots_kind (contest_id, snapshot_kind, created_at)
);

CREATE TABLE IF NOT EXISTS contest_scoreboard_rows (
    id BIGINT PRIMARY KEY,
    snapshot_id BIGINT NOT NULL,
    contest_id BIGINT NOT NULL,
    participant_id BIGINT NOT NULL,
    rank_no INT NOT NULL,
    account_snapshot VARCHAR(80) NOT NULL,
    display_name_snapshot VARCHAR(120) NOT NULL,
    solved_count INT NOT NULL,
    penalty_minutes INT NOT NULL,
    last_accepted_at_millis BIGINT NULL,
    created_at DATETIME(3) NOT NULL,
    CONSTRAINT fk_scoreboard_rows_snapshot FOREIGN KEY (snapshot_id) REFERENCES contest_scoreboard_snapshots(id),
    CONSTRAINT fk_scoreboard_rows_contest FOREIGN KEY (contest_id) REFERENCES contests(id),
    CONSTRAINT fk_scoreboard_rows_participant FOREIGN KEY (participant_id) REFERENCES contest_participants(id),
    INDEX idx_scoreboard_rows_snapshot_rank (snapshot_id, rank_no),
    INDEX idx_scoreboard_rows_contest_participant (contest_id, participant_id)
);

CREATE TABLE IF NOT EXISTS contest_scoreboard_cells (
    id BIGINT PRIMARY KEY,
    snapshot_id BIGINT NOT NULL,
    row_id BIGINT NOT NULL,
    contest_id BIGINT NOT NULL,
    participant_id BIGINT NOT NULL,
    contest_problem_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    attempts INT NOT NULL,
    wrong_attempts INT NOT NULL,
    pending_attempts INT NOT NULL,
    accepted_at_millis BIGINT NULL,
    penalty_minutes INT NOT NULL,
    created_at DATETIME(3) NOT NULL,
    CONSTRAINT fk_scoreboard_cells_snapshot FOREIGN KEY (snapshot_id) REFERENCES contest_scoreboard_snapshots(id),
    CONSTRAINT fk_scoreboard_cells_row FOREIGN KEY (row_id) REFERENCES contest_scoreboard_rows(id),
    CONSTRAINT fk_scoreboard_cells_contest FOREIGN KEY (contest_id) REFERENCES contests(id),
    CONSTRAINT fk_scoreboard_cells_participant FOREIGN KEY (participant_id) REFERENCES contest_participants(id),
    CONSTRAINT fk_scoreboard_cells_problem FOREIGN KEY (contest_problem_id) REFERENCES contest_problems(id),
    INDEX idx_scoreboard_cells_snapshot_problem (snapshot_id, contest_problem_id),
    INDEX idx_scoreboard_cells_contest_participant (contest_id, participant_id)
);

CREATE TABLE IF NOT EXISTS contest_score_adjustments (
    id BIGINT PRIMARY KEY,
    contest_id BIGINT NOT NULL,
    participant_id BIGINT NOT NULL,
    problem_id BIGINT NULL,
    adjustment_type VARCHAR(32) NOT NULL,
    submission_id BIGINT NULL,
    value_json TEXT NOT NULL,
    reason TEXT NOT NULL,
    created_by BIGINT NOT NULL,
    created_at DATETIME(3) NOT NULL,
    CONSTRAINT fk_score_adjustments_contest FOREIGN KEY (contest_id) REFERENCES contests(id),
    CONSTRAINT fk_score_adjustments_participant FOREIGN KEY (participant_id) REFERENCES contest_participants(id),
    CONSTRAINT fk_score_adjustments_problem FOREIGN KEY (problem_id) REFERENCES contest_problems(id),
    CONSTRAINT fk_score_adjustments_submission FOREIGN KEY (submission_id) REFERENCES submissions(id),
    CONSTRAINT fk_score_adjustments_creator FOREIGN KEY (created_by) REFERENCES users(id),
    INDEX idx_score_adjustments_contest (contest_id, participant_id)
);
