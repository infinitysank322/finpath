# T61 — goal table, CRUD and status (FR-19, design)

Code: `api/.../goal` (`GoalController`, `GoalService`, `GoalRepository`), migration `V10__goal.sql`. Follows T48 (loan)
conventions. No new rules code: the status calls `DatedGoal.evaluate` (T19) and the service does no money arithmetic.

## Data (V10)
`goal(id, user_id -> users ON DELETE CASCADE, type, target_amount_paise bigint > 0, target_date date,
current_amount_paise bigint >= 0 default 0, priority integer 1..10 default 5, created_at, updated_at)`.
`type` is a closed list: emergency, marriage, education, home, health, vehicle, travel, other. No free text, so no
place for an account number. RLS `ENABLE` + `FORCE`, policy `goal_self` (USING owner; WITH CHECK owner and
`finpath_has_consent()`), grants to `app_user`. Columns carry the `_paise` suffix like `loan`.

## API (`/goals`, authenticated; owner = `@CurrentUserId`, a `userId` in a body is ignored)
- `POST /goals`, `GET /goals` (priority, then date), `GET /goals/{id}`, `PUT /goals/{id}` (full replace), `DELETE /goals/{id}`.
  Body `{type, targetAmountPaise, targetDate, currentAmountPaise?, priority?}`; amounts and priority are strict JSON integers.
- `GET /goals/{id}/status?plannedMonthlyPaise=N` -> `{cyclesRemaining, shortfallPaise, monthlyNeedPaise,
  plannedMonthlyPaise, status, onTrack, instrumentClass}`. `status` is `DatedGoal.Status` (ACHIEVED / ON_TRACK /
  BEHIND / PAST_DUE); `onTrack` is true for ACHIEVED and ON_TRACK; `instrumentClass` is SAFE_ONLY under the equity
  horizon floor (`RuleConfig`), else EQUITY_ALLOWED. Reference date is today in Asia/Kolkata and the user's
  `cycle_start_day`. `plannedMonthlyPaise` is required (400 if missing or negative) and not stored: on-track needs a
  plan, and the allocation waterfall that supplies it is T64. Monthly need and shortfall are never stored.
- Errors as loan: 400 `invalid_request` (before any write), 403 `consent_required` (writes), 404 `not_found` for an
  unknown id or another user's row. Nothing logs amounts.

## `[+TABLE]` trio
Export and delete registry entries (`goal` after `loan`, before `users`); `GoalCrossTenantTest` (read, update,
reassign, delete, foreign insert, no-consent insert); `RlsMetadataTest`, `DataSubjectRegistryTest` and the consent-gate
metadata test cover the table automatically. `DataSubjectRightsDbTest` seeds and exports a goal row.

## Tests
`GoalApiDbTest`: monthly need and cycles read from the golden fixture (target, cycles, monthly; no literals) and
compared to `DatedGoal` on the same inputs and to the fixture within the 1% tolerance; on-track at exactly the need,
behind one paise short; 34-month horizon SAFE_ONLY, 37-month EQUITY_ALLOWED (synthetic amounts); achieved, past due;
validation; DB CHECK; cross-user 404s; body userId ignored; consent.

## Open questions
1. Goal type list is a first guess; widening it needs a migration.
2. `plannedMonthlyPaise` as a query parameter is a stopgap until T64 persists allocations per goal.
3. `StrictInteger` (loan package) was made public for reuse.
