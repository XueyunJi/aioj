CREATE TABLE IF NOT EXISTS contests (
    id BIGINT PRIMARY KEY,
    owner_user_id BIGINT NOT NULL,
    scope_group_id BIGINT NOT NULL,
    title VARCHAR(160) NOT NULL,
    description VARCHAR(2000),
    mode VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    visibility VARCHAR(32) NOT NULL,
    start_at DATETIME(3) NOT NULL,
    end_at DATETIME(3) NOT NULL,
    freeze_at DATETIME(3),
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    published_at DATETIME(3),
    archived_at DATETIME(3),
    CONSTRAINT fk_contests_owner FOREIGN KEY (owner_user_id) REFERENCES users(id),
    CONSTRAINT fk_contests_scope_group FOREIGN KEY (scope_group_id) REFERENCES learning_groups(id),
    INDEX idx_contests_owner_status (owner_user_id, status, start_at),
    INDEX idx_contests_scope_group_status (scope_group_id, status, start_at),
    INDEX idx_contests_status_time (status, start_at, end_at)
);

CREATE TABLE IF NOT EXISTS contest_problems (
    id BIGINT PRIMARY KEY,
    contest_id BIGINT NOT NULL,
    problem_id BIGINT NOT NULL,
    label VARCHAR(16) NOT NULL,
    display_title VARCHAR(160) NOT NULL,
    score INT NOT NULL DEFAULT 0,
    sort_order INT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    CONSTRAINT fk_contest_problems_contest FOREIGN KEY (contest_id) REFERENCES contests(id),
    CONSTRAINT fk_contest_problems_problem FOREIGN KEY (problem_id) REFERENCES problems(id),
    UNIQUE KEY uk_contest_problems_contest_problem (contest_id, problem_id),
    UNIQUE KEY uk_contest_problems_contest_label (contest_id, label),
    INDEX idx_contest_problems_contest_order (contest_id, sort_order)
);
