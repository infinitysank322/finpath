-- V2 (T28, FR-01): the users table.
--
-- No account, card or identity-number columns, by design (CLAUDE.md): only what the
-- planner needs. Every later per-user table carries user_id and its own policy.
--
-- Row-level security keys on the transaction-scoped setting app.current_user_id, set by
-- the connection initialiser added in T30. An unset setting yields NULL, so the policy
-- matches no row and the table fails closed.

CREATE TABLE users (
    id                uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    name              text        NOT NULL,
    email             text        NOT NULL UNIQUE CHECK (email = lower(email)),
    cycle_start_day   smallint    NOT NULL DEFAULT 25 CHECK (cycle_start_day BETWEEN 1 AND 31),
    currency          char(3)     NOT NULL DEFAULT 'INR' CHECK (currency = 'INR'),
    risk_profile      text        CHECK (risk_profile IN ('CONSERVATIVE', 'MODERATE', 'AGGRESSIVE')),
    fi_target_age     smallint    CHECK (fi_target_age BETWEEN 18 AND 100),
    created_at        timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE users ENABLE ROW LEVEL SECURITY;
ALTER TABLE users FORCE ROW LEVEL SECURITY;

CREATE POLICY users_self ON users
    USING (id = NULLIF(current_setting('app.current_user_id', true), '')::uuid)
    WITH CHECK (id = NULLIF(current_setting('app.current_user_id', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE, DELETE ON users TO app_user;
