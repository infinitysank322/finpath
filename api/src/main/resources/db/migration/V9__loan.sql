-- V9 (T48, FR-12): loan, one row per liability the user owes.
--
-- Money is whole paise (bigint). rate is the annual rate as a fraction (0.0835 = 8.35%), the same unit
-- the rules package uses, numeric(12,10) to hold it at the rules' RATE_SCALE; it is a rate, not money.
-- emi_paise is the instalment the user states (stored verbatim and authoritative), or, when none was
-- stated, the value the rules engine derived at write time (emi_derived = true).
-- lender is a display name only (capped at 60 chars). There is no account, card or identity number
-- column, and none may be added; the API tells the user not to type one into lender.

CREATE TABLE loan (
    id                          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                     uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    lender                      text        NOT NULL CHECK (char_length(lender) BETWEEN 1 AND 60),
    type                        text        NOT NULL CHECK (type IN ('emi', 'bullet', 'card_emi')),
    principal_outstanding_paise bigint      NOT NULL CHECK (principal_outstanding_paise >= 0),
    rate                        numeric(12, 10) NOT NULL CHECK (rate >= 0 AND rate < 1),
    emi_paise                   bigint      CHECK (emi_paise IS NULL OR emi_paise > 0),
    emi_derived                 boolean     NOT NULL DEFAULT false,
    emis_left                   integer     CHECK (emis_left IS NULL OR emis_left > 0),
    bullet_due_date             date,
    upfront_fee_paise           bigint      NOT NULL DEFAULT 0 CHECK (upfront_fee_paise >= 0),
    created_at                  timestamptz NOT NULL DEFAULT now(),
    updated_at                  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT loan_bullet_due CHECK ((type = 'bullet') = (bullet_due_date IS NOT NULL)),
    CONSTRAINT loan_emis_left CHECK ((type = 'bullet') = (emis_left IS NULL)),
    CONSTRAINT loan_derived_has_emi CHECK (NOT emi_derived OR emi_paise IS NOT NULL),
    CONSTRAINT loan_card_emi_stated CHECK (type <> 'card_emi' OR (emi_paise IS NOT NULL AND NOT emi_derived)),
    CONSTRAINT loan_fee_card_only CHECK (type = 'card_emi' OR upfront_fee_paise = 0)
);

CREATE INDEX loan_user_type_idx ON loan (user_id, type);

ALTER TABLE loan ENABLE ROW LEVEL SECURITY;
ALTER TABLE loan FORCE ROW LEVEL SECURITY;

CREATE POLICY loan_self ON loan
    USING (user_id = NULLIF(current_setting('app.current_user_id', true), '')::uuid)
    WITH CHECK (user_id = NULLIF(current_setting('app.current_user_id', true), '')::uuid
                AND finpath_has_consent());

GRANT SELECT, INSERT, UPDATE, DELETE ON loan TO app_user;
