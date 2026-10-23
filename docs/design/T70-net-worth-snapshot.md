# T70 — net_worth_snapshot written at cycle close (FR-26, design)

Code: `api/.../networth` (`NetWorthSnapshots`, `NetWorthService`, `NetWorthController`), a hook in
`PlanCycleService.derive`, migration `V12__net_worth_snapshot.sql`.

## Data
`net_worth_snapshot(id, user_id, plan_cycle_id UNIQUE -> plan_cycle ON DELETE CASCADE, cycle_start, assets_paise,
liabilities_paise, net_worth_paise, created_at)`; paise bigint, `net_worth_paise = assets - liabilities` (CHECK; may be
negative). RLS `ENABLE` + `FORCE`, policy `net_worth_snapshot_self` (WITH CHECK owner and `finpath_has_consent()`).
Grants: SELECT, INSERT, DELETE only; rows are immutable.

## When it is written
Only inside `PlanCycleService.derive`, in the same transaction that flips an OPEN cycle to CLOSED (cycle end passed).
Assets = SUM(holding.current_value_paise), liabilities = SUM(loan.principal_outstanding_paise) at that moment; the
figure is `Statistics.netWorth` (rules package). `ON CONFLICT (plan_cycle_id) DO NOTHING` makes it exactly one per
cycle even if the cycle is revived and closed again. A SUPERSEDED cycle (start-day change) gets none.

## Backfill is rejected
No write endpoint exists (`POST /net-worth` is 405), `derive` only touches rows it flips to CLOSED in that call, and a
cycle that is already CLOSED without a snapshot is never snapshotted later. Consequence: if the user is away for
several cycles, only the cycle open when they return is snapshotted, using values as of the return, not as of its end.

## API
`GET /net-worth` -> `{points: [{cycleStart, assetsPaise, liabilitiesPaise, netWorthPaise}]}` oldest first, last 120.
403 `consent_required` without consent. `Cache-Control: no-store`.

## `[+TABLE]` trio
Registry entries (export after `holding`; delete before `plan_cycle`), `NetWorthSnapshotCrossTenantTest`,
`DataSubjectRightsDbTest` seeds and exports a row.
