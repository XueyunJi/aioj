ALTER TABLE problem_drafts
    ADD COLUMN verification_status VARCHAR(32) NOT NULL DEFAULT 'NOT_RUN' AFTER validation_errors,
    ADD COLUMN verification_report_json TEXT NULL AFTER verification_status,
    ADD COLUMN repair_attempt_count INT NOT NULL DEFAULT 0 AFTER verification_report_json,
    ADD COLUMN last_repair_reason VARCHAR(1000) NULL AFTER repair_attempt_count;
