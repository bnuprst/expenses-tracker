-- Two legs of one movement between the user's own accounts (or one in-app currency exchange) share a
-- transfer_group and are left out of income/expense. Always recomputed, never user-entered.
ALTER TABLE transactions ADD COLUMN transfer_group TEXT;
CREATE INDEX transactions_transfer_group ON transactions (wallet_id, transfer_group)
    WHERE transfer_group IS NOT NULL;

-- TRUE only when a *user* explicitly assigned a non-TRANSFER category to this row after the fact
-- (PATCH /transactions/{id}). It must NOT be inferred from category_source = 'MANUAL', because the
-- CSV importer also stores its own supplied categories as MANUAL (ImportService.kt:77).
ALTER TABLE transactions ADD COLUMN transfer_override BOOLEAN NOT NULL DEFAULT FALSE;
