-- Repair early contest participant uniqueness for run-scoped contest operations.
-- Some databases may still carry the pre-run unique key from V16, which prevents
-- inviting the same student to separate runs of the same contest.
SET @old_participant_unique_exists := (
    SELECT COUNT(1)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'contest_participants'
      AND index_name = 'uk_contest_participants_contest_user'
);

SET @drop_old_participant_unique_sql := IF(
    @old_participant_unique_exists > 0,
    'ALTER TABLE contest_participants DROP INDEX uk_contest_participants_contest_user',
    'SELECT 1'
);

PREPARE drop_old_participant_unique_stmt FROM @drop_old_participant_unique_sql;
EXECUTE drop_old_participant_unique_stmt;
DEALLOCATE PREPARE drop_old_participant_unique_stmt;

SET @run_participant_unique_exists := (
    SELECT COUNT(1)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'contest_participants'
      AND index_name = 'uk_contest_participants_run_user'
);

SET @add_run_participant_unique_sql := IF(
    @run_participant_unique_exists = 0,
    'ALTER TABLE contest_participants ADD UNIQUE KEY uk_contest_participants_run_user (contest_id, contest_run_id, user_id)',
    'SELECT 1'
);

PREPARE add_run_participant_unique_stmt FROM @add_run_participant_unique_sql;
EXECUTE add_run_participant_unique_stmt;
DEALLOCATE PREPARE add_run_participant_unique_stmt;
