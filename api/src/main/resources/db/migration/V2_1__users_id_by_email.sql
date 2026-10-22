-- V2.1 (T29, INFRA): sign-in lookup of a user id by verified email.
--
-- Why: V2 FORCEs row-level security on users, keyed on app.current_user_id. At sign-in the id is
-- what we are trying to find, so under any non-superuser role a plain SELECT by email sees
-- nothing and a returning user could never sign in again. This adds the narrowest bypass:
--
--   * finpath_signin: a NOLOGIN role that owns only the function below. It gets SELECT on the
--     id and email columns only, and a SELECT-only policy on users that applies to it alone.
--     FORCE RLS still binds the table owner and app_user exactly as before.
--   * finpath_user_id_by_email(text): SECURITY DEFINER, search_path pinned, exact match on the
--     lower-cased email, returns one uuid or NULL. No set-returning form, no pattern matching,
--     so it cannot enumerate rows. EXECUTE is revoked from PUBLIC and granted to app_user.
--
-- Versioned 2.1 because V3-V13 are reserved for the tables listed in docs/PLAN.md section 5.
-- No table is created here, so the [+TABLE] export/delete registries are unchanged.

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = 'finpath_signin') THEN
        CREATE ROLE finpath_signin NOLOGIN NOINHERIT;
    END IF;
END
$$;

GRANT SELECT (id, email) ON users TO finpath_signin;

CREATE POLICY users_signin_lookup ON users
    FOR SELECT
    TO finpath_signin
    USING (true);

CREATE FUNCTION finpath_user_id_by_email(lookup_email text)
    RETURNS uuid
    LANGUAGE sql
    STABLE
    STRICT
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
BEGIN ATOMIC
    SELECT u.id FROM public.users u WHERE u.email = lower(lookup_email);
END;

-- Hand the function to finpath_signin and set its grants. The migrating role needs membership
-- only for these statements, then gives it up so it does not keep the lookup policy.
-- EXECUTE goes to app_user (the RLS-bound role from T30) and to the migrating role, which is
-- also the role the app connects as until T30 switches requests to app_user.
-- A new owner needs CREATE on the schema at the moment of the change only (superusers skip this
-- check, a Neon owner does not), so CREATE is granted around ALTER OWNER and taken back.
--
-- PostgreSQL 16+: a non-superuser creator keeps an implicit ADMIN-only grant (grantor: bootstrap
-- superuser, INHERIT and SET false). If createrole_self_grant is set, CREATE ROLE also adds a
-- second grant whose grantor is the creator itself, with INHERIT and/or SET true. A plain
-- `GRANT ... TO CURRENT_USER` would then be a no-op that keeps its flags (and ALTER OWNER fails
-- without SET), so the flags are set explicitly here; the REVOKE below removes this self-granted
-- membership, whatever createrole_self_grant made of it, leaving only the ADMIN-only grant.
DO $$
BEGIN
    IF current_setting('server_version_num')::int >= 160000 THEN
        EXECUTE 'GRANT finpath_signin TO CURRENT_USER WITH INHERIT TRUE, SET TRUE';
    ELSE
        EXECUTE 'GRANT finpath_signin TO CURRENT_USER';
    END IF;
END
$$;
GRANT CREATE ON SCHEMA public TO finpath_signin;
ALTER FUNCTION finpath_user_id_by_email(text) OWNER TO finpath_signin;
REVOKE CREATE ON SCHEMA public FROM finpath_signin;
REVOKE ALL ON FUNCTION finpath_user_id_by_email(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION finpath_user_id_by_email(text) TO app_user;
GRANT EXECUTE ON FUNCTION finpath_user_id_by_email(text) TO CURRENT_USER;
REVOKE finpath_signin FROM CURRENT_USER;

-- Fail closed. If the migrating role (the app's connection role until T30) can still use or SET ROLE
-- to finpath_signin, it would hold users_signin_lookup USING (true) and could read every user row.
-- The REVOKE above removes every membership this role granted itself (including one added by
-- createrole_self_grant), so this only fires for a membership granted by another role, which this
-- role cannot revoke (e.g. finpath_signin provisioned out of band and granted WITH INHERIT). Abort,
-- rolling the migration back, rather than leave that in place. Fix: have the granting role revoke
-- that membership (or re-grant it WITH INHERIT FALSE, SET FALSE) and re-run.
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
    can_use := pg_catalog.pg_has_role(current_user, 'finpath_signin', 'USAGE');
    IF current_setting('server_version_num')::int >= 160000 THEN
        can_set := pg_catalog.pg_has_role(current_user, 'finpath_signin', 'SET');
    END IF;
    IF can_use OR can_set THEN
        RAISE EXCEPTION 'V2_1 refused: role % would inherit or be able to SET ROLE finpath_signin, which reads every users row. Have the role that granted that membership revoke it (or re-grant WITH INHERIT FALSE, SET FALSE) and re-run.', current_user;
    END IF;
END
$$;
