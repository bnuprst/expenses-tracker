-- Set when the bank rejects a scheduled sync for its daily limit on background access; scheduled syncs
-- skip the connection until then. Syncs the user starts themselves are not affected.
ALTER TABLE bank_connections ADD COLUMN sync_paused_until TIMESTAMPTZ;
