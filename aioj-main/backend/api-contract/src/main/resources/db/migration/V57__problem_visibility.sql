-- Problem visibility controls whether students can see the problem in the
-- public catalog and practice mode. PRIVATE problems remain usable in contest
-- runs and only become visible to participants inside an active run window.
ALTER TABLE problems
    ADD COLUMN visibility VARCHAR(16) NOT NULL DEFAULT 'PUBLIC' AFTER ai_generated;
