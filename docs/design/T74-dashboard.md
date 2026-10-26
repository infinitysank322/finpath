# T74 — GET /dashboard single aggregate (FR-10, FR-21, FR-26, design)

Code: new package `api/.../dashboard` (`DashboardController`, `DashboardService`). It orchestrates existing services and
holds no arithmetic. No new table. Consumers: T75 (ring, spent vs budget), T76 (net worth, FI card), T79 (cache), T82.

## Audit: what already exists
| Figure | Exposed today? | Source found |
|---|---|---|
| Cycle start/end, daysElapsed, daysLeft | yes | `PlanCycleService.current` |
| Spent vs budget per category, level | yes | `BudgetService.status` (budgeted categories only) |
| Total budget, total spent, % used | no | rule `BudgetRules.status`/`sum` exist; `RebalanceService.preview` builds the totals privately |
| Safe-to-spend/day | no (only before/after in rebalance) | rule `BudgetRules.safeToSpendPerDay` |
| Net-worth series | yes | `NetWorthService.series` (snapshots) |
| Net worth now | no | rule `Statistics.netWorth`; only used at cycle close |
| FI date | stateless only | `FiPlannerService.plan` needs request inputs; band `fiYears`/`fiDate` |
| FI delta vs last cycle | no, nothing stored | see Open question 1 |
| Savings rate, discretionary share | rules only, wired nowhere | `Statistics.savingsRate`/`discretionaryShare`; inputs from `plan_cycle.surplus`, `IncomeService.cycleTotal`, `ExpenseService.tagSummary` |
| EMI-to-income | yes | `LoanService.guard` |
| EF months covered | yes | `EmergencyFundService.size` |
| Debt-free date | no | `Statistics.debtFreeCycle(plan)` wired nowhere; `LoanService.payoffPlan` has dates |
| Streak | yes | `ChecklistService.streak` |

## Additions to existing services (thin, rules do the maths)
- `BudgetService.cycleSummary(userId)`: totalBudget = `BudgetRules.sum` of budget rows, totalSpent = all OUT spend in the
  cycle (same definition as `RebalanceService`, so ring and rebalance agree), then `BudgetRules.status` and
  `safeToSpendPerDay`. Returns paise, `usedFraction`, `level`, `safeToSpendPerDayPaise`.
- `NetWorthService.current(userId)`: `HoldingService.aggregate` total minus sum of open `loan.principal_outstanding_paise`,
  through `Statistics.netWorth` (same inputs as the T70 snapshot, so latest point and "now" never disagree in method).
- `LoanService.debtFree(userId)`: avalanche plan -> `Statistics.debtFreeCycle` -> cycle dates; empty when no open debt.
- `FiPlannerService.projection(userId)`: gathers inputs, calls `FiPlanner.plan`, returns bands. Inputs (all stored, none
  typed): expense = `EmergencyFundService.size().monthlyOutflowPaise`; passive = RENTAL items of `IncomeService.cycleTotal`;
  opening = holdings total (EPF is a holding, OQ-1, default 0); SIP = `HABIT_SIP` + `LONG_TERM_SIP` lines of
  `plan_cycle.allocations`; step-up 0. `fiYears`/`fiDate` do not depend on `yearsToFi`, so pass the minimum valid value
  and ignore corpus fields.
- Rules: `CycleCalendar.length(Cycle)` so the ring denominator is a rule (elapsed + left).

## Contract
`GET /dashboard` — no parameters. Owner = `@CurrentUserId`; 401 unauthenticated; 403 `consent_required`;
`Cache-Control: no-store`; 200 always when data is readable. Amounts paise `long`; ratios decimal strings.
```
{ asOf: date,                       // Asia/Kolkata "today" used for every figure
  cycle:   { start, endExclusive, lengthDays, daysElapsed, daysLeft },
  budget:  { status: "OK"|"NOT_SET_UP", budgetPaise, spentPaise, remainingPaise, usedFraction, level,
             safeToSpendPerDayPaise },
  netWorth:{ nowPaise, series: [{cycleStart, assetsPaise, liabilitiesPaise, netWorthPaise}] },   // oldest first
  fi:      { status, bands: [{band, annualReturn, fiYears, fiDate}], base: {fiYears, fiDate},
             deltaYearsVsLastCycle: int|null, assumptions, disclaimer },
  stats:   { savingsRatePct, emiToIncome:{ratio,status}, discretionarySharePct,
             efMonthsCovered, debtFree:{cycleStart,endExclusive}|null, streak, netWorthPaise } }
```
Sections that need data the user has not entered (no budgets, no allocation run, no holdings and no SIP, no debt) return
`status: "NOT_SET_UP"` with their figures null, never a fabricated zero. `fiYears` null means not reached in the
solve horizon. FI carries all three bands and assumptions (T86 forbids a bare projection). Existing detail endpoints
stay; the dashboard repeats no list data (no item lists, no transactions).

## Ordering and side effects (correctness on first call)
`PlanCycleService.current` is not read-only: it closes an elapsed cycle and writes its net-worth snapshot. So
`DashboardService` calls it **first**, then reads the series; otherwise the first call after a cycle rolls over would
omit the just-closed point. Concurrent first calls serialise on the user-row lock in `current`; the loser sees the
winner's rows (`ON CONFLICT DO NOTHING`), so the result is identical. `asOf` is taken once and the same instant drives
every section, so ring, days left and safe-to-spend cannot straddle midnight.

## Cold start (Neon wake, T04)
- Sequential, not parallel: the first query pays the wake (up to ~12 s, Hikari `connection-timeout` 30 s already), the rest
  hit a warm connection. Parallel calls would only open more connections against a small pool.
- No retry layer, no partial-success masking: a connect that takes 12 s still returns the full 200. A real failure is a
  5xx via `GlobalExceptionHandler`; the endpoint never returns "empty dashboard" to hide an outage.
- No server-side cache (stale money figures); caching is the client's job (T79) using `asOf`. Client timeout must exceed
  35 s and show a skeleton (T82).
- One `consents.require` up front; inner services repeat it (cheap, accepted). Target about 20 queries, warm p95 < 600 ms.

## Data / RLS
No new table, no export/deletion/RLS change; every read goes through existing RLS-scoped services. Exception pending
Open question 1.

## Tests
- `DashboardApiDbTest` (Testcontainers, persona seeded): every field equals the owning service's own endpoint output
  (budget summary = `/budgets/status` sums; series = `/net-worth`; guard; EF; streak). Persona figures: surplus about
  39,800 / 39,835 (+-1%), home-loan EMI 40,032, safe-to-spend = floor((budget - spent) / daysLeft) with a fixed clock.
- **First call correct (cold)**: fresh user, no prior dashboard or cycle call, clock in cycle N+1 with cycle N still OPEN:
  one `GET /dashboard` returns 200 with the closed cycle's snapshot as the last series point and `cycle.start` = N+1.
  Second call returns identical JSON (idempotent, one snapshot row).
- **Cold DB**: reuse the `ColdStartFirstConnectTest` stub driver (12 s first connect); the call returns 200 with the same
  body as the warm call, no error status, no ERROR log.
- Not set up: user with consent and nothing else -> 200, sections `NOT_SET_UP`, no nulls where a number is promised, no 500.
- No consent -> 403; other user's data never appears (two users, B's call contains none of A's figures); a `userId` query
  or body parameter is ignored.
- Service unit tests: `BudgetService.cycleSummary` (over budget -> safe-to-spend 0, remaining negative; zero budget ->
  `usedFraction` null), `NetWorthService.current` equals the T70 snapshot for the same inputs, `LoanService.debtFree` empty
  with no debt. Rules: `CycleCalendar.length` incl. Feb clamping. Architecture check: `dashboard` package imports no
  `BigDecimal` arithmetic (no `.add/.divide/.multiply`).
- Perf: warm p95 < 600 ms over 50 calls on the persona dataset (recorded in the PR; not a flaky unit assertion).

## Open questions (assumptions)
1. **FI delta vs last cycle has no stored prior value, and past inputs cannot be reconstructed.** Assumption: add nullable
   `plan_cycle.fi_years_base smallint` (a column, not a table; `plan_cycle` is already exported/deleted/RLS-tested; grant
   `UPDATE (fi_years_base)`), set once by the dashboard (`... WHERE fi_years_base IS NULL`) the first time a cycle is read;
   delta = current base `fiYears` minus the previous cycle's value, null if absent. That is a write on a GET and needs
   `compliance-review`. Alternative: ship `deltaYearsVsLastCycle: null` and defer.
2. `fiYears` is whole years, so most deltas are 0. Ask rules-engine whether `FiCorpus.fiYears` may return months.
3. Total spent counts unbudgeted categories (as rebalance does), so spent can exceed the sum of line spends.
4. Debt-free uses avalanche; savings rate = `plan_cycle.surplus` / current-cycle income (null until allocation runs).

Covers T74. Adds beyond the plan: `BudgetService.cycleSummary`, `NetWorthService.current`, `LoanService.debtFree`,
`FiPlannerService.projection`, `CycleCalendar.length`, and (pending Q1) one `plan_cycle` column with migration V14.

## Implementation notes (as built)
- FI delta: `deltaYearsVsLastCycle` is always null; no `fi_years_base` column or migration was added.
- FI projection lives in `fi/FiProjectionService` (not `FiPlannerService.projection`); cycle length uses the existing `Cycle.lengthDays()`, not a new `CycleCalendar.length`.
- `BudgetService.cycleSummary(userId, asOf)` takes the shared `asOf`.
