ALTER TABLE operation_jobs
    ADD COLUMN progress_current INT NOT NULL DEFAULT 0 AFTER max_attempts,
    ADD COLUMN progress_total INT NOT NULL DEFAULT 0 AFTER progress_current,
    ADD COLUMN progress_message VARCHAR(255) NULL AFTER progress_total;
