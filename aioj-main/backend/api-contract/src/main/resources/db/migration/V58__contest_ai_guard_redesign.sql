-- Contest AI guard redesign: run-scoped AI policy snapshots and problem
-- visibility snapshots.
-- 1) Problem statements/visibility for the AI guard are read from run problem
--    snapshots instead of the live problems table, so later problem edits do
--    not change what a published run enforces.
-- 2) AI policy mode/notes are configured on the contest blueprint and copied
--    into contest_runs snapshot columns at publish time (same pattern as
--    mode_snapshot / penalty_minutes_snapshot).
ALTER TABLE contest_run_problem_snapshots
    ADD COLUMN visibility VARCHAR(32) NULL;

UPDATE contest_run_problem_snapshots s
    JOIN problems p ON s.problem_id = p.id
    SET s.visibility = p.visibility;

ALTER TABLE contests
    ADD COLUMN ai_policy_mode VARCHAR(32) NOT NULL DEFAULT 'DEFAULT',
    ADD COLUMN ai_policy_notes VARCHAR(2000) NULL;

ALTER TABLE contest_runs
    ADD COLUMN ai_policy_mode_snapshot VARCHAR(32) NULL,
    ADD COLUMN ai_policy_notes_snapshot VARCHAR(2000) NULL;
