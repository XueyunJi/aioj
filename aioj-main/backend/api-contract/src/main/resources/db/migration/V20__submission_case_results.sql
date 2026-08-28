ALTER TABLE submissions
    ADD COLUMN score DECIMAL(8,3) NULL,
    ADD COLUMN max_score DECIMAL(8,3) NULL;

ALTER TABLE testcase_package_cases
    ADD COLUMN subtask_key VARCHAR(64) NULL;

ALTER TABLE contest_scoreboard_rows
    ADD COLUMN total_score DECIMAL(8,3) NULL,
    ADD COLUMN last_score_improved_at_millis BIGINT NULL;

ALTER TABLE contest_scoreboard_cells
    ADD COLUMN score DECIMAL(8,3) NULL,
    ADD COLUMN max_score DECIMAL(8,3) NULL,
    ADD COLUMN best_submission_id BIGINT NULL,
    ADD COLUMN last_score_improved_at_millis BIGINT NULL,
    ADD CONSTRAINT fk_scoreboard_cells_best_submission FOREIGN KEY (best_submission_id) REFERENCES submissions(id);

CREATE TABLE IF NOT EXISTS problem_subtasks (
    id BIGINT PRIMARY KEY,
    problem_id BIGINT NOT NULL,
    testcase_package_id BIGINT NULL,
    subtask_key VARCHAR(64) NOT NULL,
    title VARCHAR(160) NULL,
    score DECIMAL(8,3) NOT NULL DEFAULT 0,
    sort_order INT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL,
    CONSTRAINT fk_problem_subtasks_problem FOREIGN KEY (problem_id) REFERENCES problems(id),
    CONSTRAINT fk_problem_subtasks_package FOREIGN KEY (testcase_package_id) REFERENCES testcase_packages(id),
    UNIQUE KEY uk_problem_subtasks_package_key (testcase_package_id, subtask_key),
    INDEX idx_problem_subtasks_problem (problem_id, sort_order)
);

CREATE TABLE IF NOT EXISTS contest_problem_scoring_rules (
    id BIGINT PRIMARY KEY,
    contest_id BIGINT NOT NULL,
    contest_problem_id BIGINT NOT NULL,
    scoring_mode VARCHAR(64) NOT NULL,
    max_score DECIMAL(8,3) NULL,
    tie_break VARCHAR(64) NOT NULL DEFAULT 'LAST_IMPROVEMENT_ASC',
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    CONSTRAINT fk_contest_problem_scoring_rules_contest FOREIGN KEY (contest_id) REFERENCES contests(id),
    CONSTRAINT fk_contest_problem_scoring_rules_problem FOREIGN KEY (contest_problem_id) REFERENCES contest_problems(id),
    UNIQUE KEY uk_contest_problem_scoring_rules_problem (contest_problem_id),
    INDEX idx_contest_problem_scoring_rules_contest (contest_id)
);

CREATE TABLE IF NOT EXISTS submission_case_results (
    id BIGINT PRIMARY KEY,
    submission_id BIGINT NOT NULL,
    contest_id BIGINT NULL,
    contest_problem_id BIGINT NULL,
    contest_participant_id BIGINT NULL,
    testcase_package_id BIGINT NULL,
    case_id BIGINT NULL,
    case_index INT NOT NULL DEFAULT 0,
    case_name VARCHAR(160) NULL,
    subtask_key VARCHAR(64) NULL,
    status VARCHAR(32) NOT NULL,
    score DECIMAL(8,3) NOT NULL DEFAULT 0,
    max_score DECIMAL(8,3) NOT NULL DEFAULT 0,
    time_millis BIGINT NULL,
    memory_kb BIGINT NULL,
    message VARCHAR(512) NULL,
    created_at DATETIME(3) NOT NULL,
    CONSTRAINT fk_submission_case_results_submission FOREIGN KEY (submission_id) REFERENCES submissions(id),
    CONSTRAINT fk_submission_case_results_contest FOREIGN KEY (contest_id) REFERENCES contests(id),
    CONSTRAINT fk_submission_case_results_contest_problem FOREIGN KEY (contest_problem_id) REFERENCES contest_problems(id),
    CONSTRAINT fk_submission_case_results_participant FOREIGN KEY (contest_participant_id) REFERENCES contest_participants(id),
    CONSTRAINT fk_submission_case_results_package FOREIGN KEY (testcase_package_id) REFERENCES testcase_packages(id),
    CONSTRAINT fk_submission_case_results_case FOREIGN KEY (case_id) REFERENCES testcase_package_cases(id),
    INDEX idx_case_results_submission (submission_id, case_index),
    INDEX idx_case_results_contest_problem (contest_id, contest_problem_id, contest_participant_id),
    INDEX idx_case_results_subtask (submission_id, subtask_key)
);
