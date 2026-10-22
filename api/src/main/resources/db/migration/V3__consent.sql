-- V3 (T31, INFRA, DPDP Act 2023): consent capture.
--
-- One row per grant of consent to a purpose statement. Withdrawal sets withdrawn_at (the row stays
-- until the account is deleted, as the audit record of what was agreed and when). The statement
-- text is stored verbatim so the record shows what the user actually saw, not just a version tag.
-- No account, card or identity-number columns.
--
-- The consent requirement for business tables is enforced in the database: every later per-user
-- business table's policy must put finpath_has_consent() in its WITH CHECK, so the first write
-- before consent is refused by Postgres, not only by service code (ConsentGateMetadataTest fails
-- by table name when a policy omits it). users is the one table written before consent (sign-in
-- upsert); consent itself must be writable by definition.

CREATE TABLE consent (
    id                  uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    purpose             text        NOT NULL CHECK (purpose ~ '^[A-Z_]{1,40}$'),
    statement_version   text        NOT NULL CHECK (char_length(statement_version) BETWEEN 1 AND 32),
    purpose_statement   text        NOT NULL CHECK (char_length(purpose_statement) BETWEEN 1 AND 4000),
    granted_at          timestamptz NOT NULL DEFAULT now(),
    withdrawn_at        timestamptz CHECK (withdrawn_at >= granted_at)
);

-- At most one live grant per user and purpose; makes granting idempotent.
CREATE UNIQUE INDEX consent_one_active_per_purpose ON consent (user_id, purpose) WHERE withdrawn_at IS NULL;

ALTER TABLE consent ENABLE ROW LEVEL SECURITY;
ALTER TABLE consent FORCE ROW LEVEL SECURITY;

CREATE POLICY consent_self ON consent
    USING (user_id = NULLIF(current_setting('app.current_user_id', true), '')::uuid)
    WITH CHECK (user_id = NULLIF(current_setting('app.current_user_id', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE, DELETE ON consent TO app_user;

-- True when the current user (app.current_user_id) holds a live consent to the CURRENT statement
-- version, which the application sets per transaction as app.consent_version (RlsTransactionManager).
-- A grant of an older version does not count, and an unset version fails closed. SECURITY INVOKER on
-- purpose: it reads consent through the caller's own row-level security, so it can only ever see
-- the caller's rows. Business-table policies call it in WITH CHECK.
CREATE FUNCTION finpath_has_consent()
    RETURNS boolean
    LANGUAGE sql
    STABLE
    SET search_path = pg_catalog, pg_temp
BEGIN ATOMIC
    SELECT EXISTS (
        SELECT 1 FROM public.consent c
        WHERE c.user_id = NULLIF(current_setting('app.current_user_id', true), '')::uuid
          AND c.statement_version = NULLIF(current_setting('app.consent_version', true), '')
          AND c.withdrawn_at IS NULL);
END;

REVOKE ALL ON FUNCTION finpath_has_consent() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION finpath_has_consent() TO app_user;

-- Purge of users who never gave consent, or withdrew it. Nobody owns a cross-tenant view of
-- users, so this follows the V2.1 pattern: a NOLOGIN role finpath_purge that owns one SECURITY
-- DEFINER function and holds narrow, policy-limited access (read id/created_at and the consent
-- liveness columns, delete users). EXECUTE is NOT granted to app_user: only the migrating/login
-- role (the scheduled purge job, which runs outside any request transaction) can call it.
-- It returns the ids it deleted so the caller can end their sessions. Deleting a user cascades to its consent rows. The grace period has a floor of one hour so a
-- caller cannot purge a user in the middle of first sign-in.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = 'finpath_purge') THEN
        CREATE ROLE finpath_purge NOLOGIN NOINHERIT;
    END IF;
END
$$;

GRANT SELECT (id, created_at) ON users TO finpath_purge;
GRANT DELETE ON users TO finpath_purge;
GRANT SELECT (user_id, withdrawn_at) ON consent TO finpath_purge;

CREATE POLICY users_purge_select ON users FOR SELECT TO finpath_purge USING (true);
CREATE POLICY users_purge_delete ON users FOR DELETE TO finpath_purge USING (true);
CREATE POLICY consent_purge_select ON consent FOR SELECT TO finpath_purge USING (true);

CREATE FUNCTION finpath_purge_unconsented_users(grace interval)
    RETURNS uuid[]
    LANGUAGE sql
    VOLATILE
    STRICT
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
    WITH purged AS (
        DELETE FROM public.users u
        WHERE NOT EXISTS (SELECT 1 FROM public.consent c WHERE c.user_id = u.id AND c.withdrawn_at IS NULL)
          AND greatest(u.created_at,
                       coalesce((SELECT max(c.withdrawn_at) FROM public.consent c WHERE c.user_id = u.id), u.created_at))
              < now() - greatest(grace, interval '1 hour')
        RETURNING u.id)
    SELECT coalesce(array_agg(id), '{}'::uuid[]) FROM purged;
$$;

DO $$
BEGIN
    IF current_setting('server_version_num')::int >= 160000 THEN
        EXECUTE 'GRANT finpath_purge TO CURRENT_USER WITH INHERIT TRUE, SET TRUE';
    ELSE
        EXECUTE 'GRANT finpath_purge TO CURRENT_USER';
    END IF;
END
$$;
GRANT CREATE ON SCHEMA public TO finpath_purge;
ALTER FUNCTION finpath_purge_unconsented_users(interval) OWNER TO finpath_purge;
REVOKE CREATE ON SCHEMA public FROM finpath_purge;
REVOKE ALL ON FUNCTION finpath_purge_unconsented_users(interval) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION finpath_purge_unconsented_users(interval) TO CURRENT_USER;
REVOKE finpath_purge FROM CURRENT_USER;

-- Fail closed, as in V2.1: the migrating role must not keep the ability to act as finpath_purge,
-- which can read every user's id and delete any user.
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
    can_use := pg_catalog.pg_has_role(current_user, 'finpath_purge', 'USAGE');
    IF current_setting('server_version_num')::int >= 160000 THEN
        can_set := pg_catalog.pg_has_role(current_user, 'finpath_purge', 'SET');
    END IF;
    IF can_use OR can_set THEN
        RAISE EXCEPTION 'V3 refused: role % would inherit or be able to SET ROLE finpath_purge, which can delete any users row. Have the role that granted that membership revoke it (or re-grant WITH INHERIT FALSE, SET FALSE) and re-run.', current_user;
    END IF;
END
$$;
