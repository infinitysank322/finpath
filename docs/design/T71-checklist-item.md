# T71 — checklist_item, per-cycle generation, mark-done, streak (FR-26, design)

Code: `api/.../checklist` (`ChecklistController`, `ChecklistService`, `ChecklistRepository`), new rules class
`rules/Checklist`, migration `V13__checklist_item.sql`. Pattern: T70 (V12, registry, CrossTenantTest).

## Data
`checklist_item(id, user_id -> users CASCADE, plan_cycle_id -> plan_cycle CASCADE, type, source_ref, amount_paise, done_at
timestamptz NULL, created_at)`.
- `type` CHECK IN ('EMI','SIP','GOAL_TRANSFER'); `source_ref text NOT NULL`; `amount_paise bigint CHECK (>= 0)`.
- `UNIQUE (plan_cycle_id, type, source_ref)` is the idempotency key. Index `(user_id, plan_cycle_id)`.
- No account, folio or ID columns; `source_ref` is a loan/goal uuid or a step name, never a user-typed number.
- RLS ENABLE + FORCE, policy `checklist_item_self` (USING owner; WITH CHECK owner and `finpath_has_consent()`),
  as V12. Grants: SELECT, INSERT, DELETE, and `UPDATE (done_at)` only (amount and keys are immutable).

## Generation sources (read, never recomputed)
| type | source_ref | amount_paise from |
|---|---|---|
| EMI | `loan.id` | `loan.emi_paise` (stated or derived, T48) for each loan with emi_paise > 0, incl. bullet interest and card_emi |
| GOAL_TRANSFER | `goal.id` | allocation line `SAFE_GOALS` with `ref` = goal id (`plan_cycle.allocations`, T64) |
| SIP | step name | allocation lines `HABIT_SIP` and `LONG_TERM_SIP` (ref null) |
Holdings carry no SIP schedule, so SIP items come only from allocations. Amounts are frozen at generation.
If allocations are `'[]'` (T64 not run) only EMI items are produced; a later generate adds the rest.

## Endpoints (owner = `@CurrentUserId`; body userId ignored; 403 `consent_required`; `Cache-Control: no-store`)
- `POST /checklist/generate` -> current OPEN cycle (via `PlanCycleService.current`); `INSERT ... ON CONFLICT DO NOTHING`;
  returns the list view. Repeat calls add nothing. 200.
- `GET /checklist` -> `{cycleStart, items:[{id,type,sourceRef,label,amountPaise,done,doneAt}], doneCount, totalCount}`.
  Optional `?cycleStart=` for a past cycle; unknown -> 404 `not_found`.
- `POST /checklist/items/{id}/done` -> 200 with the item. `UPDATE SET done_at = COALESCE(done_at, now()) WHERE id=?`:
  second call keeps the first `done_at`, same 200. Other user's or missing id -> 404 (RLS hides it). No un-done endpoint.
- `GET /checklist/streak` -> `{streak, history:[{cycleStart, done}]}` (last 12 cycles, oldest first, for T78 heatmap).

## Layer ownership (service does no arithmetic)
- rules `Checklist.cycleComplete(List<Boolean> itemDone)`: true iff non-empty and all true (empty = not done).
- rules `Statistics.consistencyStreak(List<Boolean> oldestToNewest)` (T25, exists): trailing run; missed latest = 0.
- Service: loads items per CLOSED cycle, maps each through `cycleComplete`, appends the OPEN cycle only if complete
  (an unfinished current month does not reset the streak), calls `consistencyStreak`. `doneCount/totalCount` are
  counts from SQL `count(*) FILTER (WHERE done_at IS NOT NULL)`; no summing of amounts anywhere.
- Web (T78) renders `streak` and `history` as given.

## `[+TABLE]` trio
Registry: EXPORT after `net_worth_snapshot`; DELETE before `plan_cycle` (with `net_worth_snapshot`);
`ChecklistItemCrossTenantTest` (B cannot SELECT/UPDATE/DELETE/INSERT-as-A's row; no-consent insert fails);
`DataSubjectRightsDbTest` seeds and exports a row, `DELETE /me` leaves zero.

## Tests
- `ChecklistTest` (rules): empty -> false; all done -> true; one open -> false.
- `StatisticsTest` already covers streak; add a service-level case: 3 done cycles + missed -> 0, not 1.
- `ChecklistApiDbTest`: persona (home-loan EMI 40,032 + other loans, SAFE_GOALS marriage line, HABIT/LONG_TERM SIP)
  gives one item per EMI, per goal line, per SIP line; generate twice -> same count; done twice -> same `done_at`;
  amounts equal source rows; cross-user id -> 404; no consent -> 403; streak equals `consistencyStreak` of the same flags.
- `ChecklistItemCrossTenantTest`, registry test (fails by table name if unregistered), no-identifier-column test as T65.

## Open questions (assumptions)
1. Cycle with no items counts as missed. 2. Allocation re-run after generation does not change existing amounts or
add/remove items (EMI changes likewise); regenerate only inserts new keys. 3. Goal with no SAFE_GOALS line this
cycle (equity goal, met from SIP) gets no item. 4. Closed cycles never generate retroactively (as T70).

Covers T71. Adds `rules/Checklist.cycleComplete` and a history list in the streak response, not in the plan.
