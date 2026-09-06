-- Older testcase packages may have NULL/zero per-case scores. Preserve their
-- intended equal-weight IOI behavior by assigning one point to each case.
UPDATE testcase_package_cases
SET score = 1
WHERE score IS NULL OR score <= 0;
