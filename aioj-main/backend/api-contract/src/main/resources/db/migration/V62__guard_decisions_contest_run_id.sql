-- Agent Core V3 P3-7: guard-decision audit query API (design doc §5.6).
-- ai_guard_decisions has no contest_run_id column; run-scoped filtering needs a
-- real indexed column. Backfill from the first matched_problem_refs entry's
-- contestRunId (JSON, camelCase keys produced by GuardDecisionRecorder).

ALTER TABLE ai_guard_decisions
    ADD COLUMN contest_run_id BIGINT NULL AFTER conversation_id,
    ADD KEY idx_guard_run_time (contest_run_id, created_at);

UPDATE ai_guard_decisions
SET contest_run_id = CAST(JSON_UNQUOTE(JSON_EXTRACT(matched_problem_refs, '$[0].contestRunId')) AS UNSIGNED)
WHERE matched_problem_refs IS NOT NULL
  AND JSON_VALID(matched_problem_refs)
  AND JSON_TYPE(matched_problem_refs) = 'ARRAY'
  AND JSON_LENGTH(matched_problem_refs) > 0
  AND JSON_EXTRACT(matched_problem_refs, '$[0].contestRunId') IS NOT NULL
  AND JSON_TYPE(JSON_EXTRACT(matched_problem_refs, '$[0].contestRunId')) = 'INTEGER';
