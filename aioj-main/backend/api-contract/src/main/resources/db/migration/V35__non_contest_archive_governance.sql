ALTER TABLE learning_groups
    ADD COLUMN deleted_at DATETIME(6) NULL AFTER archived_at,
    ADD COLUMN deleted_by BIGINT NULL AFTER deleted_at,
    ADD INDEX idx_learning_groups_deleted_at (deleted_at);

ALTER TABLE problems
    ADD COLUMN archived_at DATETIME(6) NULL AFTER updated_at,
    ADD COLUMN deleted_at DATETIME(6) NULL AFTER deleted,
    ADD COLUMN deleted_by BIGINT NULL AFTER deleted_at,
    ADD INDEX idx_problems_archive_deleted (archived_at, deleted_at),
    ADD INDEX idx_problems_deleted_at (deleted_at);

UPDATE problems
SET deleted_at = COALESCE(updated_at, created_at), deleted_by = created_by
WHERE deleted = 1 AND deleted_at IS NULL;

ALTER TABLE testcase_packages
    ADD COLUMN archived_at DATETIME(6) NULL AFTER activated_at,
    ADD COLUMN deleted_at DATETIME(6) NULL AFTER archived_at,
    ADD COLUMN deleted_by BIGINT NULL AFTER deleted_at,
    ADD INDEX idx_testcase_packages_archive_deleted (problem_id, archived_at, deleted_at);

ALTER TABLE problem_drafts
    ADD COLUMN archived_at DATETIME(6) NULL AFTER created_at,
    ADD COLUMN deleted_at DATETIME(6) NULL AFTER archived_at,
    ADD COLUMN deleted_by BIGINT NULL AFTER deleted_at,
    ADD INDEX idx_problem_drafts_archive_deleted (archived_at, deleted_at);
