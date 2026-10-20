-- Imported (non-bank) transactions carry the source system's id; re-running an import must not duplicate them.
-- The existing UNIQUE (bank_account_id, external_id) does not apply when bank_account_id is NULL.
CREATE UNIQUE INDEX transactions_imported_external_id_idx
    ON transactions (wallet_id, external_id)
    WHERE bank_account_id IS NULL AND external_id IS NOT NULL;
