-- Customer accounts with passwordless login (ADR 0005). An account is created the first time
-- a login link for its e-mail address is used; there is no password column by design.
-- Login tokens and session secrets are random and stored only as SHA-256 hashes, so a copy
-- of the database cannot be turned into a login.

CREATE TABLE accounts (
    id            UUID         PRIMARY KEY,
    -- Lower-cased by the application; the unique index makes the e-mail the identity.
    email         VARCHAR(254) NOT NULL UNIQUE,
    -- Shipping details the checkout can prefill; null until the customer saves them.
    name          VARCHAR(120),
    street        VARCHAR(160),
    postal_code   VARCHAR(16),
    city          VARCHAR(120),
    country       VARCHAR(2),
    created_at    TIMESTAMPTZ  NOT NULL,
    last_login_at TIMESTAMPTZ  NOT NULL
);

-- One-time login links: used once, valid for minutes, purged after expiry.
CREATE TABLE login_tokens (
    id         UUID         PRIMARY KEY,
    email      VARCHAR(254) NOT NULL,
    token_hash VARCHAR(64)  NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ  NOT NULL,
    used_at    TIMESTAMPTZ,
    created_at TIMESTAMPTZ  NOT NULL,
    -- Who requested the link (client address), for abuse investigations.
    client_key VARCHAR(64)  NOT NULL
);

CREATE INDEX login_tokens_expires_at_idx ON login_tokens (expires_at);

-- Browser sessions behind the pod_session cookie; gone with the account.
CREATE TABLE account_sessions (
    id           UUID        PRIMARY KEY,
    account_id   UUID        NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    token_hash   VARCHAR(64) NOT NULL UNIQUE,
    created_at   TIMESTAMPTZ NOT NULL,
    last_seen_at TIMESTAMPTZ NOT NULL,
    expires_at   TIMESTAMPTZ NOT NULL
);

CREATE INDEX account_sessions_account_idx ON account_sessions (account_id);
CREATE INDEX account_sessions_expires_at_idx ON account_sessions (expires_at);

-- Orders placed while logged in remember the account; deleting the account keeps the order
-- under its retention rules and only drops the link. Erasure also clears it.
ALTER TABLE orders
    ADD COLUMN account_id UUID REFERENCES accounts (id) ON DELETE SET NULL;

CREATE INDEX orders_account_idx ON orders (account_id) WHERE account_id IS NOT NULL;
-- The account's order list matches the e-mail case-insensitively.
CREATE INDEX orders_customer_email_lower_idx ON orders (lower(customer_email));
