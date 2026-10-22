# T30 — Row-level security wiring

- `RlsTransactionManager` (replaces Boot's manager via `RlsAutoConfiguration`) runs `SET LOCAL ROLE app_user`
  and `set_config('app.current_user_id', <JWT sub>, true)` at the start of every transaction. Both are
  transaction-local: no leak to pooled connections, works behind a transaction-mode pooler. No user (sign-in)
  leaves the id unset, so policies match nothing.
- Deliberate trade-off: the login role is not `app_user` itself; `SET LOCAL ROLE` approximates "connects as
  app_user" (needed for Neon pooling). Statements outside a transaction run as the login role; `RlsRoleGuard`
  warns when that role bypasses RLS. All data access must be `@Transactional`. Injected SQL could `RESET ROLE`.
- Required out-of-band step: the login role must be able to `SET ROLE app_user`. Superusers can; on Neon run
  `GRANT app_user TO <login> WITH SET TRUE` (PG16+). `RlsRoleGuard` fails startup with that hint otherwise.
- If JPA is added later, its transaction manager must apply `RlsTransactionManager.bind` too.
- Every table needs ENABLE + FORCE RLS and a policy (`RlsMetadataTest`, static and against a database);
  SECURITY DEFINER functions need an entry in `RlsMetadataTest.SECURITY_DEFINER_ALLOW_LIST`.
- New per-user tables: subclass `CrossTenantTest`.
