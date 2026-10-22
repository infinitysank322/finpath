-- V4 (T34, FR-02): plan_cycle, one row per budget cycle a user has been in.
--
-- cycle_start / cycle_end are the bounds computed by the rules package (CycleCalendar); cycle_end is
-- exclusive, the next cycle's first day. start_day records the users.cycle_start_day the bounds were
-- derived with. surplus is paise (bigint), NULL until the allocation run (T64) has computed it;
-- allocations is the waterfall output as a JSON array (also paise), '[]' until then.
-- No account, card or identity-number columns.
--
-- status: OPEN = the current cycle (at most one per user, enforced by a partial unique index);
-- CLOSED = the cycle ended; SUPERSEDED = still running when the user changed their cycle start day,
-- so its bounds no longer describe their calendar. Rows are never orphaned by a day change.

CREATE TABLE plan_cycle (
    id            uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    cycle_start   date        NOT NULL,
    cycle_end     date        NOT NULL,
    start_day     smallint    NOT NULL CHECK (start_day BETWEEN 1 AND 31),
    surplus       bigint,
    allocations   jsonb       NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(allocations) = 'array'),
    status        text        NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'CLOSED', 'SUPERSEDED')),
    created_at    timestamptz NOT NULL DEFAULT now(),
    CHECK (cycle_end > cycle_start),
    CONSTRAINT plan_cycle_user_start_key UNIQUE (user_id, cycle_start)
);

CREATE UNIQUE INDEX plan_cycle_one_open_per_user ON plan_cycle (user_id) WHERE status = 'OPEN';

ALTER TABLE plan_cycle ENABLE ROW LEVEL SECURITY;
ALTER TABLE plan_cycle FORCE ROW LEVEL SECURITY;

CREATE POLICY plan_cycle_self ON plan_cycle
    USING (user_id = NULLIF(current_setting('app.current_user_id', true), '')::uuid)
    WITH CHECK (user_id = NULLIF(current_setting('app.current_user_id', true), '')::uuid
                AND finpath_has_consent());

GRANT SELECT, INSERT, UPDATE, DELETE ON plan_cycle TO app_user;
