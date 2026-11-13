ALTER TABLE sync_runs ADD COLUMN error_key TEXT;

-- Backfill the only two messages this backend ever generated itself, so errors stored before this
-- migration are localized too. Provider text and unexpected-exception text have no key and stay as-is.
UPDATE sync_runs SET error_key = 'bank.rate_limit_scheduled'
 WHERE error = 'The bank''s daily limit on automatic refreshes was reached; they resume tomorrow. Sync now still works.';

UPDATE sync_runs SET error_key = 'bank.rate_limit_user'
 WHERE error = 'The bank is refusing requests for now (rate limit); try again later.';
