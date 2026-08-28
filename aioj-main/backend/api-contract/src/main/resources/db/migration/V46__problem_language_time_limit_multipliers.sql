ALTER TABLE problems
    ADD COLUMN cpp_time_limit_multiplier DECIMAL(4,2) NOT NULL DEFAULT 1.00 AFTER time_limit_millis,
    ADD COLUMN python_time_limit_multiplier DECIMAL(4,2) NOT NULL DEFAULT 1.00 AFTER cpp_time_limit_multiplier,
    ADD COLUMN java_time_limit_multiplier DECIMAL(4,2) NOT NULL DEFAULT 1.00 AFTER python_time_limit_multiplier;

CREATE INDEX idx_problem_solutions_problem_language ON problem_solutions (problem_id, language);
