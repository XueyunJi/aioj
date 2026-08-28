CREATE TABLE contest_announcements (
    id BIGINT NOT NULL PRIMARY KEY,
    contest_id BIGINT NOT NULL,
    contest_run_id BIGINT NOT NULL,
    author_user_id BIGINT NOT NULL,
    title VARCHAR(160) NOT NULL,
    content MEDIUMTEXT NOT NULL,
    pinned TINYINT(1) NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL,
    published_at DATETIME(6) NOT NULL,
    archived_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    KEY idx_contest_announcements_run_status (contest_run_id, status, pinned, published_at),
    KEY idx_contest_announcements_contest_run (contest_id, contest_run_id),
    KEY idx_contest_announcements_author (author_user_id, created_at)
);

CREATE TABLE contest_clarifications (
    id BIGINT NOT NULL PRIMARY KEY,
    contest_id BIGINT NOT NULL,
    contest_run_id BIGINT NOT NULL,
    contest_problem_id BIGINT NULL,
    participant_id BIGINT NULL,
    user_id BIGINT NOT NULL,
    question TEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    answer MEDIUMTEXT NULL,
    answer_visibility VARCHAR(32) NULL,
    answered_by BIGINT NULL,
    answered_at DATETIME(6) NULL,
    closed_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    KEY idx_contest_clarifications_run_status (contest_run_id, status, created_at),
    KEY idx_contest_clarifications_user_run (user_id, contest_run_id, created_at),
    KEY idx_contest_clarifications_problem (contest_problem_id),
    KEY idx_contest_clarifications_answer_visibility (contest_run_id, answer_visibility, status)
);
