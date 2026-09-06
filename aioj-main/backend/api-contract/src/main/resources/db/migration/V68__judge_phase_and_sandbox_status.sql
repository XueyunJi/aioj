ALTER TABLE submissions
    ADD COLUMN judge_phase VARCHAR(16) NULL AFTER status,
    ADD COLUMN sandbox_status VARCHAR(64) NULL AFTER judge_phase;

ALTER TABLE submission_case_results
    ADD COLUMN judge_phase VARCHAR(16) NULL AFTER status,
    ADD COLUMN sandbox_status VARCHAR(64) NULL AFTER judge_phase;

ALTER TABLE judge_audit_logs
    ADD COLUMN judge_phase VARCHAR(16) NULL AFTER to_status,
    ADD COLUMN sandbox_status VARCHAR(64) NULL AFTER judge_phase;
