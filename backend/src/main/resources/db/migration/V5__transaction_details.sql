-- Bank-feed details for the transaction details screen. Bank rows get them from now on; rows synced before
-- this get them when a later sync fetches them again (each sync overlaps the previous one by a week).
ALTER TABLE transactions
    ADD COLUMN transaction_date DATE,
    ADD COLUMN value_date DATE,
    ADD COLUMN counterparty_iban TEXT,
    ADD COLUMN payment_type TEXT;
