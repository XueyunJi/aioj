ALTER TABLE problems
  ADD COLUMN notes TEXT NULL COMMENT 'Markdown notes shown to students after the statement' AFTER statement;
