-- V6 (T36, FR-04): category (shared system taxonomy) and transaction (personal, RLS).
--
-- category holds no personal data: identical for every user, no user_id, no RLS. app_user gets
-- SELECT only, so the application role cannot alter the taxonomy. Rows are keyed for humans by
-- the stable upper-case code (the dedupe hash uses the code, never the uuid).
-- Custom per-user categories are out of scope; per-cycle budgets arrive as category_budget later.
--
-- transaction.amount_paise is whole paise (bigint). dedupe_hash is a duplicate HINT (non-unique
-- index): two identical small payments in a day are legitimate. The hard guard against a retried
-- request is UNIQUE (user_id, idempotency_key). note is the only free-text column (max 140);
-- it must never hold account, card or identity numbers. tag is nullable: untagged stays untagged.

CREATE TABLE category (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    code       text NOT NULL UNIQUE CHECK (code = upper(code)),
    name       text NOT NULL,
    parent_id  uuid REFERENCES category (id),
    sort_order int  NOT NULL
);

INSERT INTO category (code, name, sort_order) VALUES
    ('GROCERIES', 'Groceries', 10),
    ('DINING', 'Dining out', 20),
    ('RENT', 'Rent', 30),
    ('UTILITIES', 'Utilities', 40),
    ('TRANSPORT', 'Transport', 50),
    ('FUEL', 'Fuel', 60),
    ('HEALTH', 'Health', 70),
    ('EDUCATION', 'Education', 80),
    ('INSURANCE', 'Insurance', 90),
    ('EMI', 'EMI', 100),
    ('SUBSCRIPTIONS', 'Subscriptions', 110),
    ('SHOPPING', 'Shopping', 120),
    ('ENTERTAINMENT', 'Entertainment', 130),
    ('TRAVEL', 'Travel', 140),
    ('PERSONAL_CARE', 'Personal care', 150),
    ('GIFTS_DONATIONS', 'Gifts and donations', 160),
    ('TAXES_FEES', 'Taxes and fees', 170),
    ('OTHER', 'Other', 180),
    ('INCOME', 'Income', 200),
    ('REFUND', 'Refund', 210),
    ('OTHER_IN', 'Other income', 220);

GRANT SELECT ON category TO app_user;

CREATE TABLE transaction (
    id              uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    txn_date        date        NOT NULL,
    amount_paise    bigint      NOT NULL CHECK (amount_paise > 0),
    direction       text        NOT NULL CHECK (direction IN ('IN', 'OUT')),
    category_id     uuid        NOT NULL REFERENCES category (id) ON DELETE RESTRICT,
    mode            text        NOT NULL CHECK (mode IN ('UPI', 'CARD', 'CASH', 'NETBANKING', 'OTHER')),
    tag             text        CHECK (tag IN ('NEED', 'WANT', 'WASTE')),
    source          text        NOT NULL DEFAULT 'MANUAL' CHECK (source IN ('MANUAL', 'SMS', 'IMPORT')),
    note            text        CHECK (note IS NULL OR char_length(note) <= 140),
    dedupe_hash     text        NOT NULL CHECK (dedupe_hash ~ '^[0-9a-f]{64}$'),
    idempotency_key text        NOT NULL CHECK (char_length(idempotency_key) BETWEEN 8 AND 64),
    created_at      timestamptz NOT NULL DEFAULT now(),
    CHECK (tag IS NULL OR direction = 'OUT')
);

CREATE UNIQUE INDEX transaction_user_idempotency ON transaction (user_id, idempotency_key);
CREATE INDEX transaction_user_date ON transaction (user_id, txn_date);
CREATE INDEX transaction_user_dedupe ON transaction (user_id, dedupe_hash);

ALTER TABLE transaction ENABLE ROW LEVEL SECURITY;
ALTER TABLE transaction FORCE ROW LEVEL SECURITY;

CREATE POLICY transaction_self ON transaction
    USING (user_id = NULLIF(current_setting('app.current_user_id', true), '')::uuid)
    WITH CHECK (user_id = NULLIF(current_setting('app.current_user_id', true), '')::uuid
                AND finpath_has_consent());

GRANT SELECT, INSERT, UPDATE, DELETE ON transaction TO app_user;
