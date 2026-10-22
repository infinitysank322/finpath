-- V5 (T35, FR-03): income_source, one row per income stream or one-off receipt.
--
-- amount_paise is whole paise (bigint). The column is named amount_paise (not amount) so the DPDP
-- export, which emits raw column names, shows the unit. frequency MONTHLY is recurring and counts in
-- every cycle overlapping [start_date, end_date] (end_date inclusive, NULL = open-ended); ONE_OFF
-- counts only in the cycle containing start_date (the date received) and has no end_date. The
-- per-cycle maths lives in the rules package (IncomeCalculator), not in SQL.
-- No label, payer, account, card or identity-number columns.

CREATE TABLE income_source (
    id           uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    type         text        NOT NULL CHECK (type IN ('SALARY', 'RENTAL', 'BONUS', 'SIDE', 'OTHER')),
    frequency    text        NOT NULL CHECK (frequency IN ('MONTHLY', 'ONE_OFF')),
    amount_paise bigint      NOT NULL CHECK (amount_paise > 0),
    start_date   date        NOT NULL,
    end_date     date,
    created_at   timestamptz NOT NULL DEFAULT now(),
    CHECK (end_date IS NULL OR end_date >= start_date),
    CHECK (frequency = 'MONTHLY' OR end_date IS NULL)
);

CREATE INDEX income_source_user_start ON income_source (user_id, start_date);

ALTER TABLE income_source ENABLE ROW LEVEL SECURITY;
ALTER TABLE income_source FORCE ROW LEVEL SECURITY;

CREATE POLICY income_source_self ON income_source
    USING (user_id = NULLIF(current_setting('app.current_user_id', true), '')::uuid)
    WITH CHECK (user_id = NULLIF(current_setting('app.current_user_id', true), '')::uuid
                AND finpath_has_consent());

GRANT SELECT, INSERT, UPDATE, DELETE ON income_source TO app_user;
