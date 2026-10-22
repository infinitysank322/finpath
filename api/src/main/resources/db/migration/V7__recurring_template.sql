-- V7 (T38, FR-05): recurring_template, a bill that repeats every month, quarter or year.
--
-- amount_paise is the BILL per period in whole paise (bigint), not the monthly instalment; the
-- split into per-cycle instalments is computed by the rules package (RecurringSplit), never in SQL.
-- There is deliberately no free-text column (no label, payer, reference or note): the category
-- identifies the bill, and no account, card or identity number can be stored here. Postings made
-- from a template are ordinary transaction rows (source RECURRING, note NULL) and outlive it.

CREATE TABLE recurring_template (
    id           uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    category_id  uuid        NOT NULL REFERENCES category (id) ON DELETE RESTRICT,
    amount_paise bigint      NOT NULL CHECK (amount_paise > 0),
    frequency    text        NOT NULL CHECK (frequency IN ('MONTHLY', 'QUARTERLY', 'YEARLY')),
    mode         text        NOT NULL CHECK (mode IN ('UPI', 'CARD', 'CASH', 'NETBANKING', 'OTHER')),
    tag          text        CHECK (tag IN ('NEED', 'WANT', 'WASTE')),
    start_date   date        NOT NULL,
    end_date     date,
    created_at   timestamptz NOT NULL DEFAULT now(),
    CHECK (end_date IS NULL OR end_date >= start_date)
);

CREATE INDEX recurring_template_user_start ON recurring_template (user_id, start_date);

ALTER TABLE recurring_template ENABLE ROW LEVEL SECURITY;
ALTER TABLE recurring_template FORCE ROW LEVEL SECURITY;

CREATE POLICY recurring_template_self ON recurring_template
    USING (user_id = NULLIF(current_setting('app.current_user_id', true), '')::uuid)
    WITH CHECK (user_id = NULLIF(current_setting('app.current_user_id', true), '')::uuid
                AND finpath_has_consent());

GRANT SELECT, INSERT, UPDATE, DELETE ON recurring_template TO app_user;

-- Postings from a template carry source RECURRING. The V6 inline CHECK got the default name
-- transaction_source_check (confirmed with \d transaction).
ALTER TABLE transaction DROP CONSTRAINT transaction_source_check;
ALTER TABLE transaction ADD CONSTRAINT transaction_source_check
    CHECK (source IN ('MANUAL', 'SMS', 'IMPORT', 'RECURRING'));
