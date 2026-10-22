# T40 — category_budget and budget-status (FR-10, design)

Code: `api/.../budget` (controller, service, repository), additions to `api/.../rules/BudgetRules`, migration
`V8__category_budget.sql`. Follows T38 (conventions, RLS, registry) and T36 (reads `TransactionRepository`). Web is
T45; no UI here. Rules T23 are reused unchanged: `levelFor`, `status`, `newAlert`.

## 1. Rules (`api/.../rules`, pure)
Existing, called as is: `BudgetRules.status(budget, spent, config) -> Status(budget, spent, remaining, usedFraction,
level)`, `levelFor`, `newAlert(alreadyFired, current)`. The service never divides, compares or sums money.
New (rules-engine): `BudgetRules.spentByCategory(List<Spend(categoryCode, direction, amountPaise)>) ->
Map<String, Money>`: sums **OUT rows only**, per category code, `Math.addExact`; IN rows ignored. Empty map is valid
(missing category = `Money.ZERO`, the service uses `getOrDefault`). `RuleConfig` thresholds (80/100) come from the
existing bean.

Tests (`BudgetRulesTest` additions): IN ignored; two OUT rows in one category sum; categories kept apart; empty list.
Golden: add fake keys to `persona.sample.properties` (owner mirrors them in `persona.local.properties`):
`budget.category_paise`, `budget.spent_paise` (below 80%), `budget.spent_warn_paise` (at least 80%, below 100%),
`budget.spent_breach_paise` (at least 100%). `RulesGoldenTest` asserts `status(...)` level, remaining and fraction for
each spend, read from `GoldenFixture`; no persona number is hard-coded in code or tests. Boundary cases (79.9%,
80.0%, 100%) stay in `BudgetRulesTest`.

## 2. Data (V8)
`category_budget(id uuid pk default gen_random_uuid(), user_id uuid NOT NULL -> users ON DELETE CASCADE,
cycle_start date NOT NULL, category_id uuid NOT NULL -> category ON DELETE RESTRICT, budget_paise bigint NOT NULL
CHECK (>= 0), last_alert_level text NOT NULL DEFAULT 'OK' CHECK IN ('OK','WARN','BREACH'), created_at timestamptz
NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(), UNIQUE (user_id, cycle_start, category_id))`.
- Keyed by `cycle_start` (the date `CycleCalendar` returns), **not** an FK to `plan_cycle`: a budget can be set for a
  cycle with no plan row yet, and a start-day change cannot orphan it.
- No free-text column; the category identifies the line. No account, card or ID numbers.
- RLS `ENABLE` + `FORCE`, policy `category_budget_self` as V6/V7 (`USING user_id = current`, `WITH CHECK ... AND
  finpath_has_consent()`); `GRANT SELECT, INSERT, UPDATE, DELETE` to `app_user`.

## 3. Threshold events (fire once)
`last_alert_level` is the highest level already announced for that row. `BudgetAlertService.evaluate(userId,
cycleStart) -> List<Event(categoryCode, level)>`, one RLS transaction:
1. Load budgets of the cycle and the cycle's OUT spend (`TransactionRepository.listBetween`, rows reduced with
   `spentByCategory`).
2. Per budget: `current = levelFor(budget, spent, config)`; `newAlert(lastAlertLevel, current)`.
3. If present: `UPDATE ... SET last_alert_level = :new WHERE id = :id AND last_alert_level = :old`; emit the event only
   if one row updated (concurrent callers cannot both fire).
Result: 79.9% -> none; first call at 80.0% -> one `WARN`; repeat -> none; jump to 100% -> one `BREACH`, no `WARN`;
WARN then BREACH -> one each, never repeated. Level never lowers when spend falls (monotonic, as T23).
Triggers: `POST /budgets/evaluate`, and `ExpenseService` calls `evaluate` after create, update and delete of an OUT
transaction in a budgeted cycle (one-line hook, failure is swallowed and logged without content so logging an
expense never fails). Events are returned to the caller; no push or email in MVP (Q3).
Changing `budget_paise` (upsert with a different value) resets `last_alert_level` to `OK` so the new limit can alert.

## 4. API (`/budgets`, authenticated; owner = `@CurrentUserId`, a `userId` in a body is ignored)
`cycleStart` is any date in the wanted cycle (default: today, `Asia/Kolkata`, from the service `Clock`); the service
normalises via `CycleCalendar.cycleFor(date, cycle_start_day)` and stores `cycle.start`.
| Call | Body / result |
| --- | --- |
| `PUT /budgets/{categoryCode}?cycleStart=` | `{budgetPaise}` upsert -> 200 + line view |
| `DELETE /budgets/{categoryCode}?cycleStart=` | 204 |
| `GET /budgets/status?cycleStart=` | status below |
| `POST /budgets/evaluate?cycleStart=` | `{events:[{categoryCode, level}]}` (section 3) |
Status: `{cycleStart, cycleEndExclusive, lines:[{categoryCode, budgetPaise, spentPaise, remainingPaise, usedFraction
(string decimal, null if budget 0), level, lastAlertLevel}]}`, ordered by category code. Every field is copied from
`BudgetRules.Status` or the row; `GET` is read-only and never changes `last_alert_level`.
Errors (fixed codes as T35/T38): 400 `invalid_request` (unknown category, `budgetPaise` not an integer or < 0, bad
date); 403 `consent_required` on PUT/DELETE/evaluate before consent; 404 `not_found` for DELETE of a missing line.
No amount or body is logged. Add the endpoints to `OpenApiTest`.

## 5. `[+TABLE]` trio for `category_budget`
- Export: `new TableRef("category_budget","user_id", ROW_WITH_CATEGORY_CODE)` in `DataSubjectRegistry.EXPORT` after
  `transaction`. Money key `budget_paise`.
- Deletion: add to `DELETE` before `users`.
- RLS: `CategoryBudgetCrossTenantTest`: B cannot select, update or delete A's budget; insert for another user, or
  without consent, is rejected. `DataSubjectRegistryTest` must pass.
- `BudgetStatusDbTest` (acceptance): seed OUT transactions in one cycle from `GoldenFixture` (`budget.*` keys, sample
  fallback); the endpoint line equals `BudgetRules.status(...)` computed in the test from the same fixture values;
  IN rows and rows outside the cycle are excluded; user B sees nothing of A. `BudgetAlertDbTest`: 79.9% -> no event;
  80% -> exactly one `WARN` across two `evaluate` calls; then 100% -> exactly one `BREACH`; raising the budget resets.

## 6. Screens (T45 consumes; T77 reads the same status)
Budget screen at 380 px, light and dark: a row per category with a progress bar, `usedFraction` as the percentage,
`remainingPaise`, and a WARN (80%) or BREACH (100%) banner from `level`, contrast at least 4.5:1. Edit via a
bottom sheet calling the PUT. Components render API numbers only; INR lakh/crore formatting is UI-only. T45 calls
`POST /budgets/evaluate` on open to show new events once.

## 7. Open questions (assumptions)
1. Spend counts all OUT rows in the cycle by category, including RECURRING postings. Assumed.
2. Budget is per category per cycle; no automatic carry-over to the next cycle (a "copy last cycle" action is later).
3. Alerts are returned in-app only; no notification channel until a later task.
4. A budget of 0 is allowed (any spend is BREACH, per T23).
5. Changing the cycle start day mid-cycle can leave budgets keyed to an old `cycle_start`; accepted for MVP.

## 8. Plan coverage
Covers **T40**. Feeds T41, T45, T77. Additions not in PLAN.md: `BudgetRules.spentByCategory`, `budget.*` fixture keys
(sample plus owner's local file), `POST /budgets/evaluate`, the `ExpenseService` evaluate hook, `last_alert_level`
reset on budget change.
