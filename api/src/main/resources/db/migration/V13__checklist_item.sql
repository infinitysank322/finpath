-- V13 (T71, FR-26): checklist_item, one row per payment the user should make in a plan cycle
-- (an EMI, a SIP, or a goal transfer), ticked off with done_at.
--
-- Rows are generated from loan.emi_paise and the stored plan_cycle.allocations; amounts are frozen at
-- generation (whole paise, bigint). (plan_cycle_id, type, source_ref) is the idempotency key: generating
-- again inserts only new keys. source_ref is a loan or goal uuid, or a SIP step name; never a user-typed
-- value. There is no account, folio or identity column, and none may be added.
-- Only done_at is updatable (the grant below); amounts and keys are immutable.

CREATE TABLE checklist_item (
    id            uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    plan_cycle_id uuid        NOT NULL REFERENCES plan_cycle (id) ON DELETE CASCADE,
    type          text        NOT NULL CHECK (type IN ('EMI', 'SIP', 'GOAL_TRANSFER')),
    source_ref    text        NOT NULL CHECK (char_length(source_ref) BETWEEN 1 AND 64),
    amount_paise  bigint      NOT NULL CHECK (amount_paise >= 0),
    done_at       timestamptz,
    created_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT checklist_item_key UNIQUE (plan_cycle_id, type, source_ref)
);

CREATE INDEX checklist_item_user_cycle_idx ON checklist_item (user_id, plan_cycle_id);

ALTER TABLE checklist_item ENABLE ROW LEVEL SECURITY;
ALTER TABLE checklist_item FORCE ROW LEVEL SECURITY;

CREATE POLICY checklist_item_self ON checklist_item
    USING (user_id = NULLIF(current_setting('app.current_user_id', true), '')::uuid)
    WITH CHECK (user_id = NULLIF(current_setting('app.current_user_id', true), '')::uuid
                AND finpath_has_consent());

GRANT SELECT, INSERT, DELETE ON checklist_item TO app_user;
GRANT UPDATE (done_at) ON checklist_item TO app_user;
