CREATE INDEX idx_submissions_contest_run_visible_time
    ON submissions (contest_id, contest_run_id, visible_to_participant, submitted_at_contest_millis, id);
