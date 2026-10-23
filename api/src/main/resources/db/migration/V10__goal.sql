-- V10 (T61, FR-19): goal, one row per dated savings goal the user holds.
--
-- Money is whole paise (bigint). type is a closed list of goal kinds. priority is 1 (highest) to 10.
-- There is no account, card or identity number column, and none may be added. The monthly need and the
-- on-track flag are never stored: the rules engine computes them on read from these columns.

CREATE TABLE goal (
    id                   uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id              uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    type                 text        NOT NULL CHECK (type IN ('emergency', 'marriage', 'education', 'home',
                                                              'health', 'vehicle', 'travel', 'other')),
    target_amount_paise  bigint      NOT NULL CHECK (target_amount_paise > 0),
    target_date          date        NOT NULL,
    current_amount_paise bigint      NOT NULL DEFAULT 0 CHECK (current_amount_paise >= 0),
    priority             integer     NOT NULL DEFAULT 5 CHECK (priority BETWEEN 1 AND 10),
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX goal_user_priority_idx ON goal (user_id, priority, target_date);

ALTER TABLE goal ENABLE ROW LEVEL SECURITY;
ALTER TABLE goal FORCE ROW LEVEL SECURITY;

CREATE POLICY goal_self ON goal
    USING (user_id = NULLIF(current_setting('app.current_user_id', true), '')::uuid)
    WITH CHECK (user_id = NULLIF(current_setting('app.current_user_id', true), '')::uuid
                AND finpath_has_consent());

GRANT SELECT, INSERT, UPDATE, DELETE ON goal TO app_user;
