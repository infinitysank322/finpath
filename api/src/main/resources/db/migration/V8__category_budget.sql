-- V8 (T40, FR-10): category_budget, one spending limit per category per cycle.
--
-- cycle_start is the date CycleCalendar returns for the cycle; it is deliberately NOT a foreign
-- key to plan_cycle, so a budget can be set before the cycle has a plan row and a change of the
-- cycle start day cannot orphan it. budget_paise is whole paise (bigint). There is no free-text
-- column: the category identifies the line, and no account, card or identity number can be stored.
-- last_alert_level is the highest level already announced for the row (OK, WARN, BREACH), so an
-- alert fires once; it only ever moves up until the budget amount is changed.

CREATE TABLE category_budget (
    id               uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id          uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    cycle_start      date        NOT NULL,
    category_id      uuid        NOT NULL REFERENCES category (id) ON DELETE RESTRICT,
    budget_paise     bigint      NOT NULL CHECK (budget_paise >= 0),
    last_alert_level text        NOT NULL DEFAULT 'OK' CHECK (last_alert_level IN ('OK', 'WARN', 'BREACH')),
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    UNIQUE (user_id, cycle_start, category_id)
);

ALTER TABLE category_budget ENABLE ROW LEVEL SECURITY;
ALTER TABLE category_budget FORCE ROW LEVEL SECURITY;

CREATE POLICY category_budget_self ON category_budget
    USING (user_id = NULLIF(current_setting('app.current_user_id', true), '')::uuid)
    WITH CHECK (user_id = NULLIF(current_setting('app.current_user_id', true), '')::uuid
                AND finpath_has_consent());

GRANT SELECT, INSERT, UPDATE, DELETE ON category_budget TO app_user;
