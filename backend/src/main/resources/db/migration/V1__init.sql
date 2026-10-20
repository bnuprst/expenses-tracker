CREATE TABLE users (
    id            BIGSERIAL PRIMARY KEY,
    email         TEXT NOT NULL UNIQUE,
    name          TEXT NOT NULL,
    password_hash TEXT NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE wallets (
    id         BIGSERIAL PRIMARY KEY,
    name       TEXT NOT NULL,
    currency   VARCHAR(3) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE wallet_members (
    wallet_id BIGINT NOT NULL REFERENCES wallets (id) ON DELETE CASCADE,
    user_id   BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role      VARCHAR(16) NOT NULL CHECK (role IN ('OWNER', 'MEMBER')),
    PRIMARY KEY (wallet_id, user_id)
);

CREATE TABLE categories (
    id        BIGSERIAL PRIMARY KEY,
    wallet_id BIGINT NOT NULL REFERENCES wallets (id) ON DELETE CASCADE,
    name      TEXT NOT NULL,
    kind      VARCHAR(16) NOT NULL CHECK (kind IN ('EXPENSE', 'INCOME', 'TRANSFER')),
    parent_id BIGINT REFERENCES categories (id) ON DELETE SET NULL,
    UNIQUE (wallet_id, name)
);

CREATE TABLE bank_connections (
    id            BIGSERIAL PRIMARY KEY,
    wallet_id     BIGINT NOT NULL REFERENCES wallets (id) ON DELETE CASCADE,
    created_by    BIGINT NOT NULL REFERENCES users (id),
    aspsp_name    TEXT NOT NULL,
    country       VARCHAR(2) NOT NULL,
    session_id    TEXT,
    auth_state    TEXT UNIQUE,
    valid_until   TIMESTAMPTZ,
    status        VARCHAR(16) NOT NULL CHECK (status IN ('PENDING', 'ACTIVE', 'EXPIRED', 'ERROR')),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE bank_accounts (
    id               BIGSERIAL PRIMARY KEY,
    connection_id    BIGINT NOT NULL REFERENCES bank_connections (id) ON DELETE CASCADE,
    -- uid is per Enable Banking session and changes on re-authorisation; identity_key is stable.
    external_uid     TEXT NOT NULL,
    identity_key     TEXT NOT NULL,
    name             TEXT,
    iban_masked      TEXT,
    currency         VARCHAR(3),
    last_booking_date DATE,
    UNIQUE (connection_id, identity_key)
);

CREATE TABLE transactions (
    id              BIGSERIAL PRIMARY KEY,
    wallet_id       BIGINT NOT NULL REFERENCES wallets (id) ON DELETE CASCADE,
    bank_account_id BIGINT REFERENCES bank_accounts (id) ON DELETE SET NULL,
    external_id     TEXT,
    booking_date    DATE NOT NULL,
    amount          NUMERIC(14, 2) NOT NULL,
    currency        VARCHAR(3) NOT NULL,
    description_raw TEXT NOT NULL,
    counterparty    TEXT,
    merchant_key    TEXT NOT NULL,
    category_id     BIGINT REFERENCES categories (id) ON DELETE SET NULL,
    category_source VARCHAR(16) CHECK (category_source IN ('MEMORY', 'RULE', 'LLM', 'MANUAL')),
    confidence      DOUBLE PRECISION,
    status          VARCHAR(16) NOT NULL CHECK (status IN ('CATEGORIZED', 'REVIEW', 'UNCATEGORIZED')),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (bank_account_id, external_id)
);
CREATE INDEX transactions_wallet_date_idx ON transactions (wallet_id, booking_date DESC);
CREATE INDEX transactions_wallet_status_idx ON transactions (wallet_id, status);

CREATE TABLE merchant_memory (
    wallet_id    BIGINT NOT NULL REFERENCES wallets (id) ON DELETE CASCADE,
    merchant_key TEXT NOT NULL,
    category_id  BIGINT NOT NULL REFERENCES categories (id) ON DELETE CASCADE,
    hits         INT NOT NULL DEFAULT 1,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (wallet_id, merchant_key)
);

CREATE TABLE rules (
    id          BIGSERIAL PRIMARY KEY,
    wallet_id   BIGINT NOT NULL REFERENCES wallets (id) ON DELETE CASCADE,
    priority    INT NOT NULL DEFAULT 100,
    field       VARCHAR(16) NOT NULL CHECK (field IN ('DESCRIPTION', 'MERCHANT', 'COUNTERPARTY', 'AMOUNT')),
    op          VARCHAR(16) NOT NULL CHECK (op IN ('CONTAINS', 'EQUALS', 'REGEX', 'GT', 'LT')),
    value       TEXT NOT NULL,
    category_id BIGINT NOT NULL REFERENCES categories (id) ON DELETE CASCADE
);

CREATE TABLE sync_runs (
    id            BIGSERIAL PRIMARY KEY,
    connection_id BIGINT NOT NULL REFERENCES bank_connections (id) ON DELETE CASCADE,
    started_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at   TIMESTAMPTZ,
    fetched       INT NOT NULL DEFAULT 0,
    inserted      INT NOT NULL DEFAULT 0,
    error         TEXT
);
CREATE INDEX sync_runs_connection_idx ON sync_runs (connection_id, started_at DESC);
