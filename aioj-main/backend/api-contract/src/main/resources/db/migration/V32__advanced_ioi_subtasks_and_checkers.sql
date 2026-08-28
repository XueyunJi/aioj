ALTER TABLE testcase_packages
    ADD COLUMN checker_type VARCHAR(32) NOT NULL DEFAULT 'STANDARD',
    ADD COLUMN checker_language VARCHAR(32) NULL,
    ADD COLUMN checker_source_path VARCHAR(500) NULL,
    ADD COLUMN checker_protocol VARCHAR(32) NULL;

ALTER TABLE contest_run_problem_snapshots
    ADD COLUMN scoring_mode VARCHAR(64) NULL;

CREATE INDEX idx_problem_subtasks_package_sort
    ON problem_subtasks (testcase_package_id, sort_order, id);

CREATE INDEX idx_case_results_submission_subtask
    ON submission_case_results (submission_id, subtask_key, case_index);
