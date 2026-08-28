ALTER TABLE submissions
    ADD COLUMN stdout_excerpt TEXT NULL COMMENT 'Truncated stdout from sandbox (max 8KB)',
    ADD COLUMN stderr_excerpt TEXT NULL COMMENT 'Truncated stderr from sandbox (max 8KB)',
    ADD COLUMN exit_status INT NULL COMMENT 'Exit code; for Signalled status this is the signal number',
    ADD COLUMN run_time_millis BIGINT NULL COMMENT 'Wall clock time in milliseconds';

ALTER TABLE judge_audit_logs
    ADD COLUMN sandbox_run_id VARCHAR(64) NULL COMMENT 'go-judge run identifier if applicable',
    ADD COLUMN signal_value INT NULL COMMENT 'POSIX signal value for Signalled status';
