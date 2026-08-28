-- Contest AI-assistance statistics for Agent Core V3.
-- These tables are additive. Legacy usage, conversation, message, and audit
-- tables remain the raw historical source and are intentionally not modified.

CREATE TABLE ai_contest_assistance_turns (
    id BIGINT PRIMARY KEY,
    turn_id VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    contest_id BIGINT NOT NULL,
    contest_run_id BIGINT NOT NULL,
    conversation_id VARCHAR(64) NOT NULL,
    terminal_status VARCHAR(32) NULL,
    intercept_type VARCHAR(48) NOT NULL DEFAULT 'NONE',
    intent_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    token_accounting_status VARCHAR(32) NOT NULL DEFAULT 'COMPLETE',
    started_at DATETIME(3) NOT NULL,
    completed_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_contest_assistance_turn (turn_id),
    KEY idx_contest_assistance_run_user_time (contest_id, contest_run_id, user_id, started_at),
    KEY idx_contest_assistance_conversation (conversation_id, started_at)
);

CREATE TABLE ai_contest_assistance_model_usages (
    id BIGINT PRIMARY KEY,
    assistance_turn_id BIGINT NOT NULL,
    turn_id VARCHAR(64) NOT NULL,
    usage_key VARCHAR(64) NOT NULL,
    usage_source VARCHAR(48) NOT NULL,
    provider VARCHAR(64) NULL,
    model VARCHAR(160) NULL,
    prompt_tokens BIGINT NOT NULL DEFAULT 0,
    completion_tokens BIGINT NOT NULL DEFAULT 0,
    usage_status VARCHAR(32) NOT NULL DEFAULT 'REPORTED',
    created_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_contest_assistance_usage (turn_id, usage_key),
    KEY idx_contest_assistance_usage_turn (assistance_turn_id),
    KEY idx_contest_assistance_usage_source (usage_source, created_at)
);

CREATE TABLE ai_contest_assistance_legacy_snapshots (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    contest_id BIGINT NOT NULL,
    contest_run_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    turn_count BIGINT NOT NULL DEFAULT 0,
    prompt_tokens BIGINT NOT NULL DEFAULT 0,
    completion_tokens BIGINT NOT NULL DEFAULT 0,
    conversation_count BIGINT NOT NULL DEFAULT 0,
    intercepted_count BIGINT NOT NULL DEFAULT 0,
    last_used_at DATETIME(3) NULL,
    snapshot_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_contest_assistance_legacy_snapshot (contest_id, contest_run_id, user_id),
    KEY idx_contest_assistance_legacy_user (contest_id, contest_run_id, user_id)
);

-- Historical usage records are only accepted when they carry concrete contest
-- and run attribution and fall in the original run's [start, end + 60s] window.
INSERT INTO ai_contest_assistance_legacy_snapshots (
    contest_id, contest_run_id, user_id, turn_count, prompt_tokens,
    completion_tokens, conversation_count, intercepted_count, last_used_at, snapshot_at
)
SELECT
    usage_record.contest_id,
    usage_record.contest_run_id,
    usage_record.user_id,
    COUNT(*),
    COALESCE(SUM(usage_record.prompt_tokens), 0),
    COALESCE(SUM(usage_record.completion_tokens), 0),
    0,
    0,
    MAX(usage_record.created_at),
    CURRENT_TIMESTAMP(3)
FROM ai_usage_records usage_record
JOIN contest_runs run ON run.id = usage_record.contest_run_id
WHERE usage_record.contest_id IS NOT NULL
  AND usage_record.contest_run_id IS NOT NULL
  AND usage_record.created_at >= run.start_at
  AND usage_record.created_at <= DATE_ADD(run.end_at, INTERVAL 60 SECOND)
GROUP BY usage_record.contest_id, usage_record.contest_run_id, usage_record.user_id;

-- A pre-V3 guard may have refused a request before it produced a usage row.
-- Retain these students as historical estimates rather than silently dropping
-- their intercepted attempts from the archive.
INSERT IGNORE INTO ai_contest_assistance_legacy_snapshots (
    contest_id, contest_run_id, user_id, turn_count, prompt_tokens,
    completion_tokens, conversation_count, intercepted_count, last_used_at, snapshot_at
)
SELECT
    event.contest_id,
    event.contest_run_id,
    event.actor_user_id,
    0,
    0,
    0,
    0,
    0,
    MAX(event.created_at),
    CURRENT_TIMESTAMP(3)
FROM operation_audit_events event
JOIN contest_runs run ON run.id = event.contest_run_id
WHERE event.contest_id IS NOT NULL
  AND event.contest_run_id IS NOT NULL
  AND event.created_at >= run.start_at
  AND event.created_at <= DATE_ADD(run.end_at, INTERVAL 60 SECOND)
  AND (
      event.action IN (
          'AI_CONTEST_REQUEST_BLOCKED',
          'AI_CONTEST_LEAK_BLOCKED',
          'AI_CONTEST_LEAK_PARTICIPANT_BLOCKED',
          'AI_CONTEST_RESPONSE_REPLACED'
      )
      OR (event.action = 'AI_CONTEST_GUARD_EVALUATED' AND event.status = 'REFUSE')
  )
GROUP BY event.contest_id, event.contest_run_id, event.actor_user_id;

-- The old conversation binding is only a historical approximation. Count a
-- conversation only when it has at least one message in the same run window.
UPDATE ai_contest_assistance_legacy_snapshots snapshot
JOIN contest_runs run ON run.id = snapshot.contest_run_id
SET snapshot.conversation_count = (
    SELECT COUNT(DISTINCT conversation.id)
    FROM ai_conversations conversation
    JOIN ai_messages message ON message.conversation_id = conversation.id
    WHERE conversation.user_id = snapshot.user_id
      AND conversation.contest_id = snapshot.contest_id
      AND conversation.contest_run_id = snapshot.contest_run_id
      AND message.created_at >= run.start_at
      AND message.created_at <= DATE_ADD(run.end_at, INTERVAL 60 SECOND)
);

-- A trace id groups lifecycle audit events of the same old turn when available;
-- event id is the safe fallback when the historical event has no correlation id.
UPDATE ai_contest_assistance_legacy_snapshots snapshot
JOIN contest_runs run ON run.id = snapshot.contest_run_id
SET snapshot.intercepted_count = (
    SELECT COUNT(DISTINCT COALESCE(NULLIF(event.trace_id, ''), CONCAT('event:', event.id)))
    FROM operation_audit_events event
    WHERE event.contest_id = snapshot.contest_id
      AND event.contest_run_id = snapshot.contest_run_id
      AND event.actor_user_id = snapshot.user_id
      AND event.created_at >= run.start_at
      AND event.created_at <= DATE_ADD(run.end_at, INTERVAL 60 SECOND)
      AND (
          event.action IN (
              'AI_CONTEST_REQUEST_BLOCKED',
              'AI_CONTEST_LEAK_BLOCKED',
              'AI_CONTEST_LEAK_PARTICIPANT_BLOCKED',
              'AI_CONTEST_RESPONSE_REPLACED'
          )
          OR (event.action = 'AI_CONTEST_GUARD_EVALUATED' AND event.status = 'REFUSE')
      )
);
