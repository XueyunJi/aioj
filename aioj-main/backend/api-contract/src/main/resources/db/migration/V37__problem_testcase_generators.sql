CREATE TABLE IF NOT EXISTS problem_testcase_generators (
    id BIGINT PRIMARY KEY,
    problem_id BIGINT NOT NULL,
    content MEDIUMTEXT NOT NULL,
    created_by BIGINT,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    CONSTRAINT fk_problem_testcase_generators_problem FOREIGN KEY (problem_id) REFERENCES problems(id),
    UNIQUE KEY uk_problem_testcase_generators_problem (problem_id)
);
