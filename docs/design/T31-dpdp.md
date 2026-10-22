# T31 — DPDP spine: consent, export, deletion (as-built design)

Code: `api/src/main/java/com/finpath/dpdp`, `.../auth/{DeletedUsers,SessionRevoker}.java`,
migration `V3__consent.sql`. No money maths.

## 1. Consent table (V3)
`consent(id, user_id → users ON DELETE CASCADE, purpose, statement_version, purpose_statement, granted_at,
withdrawn_at)`. The statement text is stored verbatim (what the user saw). One live row per (user, purpose)
via a partial unique index, so granting is idempotent. No account/card/identity columns. RLS ENABLE + FORCE +
`consent_self` policy; grants to `app_user`. Registered in both registries: export `[users, consent]`,
delete `[consent, users]` (children before `users`).

## 2. Endpoints (principal only; no id from path/query/body)
| Endpoint | Behaviour |
| --- | --- |
| `GET /me/export` | `{"format":1,"exportedAt":…,"tables":{<table>:[rows]}}`, one key per registry table (empty array if no rows), `Content-Disposition: attachment`, `no-store`. Generic: `to_jsonb(row)` per registry entry in one RLS transaction. Money stays integer paise. |
| `DELETE /me` | Deletes the rows of every delete-registry table in one transaction, then (only after commit) `SessionRevoker`: `DeletedUsers.markDeleted`, `RefreshTokenStore.revokeAll`, `OneTimeCodeStore.revokeAll`. 204. A failed delete revokes nothing. |
| `GET /me/consent` | current statement (`purpose`, `version`, `text`) and the live consent or null. |
| `POST /me/consent` | `{purpose, statementVersion}`; must name the current statement, else 400 `invalid_request`. 201 with the live consent (idempotent). |
| `DELETE /me/consent` | withdraw (sets `withdrawn_at`); 204. Account is purged after the grace period; `DELETE /me` erases at once. |
Errors are fixed codes only (`consent_required` 403, `invalid_request` 400).

## 3. Consent before the first business write
Enforced in Postgres: `finpath_has_consent()` (SECURITY INVOKER, so it only sees the caller's own consent via
RLS) must appear in the WITH CHECK of every business table's policy. `ConsentGateMetadataTest` fails by table
name when a migrated table (other than `users`, written by sign-in, and `consent`) lacks it. `ConsentGateDbTest`
proves it against a business table (created in a rolled-back transaction): write refused before consent,
allowed after, refused after withdrawal. Services may call `ConsentService.require(userId)` first to return a
clean 403 instead of an RLS violation. Reads are not gated. The gate honours the statement version: `finpath_has_consent()` also requires
`statement_version = current_setting('app.consent_version')`, which `RlsTransactionManager.bind` sets per
transaction from `ConsentStatement.CURRENT.version()` (unset fails closed). `ConsentService.current/require`,
`GET /me/consent` (`given`) use the same filter, so consent to an older wording does not count; re-granting
retires the stale live row and records the new one. Bumping the wording is a code change only (no migration).
Policy template for later `[+TABLE]` tasks:
`USING (user_id = <cur>) WITH CHECK (user_id = <cur> AND finpath_has_consent())`.

Current statement version `2026-10-v2` names Google name and email, risk profile and target age, the 7-day
retention after withdrawal, and `DELETE /me` for immediate erasure. A stale or wrong-purpose grant raises
`ConsentStatementMismatchException` (mapped to 400 `invalid_request`; no other exception is mapped).

## 4. Purge of users with no live consent
`UnconsentedUserPurge` (hourly, 10 min initial delay, grace `finpath.dpdp.purge-grace` default 7d) calls
`finpath_purge_unconsented_users(interval)`: SECURITY DEFINER, owned by NOLOGIN role `finpath_purge` (same
pattern and fail-closed membership check as V2.1), column-limited SELECT plus DELETE on `users` through
policies that apply to that role only. EXECUTE is granted to the migrating/login role only, NOT `app_user`
(tested). Grace is measured from the later of `created_at` and the last withdrawal, floor one hour in SQL.
The function returns the purged ids (`uuid[]`); each goes through `SessionRevoker` exactly as `DELETE /me` does (tested: refresh token and JWT of a purged user fail). It runs outside any transaction (the RLS manager would hide other users). Cascade removes consent rows. Logs a
count only; a failed or slow (cold-start) run is a warning and retried next interval. Allow-listed in
`RlsMetadataTest.SECURITY_DEFINER_ALLOW_LIST`.

## 5. After deletion, old credentials fail
- Access token: JWT decoder has a `subjectNotDeleted` validator over `DeletedUsers` (entry lives access TTL +
  2 min, covering the 30 s skew). Residual risk, accepted: in memory, so after an API restart within 15 min of
  deletion an old JWT verifies, but reaches nothing (rows gone, RLS matches none, consent check fails).
- Refresh tokens: `revokeAll` bumps the user's epoch and removes all hashes (spent included); `/auth/refresh`
  also filters deleted subs.
- One-time codes: new `OneTimeCodeStore.revokeAll(userId)`; `/auth/token` also filters deleted subs.
- Re-sign-in with the same email later creates a new user id.

## 6. Decisions
- Identity stays email-based (T29 section 7); `google_sub` not added.
- Deployment: the login role must be able to run the purge function (it is the migrating role) and `SET ROLE app_user` (T30).
  Roles `finpath_signin` and `finpath_purge` are cluster-wide; a cluster where either pre-exists without admin
  rights for the migrating role fails V2.1/V3 loudly.
- `UsersForeignKeyCascadeTest` requires every FK to `users` to be ON DELETE CASCADE (static and catalog).
- Tests that wipe the DB need the scratch-owner harness to park `finpath_purge` as well as `finpath_signin`.

## Coverage
PLAN T31 and its T29 follow-ups (revokeAll, OneTimeCodeStore revoke, deleted-sub rejection, consent proven
against a business write, purge of never/declined-consent users).
