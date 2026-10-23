-- V11 (T65, FR-23): holding, one row per investment line the user holds (EPF, PPF, NPS, MF, stocks, FD, gold).
--
-- Money is whole paise (bigint). asset_class is a closed list. name is a short free-text LABEL the user types
-- (for example "Employer EPF" or "Index fund"); it is NOT for folio, demat, account, PAN or UAN numbers, and
-- the API rejects long digit runs. There is no account, folio, demat or identity column, and none may be added.
-- Totals per asset class are computed on read; they are never stored.

CREATE TABLE holding (
    id                           uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                      uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    asset_class                  text        NOT NULL CHECK (asset_class IN ('epf', 'ppf', 'nps', 'mf', 'stocks',
                                                                              'fd', 'gold')),
    name                         text        NOT NULL CHECK (char_length(name) BETWEEN 1 AND 60),
    invested_paise               bigint      NOT NULL DEFAULT 0 CHECK (invested_paise >= 0),
    current_value_paise          bigint      NOT NULL DEFAULT 0 CHECK (current_value_paise >= 0),
    monthly_contribution_paise   bigint      NOT NULL DEFAULT 0 CHECK (monthly_contribution_paise >= 0),
    created_at                   timestamptz NOT NULL DEFAULT now(),
    updated_at                   timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX holding_user_class_idx ON holding (user_id, asset_class, created_at);

ALTER TABLE holding ENABLE ROW LEVEL SECURITY;
ALTER TABLE holding FORCE ROW LEVEL SECURITY;

CREATE POLICY holding_self ON holding
    USING (user_id = NULLIF(current_setting('app.current_user_id', true), '')::uuid)
    WITH CHECK (user_id = NULLIF(current_setting('app.current_user_id', true), '')::uuid
                AND finpath_has_consent());

GRANT SELECT, INSERT, UPDATE, DELETE ON holding TO app_user;
