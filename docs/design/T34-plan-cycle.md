# T34 — plan_cycle and cycle-day change (FR-02, as-built design)

Code: `api/src/main/java/com/finpath/plan`, migration `V4__plan_cycle.sql`. No money maths: surplus and
allocations are only stored (computed in T64 by the rules package).

## 1. Table (V4)
`plan_cycle(id, user_id → users ON DELETE CASCADE, cycle_start, cycle_end, start_day, surplus bigint,
allocations jsonb, status, created_at)`.
- `cycle_end` is exclusive (the next cycle's first day); both bounds come from `CycleCalendar`.
- `surplus` is paise, NULL until the allocation run; `allocations` is a JSON array, `'[]'` until then.
- `status`: OPEN (current), CLOSED (ended), SUPERSEDED (still running when the start day changed).
- `UNIQUE (user_id, cycle_start)`; partial unique index `plan_cycle_one_open_per_user` allows one OPEN row.
- RLS ENABLE + FORCE, policy `plan_cycle_self`: `USING (user_id = <cur>)`,
  `WITH CHECK (user_id = <cur> AND finpath_has_consent())`; grants to `app_user`.
- `[+TABLE]` trio: export `[users, consent, plan_cycle]`, delete `[consent, plan_cycle, users]`,
  `PlanCycleCrossTenantTest`.

## 2. Materialisation
`PlanCycleService.current(userId)`: require consent (clean 403), then in one RLS transaction lock the user
row (`FOR UPDATE`, serialises concurrent calls), compute `CycleCalendar.cycleFor(today, startDay)`, retire any
other OPEN row (CLOSED if it had ended, else SUPERSEDED), then upsert the current row. The upsert only writes
when something differs, so repeated calls change nothing. "Today" is the date in `Asia/Kolkata` from a
`Clock`; `rules` never reads the clock.

## 3. Changing the start day
`PUT /plan-cycles/start-day {"startDay": 1..31}` (principal only; any `userId` in the body is ignored).
In one transaction: update `users.cycle_start_day`, then re-derive as above. Result for 25 -> 1 on 22 Oct:
the open 25 Sep row becomes SUPERSEDED, a 1 Oct row is OPEN, exactly one OPEN row, nothing deleted.
Changing back revives the original row for that start date. If the start date is unchanged but the end moves
(day 30 vs 31 in February), the stored surplus and allocations were computed for other bounds and are cleared;
if both bounds are unchanged they are kept. `GET /plan-cycles/current` returns the same view:
`cycleStart, cycleEndExclusive, startDay, status, surplusPaise, allocations, daysElapsed, daysLeft`.

## 4. Errors (docs/api-conventions.md, fixed codes)
400 `invalid_request` (missing, null, non-integer or out-of-range day), 403 `consent_required`,
404 `not_found` (no visible users row). Nothing logs row content.

## 5. No YearMonth
`NoYearMonthTest` fails if any file under `api/src/main` mentions `YearMonth`. To satisfy it,
`EmiRollOff.Step.cycleMonth()` (T15, the only use) was removed; `cycleId()` (the cycle's start date) is the id.
Tests still use `YearMonth` locally to compare against the golden fixture's `yyyy-MM` keys.
