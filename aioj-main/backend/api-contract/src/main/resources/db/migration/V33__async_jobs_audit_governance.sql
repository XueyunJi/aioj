CREATE TABLE IF NOT EXISTS operation_jobs (
    id BIGINT PRIMARY KEY,
    job_type VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    resource_type VARCHAR(64) NULL,
    resource_id BIGINT NULL,
    contest_id BIGINT NULL,
    contest_run_id BIGINT NULL,
    requested_by BIGINT NOT NULL,
    request_json JSON NULL,
    result_json JSON NULL,
    error_message VARCHAR(1000) NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    max_attempts INT NOT NULL DEFAULT 3,
    lease_owner VARCHAR(128) NULL,
    lease_expires_at DATETIME(6) NULL,
    started_at DATETIME(6) NULL,
    completed_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    INDEX idx_operation_jobs_status (status, created_at),
    INDEX idx_operation_jobs_requested_by (requested_by, created_at),
    INDEX idx_operation_jobs_contest_run (contest_id, contest_run_id, created_at),
    INDEX idx_operation_jobs_resource (resource_type, resource_id)
);

CREATE TABLE IF NOT EXISTS operation_job_artifacts (
    id BIGINT PRIMARY KEY,
    job_id BIGINT NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    content_type VARCHAR(128) NOT NULL,
    storage_provider VARCHAR(32) NOT NULL,
    storage_key VARCHAR(512) NOT NULL,
    byte_size BIGINT NOT NULL,
    sha256 VARCHAR(64) NULL,
    expires_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    INDEX idx_operation_job_artifacts_job (job_id)
);

CREATE TABLE IF NOT EXISTS operation_audit_events (
    id BIGINT PRIMARY KEY,
    actor_user_id BIGINT NOT NULL,
    action VARCHAR(96) NOT NULL,
    resource_type VARCHAR(64) NOT NULL,
    resource_id BIGINT NULL,
    contest_id BIGINT NULL,
    contest_run_id BIGINT NULL,
    target_user_id BIGINT NULL,
    status VARCHAR(32) NOT NULL,
    trace_id VARCHAR(64) NULL,
    summary_json JSON NULL,
    created_at DATETIME(6) NOT NULL,
    INDEX idx_operation_audit_actor (actor_user_id, created_at),
    INDEX idx_operation_audit_action (action, created_at),
    INDEX idx_operation_audit_resource (resource_type, resource_id, created_at),
    INDEX idx_operation_audit_contest_run (contest_id, contest_run_id, created_at),
    INDEX idx_operation_audit_target (target_user_id, created_at)
);
