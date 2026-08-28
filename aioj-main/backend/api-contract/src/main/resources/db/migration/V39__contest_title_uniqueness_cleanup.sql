-- Enforce active contest/run title uniqueness while allowing deleted rows to keep history.
-- Existing active duplicates keep the earliest created row, then the smallest id as tie-breaker.
UPDATE contests c
JOIN (
    SELECT id,
           ROW_NUMBER() OVER (PARTITION BY title ORDER BY created_at ASC, id ASC) AS rn
    FROM contests
    WHERE deleted_at IS NULL
) ranked ON ranked.id = c.id
SET c.deleted_at = NOW(3),
    c.deleted_by = NULL,
    c.updated_at = NOW(3)
WHERE ranked.rn > 1;

UPDATE contest_runs r
JOIN (
    SELECT id,
           ROW_NUMBER() OVER (PARTITION BY title ORDER BY created_at ASC, id ASC) AS rn
    FROM contest_runs
    WHERE deleted_at IS NULL
) ranked ON ranked.id = r.id
SET r.deleted_at = NOW(3),
    r.deleted_by = NULL,
    r.updated_at = NOW(3)
WHERE ranked.rn > 1;

ALTER TABLE contests
    ADD COLUMN active_title VARCHAR(160)
        GENERATED ALWAYS AS (CASE WHEN deleted_at IS NULL THEN title ELSE NULL END) STORED,
    ADD UNIQUE KEY uk_contests_active_title (active_title);

ALTER TABLE contest_runs
    ADD COLUMN active_title VARCHAR(160)
        GENERATED ALWAYS AS (CASE WHEN deleted_at IS NULL THEN title ELSE NULL END) STORED,
    ADD UNIQUE KEY uk_contest_runs_active_title (active_title);
