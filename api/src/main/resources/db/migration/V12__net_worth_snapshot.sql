-- V12 (T70, FR-26): net_worth_snapshot, one row per closed plan_cycle (assets - liabilities as at the close).
--
-- Written only by the cycle-close step in PlanCycleService (same transaction as OPEN -> CLOSED); there is no
-- API that writes it, so a closed cycle cannot be backfilled. Rows are immutable: no UPDATE grant. At most one
-- per cycle (unique plan_cycle_id). Money is whole paise (bigint); net_worth_paise may be negative.
-- No account, folio or identity columns. Assets = sum of holding.current_value_paise, liabilities = sum of
-- loan.principal_outstanding_paise at the moment of closing; net worth comes from rules Statistics.netWorth.

CREATE TABLE net_worth_snapshot (
    id                uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id           uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    plan_cycle_id     uuid        NOT NULL REFERENCES plan_cycle (id) ON DELETE CASCADE,
    cycle_start       date        NOT NULL,
    assets_paise      bigint      NOT NULL CHECK (assets_paise >= 0),
    liabilities_paise bigint      NOT NULL CHECK (liabilities_paise >= 0),
    net_worth_paise   bigint      NOT NULL CHECK (net_worth_paise = assets_paise - liabilities_paise),
    created_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT net_worth_snapshot_cycle_key UNIQUE (plan_cycle_id)
);

CREATE INDEX net_worth_snapshot_user_idx ON net_worth_snapshot (user_id, cycle_start);

ALTER TABLE net_worth_snapshot ENABLE ROW LEVEL SECURITY;
ALTER TABLE net_worth_snapshot FORCE ROW LEVEL SECURITY;

CREATE POLICY net_worth_snapshot_self ON net_worth_snapshot
    USING (user_id = NULLIF(current_setting('app.current_user_id', true), '')::uuid)
    WITH CHECK (user_id = NULLIF(current_setting('app.current_user_id', true), '')::uuid
                AND finpath_has_consent());

GRANT SELECT, INSERT, DELETE ON net_worth_snapshot TO app_user;
