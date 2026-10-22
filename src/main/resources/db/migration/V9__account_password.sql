-- Optional password on a customer account (ADR 0005, addendum of 2026-10-22). Null means the
-- account logs in by mail link only, which stays the default and remains the recovery path
-- for every account. The value is a bcrypt hash over a SHA-256 pre-hash of the password, so
-- a copy of the database yields no password.
ALTER TABLE accounts
    ADD COLUMN password_hash TEXT;
