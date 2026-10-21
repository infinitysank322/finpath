-- V1 baseline (T03). Forward-only: never edit this file once applied; add V2, V3, ...
--
-- MONEY CONVENTION
-- Every money amount is stored as integer paise (1 INR = 100 paise) in a BIGINT
-- column, named with a _paise suffix. Never real, float4, float8, double precision
-- or the money type. All arithmetic on amounts happens in the api rules package;
-- SQL only stores and sums paise. SchemaLintTest fails the build on float columns.
--
-- No business tables here: users arrives as V2 (T28).

-- Application role targeted by the row-level-security policies added in T30.
-- NOLOGIN and no password: login credentials come from the environment, never from
-- a migration. Created only if missing so the baseline is safe to run against a
-- database (such as Neon) where the role was provisioned out of band.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = 'app_user') THEN
        CREATE ROLE app_user NOLOGIN;
    END IF;
END
$$;

COMMENT ON SCHEMA public IS
    'FinPath. Money is stored as integer paise in bigint columns; no floating-point money columns.';
