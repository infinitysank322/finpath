-- V15 (T72, FR-27): loan.due_day and reminder_sent.
--
-- loan.due_day: the day of the month the payment falls due (1-31, clamped to the month length by the
-- rules package). Nullable, no default, no backfill: a loan without it produces no reminder.
--
-- reminder_sent: the claim row that makes a reminder fire at most once per (user, key, due date). The key
-- is a kind plus a loan uuid, never user text, lender, amount or any account/card/identity value. There
-- is deliberately no foreign key to loan (deleting a loan must not touch history). UPDATE is not granted;
-- DELETE is only to release a claim when delivery failed.

ALTER TABLE loan ADD COLUMN due_day smallint CHECK (due_day IS NULL OR due_day BETWEEN 1 AND 31);

CREATE TABLE reminder_sent (
    id           uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    reminder_key text        NOT NULL CHECK (reminder_key ~ '^(loan_due:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}|cycle_start)$'),
    due_date     date        NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT reminder_sent_once UNIQUE (user_id, reminder_key, due_date)
);

ALTER TABLE reminder_sent ENABLE ROW LEVEL SECURITY;
ALTER TABLE reminder_sent FORCE ROW LEVEL SECURITY;

CREATE POLICY reminder_sent_self ON reminder_sent
    USING (user_id = NULLIF(current_setting('app.current_user_id', true), '')::uuid)
    WITH CHECK (user_id = NULLIF(current_setting('app.current_user_id', true), '')::uuid
                AND finpath_has_consent());

GRANT SELECT, INSERT, DELETE ON reminder_sent TO app_user;

-- Candidate users for the reminder job: ids of users holding a live consent. RLS forbids a cross-tenant
-- read and the job must not run as a role that bypasses it, so this follows V3: a NOLOGIN role that owns
-- one SECURITY DEFINER function and can read only consent.user_id / withdrawn_at. It returns ids only.
-- EXECUTE goes to the login role, not app_user. Whether the consent matches the current statement version
-- is re-checked per user, inside that user's own RLS scope.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = 'finpath_reminder') THEN
        CREATE ROLE finpath_reminder NOLOGIN NOINHERIT;
    END IF;
END
$$;

GRANT SELECT (user_id, withdrawn_at) ON consent TO finpath_reminder;
CREATE POLICY consent_reminder_select ON consent FOR SELECT TO finpath_reminder USING (true);

CREATE FUNCTION finpath_reminder_users()
    RETURNS uuid[]
    LANGUAGE sql
    STABLE
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
    SELECT coalesce(array_agg(DISTINCT c.user_id), '{}'::uuid[])
    FROM public.consent c
    WHERE c.withdrawn_at IS NULL;
$$;

DO $$
BEGIN
    IF current_setting('server_version_num')::int >= 160000 THEN
        EXECUTE 'GRANT finpath_reminder TO CURRENT_USER WITH INHERIT TRUE, SET TRUE';
    ELSE
        EXECUTE 'GRANT finpath_reminder TO CURRENT_USER';
    END IF;
END
$$;
GRANT CREATE ON SCHEMA public TO finpath_reminder;
ALTER FUNCTION finpath_reminder_users() OWNER TO finpath_reminder;
REVOKE CREATE ON SCHEMA public FROM finpath_reminder;
REVOKE ALL ON FUNCTION finpath_reminder_users() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION finpath_reminder_users() TO CURRENT_USER;
REVOKE finpath_reminder FROM CURRENT_USER;

-- Fail closed, as in V3: the migrating role must not keep the ability to act as finpath_reminder.
DO $$
DECLARE
    is_superuser boolean;
    can_use boolean;
    can_set boolean := false;
BEGIN
    SELECT rolsuper INTO is_superuser FROM pg_catalog.pg_roles WHERE rolname = current_user;
    IF is_superuser THEN
        RETURN;
    END IF;
    can_use := pg_catalog.pg_has_role(current_user, 'finpath_reminder', 'USAGE');
    IF current_setting('server_version_num')::int >= 160000 THEN
        can_set := pg_catalog.pg_has_role(current_user, 'finpath_reminder', 'SET');
    END IF;
    IF can_use OR can_set THEN
        RAISE EXCEPTION 'V15 refused: role % would inherit or be able to SET ROLE finpath_reminder, which reads every consent row. Have the role that granted that membership revoke it (or re-grant WITH INHERIT FALSE, SET FALSE) and re-run.', current_user;
    END IF;
END
$$;
