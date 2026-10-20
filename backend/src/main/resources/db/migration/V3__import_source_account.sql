-- The source system's account name for imported rows, so a bank account's first sync can start where
-- that account's imported history ends rather than where any imported account's history ends.
ALTER TABLE transactions ADD COLUMN source_account TEXT;
