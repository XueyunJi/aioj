CREATE TABLE IF NOT EXISTS testcase_packages (
    id BIGINT PRIMARY KEY,
    problem_id BIGINT NOT NULL,
    version VARCHAR(64) NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    file_size_bytes BIGINT NOT NULL,
    sha256 CHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    active TINYINT NOT NULL DEFAULT 0,
    storage_provider VARCHAR(32) NOT NULL,
    storage_key VARCHAR(512) NOT NULL,
    case_count INT NOT NULL DEFAULT 0,
    sample_count INT NOT NULL DEFAULT 0,
    manifest_json JSON,
    created_by BIGINT,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    activated_at DATETIME(3),
    error_message VARCHAR(1000),
    CONSTRAINT fk_testcase_packages_problem FOREIGN KEY (problem_id) REFERENCES problems(id),
    UNIQUE KEY uk_testcase_packages_problem_sha (problem_id, sha256),
    INDEX idx_testcase_packages_problem_active (problem_id, active),
    INDEX idx_testcase_packages_problem_status (problem_id, status)
);

CREATE TABLE IF NOT EXISTS testcase_package_cases (
    id BIGINT PRIMARY KEY,
    package_id BIGINT NOT NULL,
    name VARCHAR(160) NOT NULL,
    input_path VARCHAR(512) NOT NULL,
    output_path VARCHAR(512) NOT NULL,
    sample TINYINT NOT NULL DEFAULT 0,
    score INT,
    input_size_bytes BIGINT NOT NULL DEFAULT 0,
    output_size_bytes BIGINT NOT NULL DEFAULT 0,
    sort_order INT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL,
    CONSTRAINT fk_testcase_package_cases_package FOREIGN KEY (package_id) REFERENCES testcase_packages(id),
    INDEX idx_testcase_package_cases_package (package_id, sort_order)
);

CREATE TABLE IF NOT EXISTS testcase_upload_sessions (
    id VARCHAR(64) PRIMARY KEY,
    problem_id BIGINT NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    file_size_bytes BIGINT NOT NULL,
    sha256 CHAR(64) NOT NULL,
    chunk_size_bytes INT NOT NULL,
    total_chunks INT NOT NULL,
    uploaded_chunks INT NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL,
    temp_dir VARCHAR(512),
    package_id BIGINT,
    created_by BIGINT,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    expires_at DATETIME(3) NOT NULL,
    error_message VARCHAR(1000),
    CONSTRAINT fk_testcase_upload_sessions_problem FOREIGN KEY (problem_id) REFERENCES problems(id),
    CONSTRAINT fk_testcase_upload_sessions_package FOREIGN KEY (package_id) REFERENCES testcase_packages(id),
    INDEX idx_testcase_upload_sessions_problem_status (problem_id, status),
    INDEX idx_testcase_upload_sessions_expires (expires_at)
);

CREATE TABLE IF NOT EXISTS testcase_upload_chunks (
    upload_id VARCHAR(64) NOT NULL,
    chunk_index INT NOT NULL,
    chunk_size_bytes BIGINT NOT NULL,
    sha256 CHAR(64) NOT NULL,
    storage_path VARCHAR(512) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (upload_id, chunk_index),
    CONSTRAINT fk_testcase_upload_chunks_session FOREIGN KEY (upload_id) REFERENCES testcase_upload_sessions(id)
);
