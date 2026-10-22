# T35 — income_source and per-cycle income (FR-03, design)

Code: `api/.../income` (controller, service, repository), `api/.../rules/IncomeCalculator`, migration
`V5__income_source.sql`. Follows `docs/api-conventions.md` and the T34 / V4 pattern.

## 1. Rules (layer: `api/.../rules`, pure BigDecimal)
The per-cycle total is computed in the **rules package**, not in the service, controller or UI. The service
only fetches the user's rows (RLS-filtered) and the cycle bounds, then calls:

- `IncomeCalculator.cycleTotal(List<Source> sources, CycleCalendar.Cycle cycle) -> long` (paise).
  `Source` is a record of primitives `(type, amountPaise, frequency, startDate, endDateOrNull)`; rules never
  see entities, the clock or the database. Cycle bounds come from `CycleCalendar.cycleFor(today, startDay)`
  (end exclusive). "Today" is `Asia/Kolkata` from a `Clock` in the service.
- `IncomeCalculator.countsIn(Source, Cycle) -> boolean`, the single inclusion rule, also used for the list view.
- Sum is `BigDecimal` of whole paise, converted with `longValueExact()` at the edge. No rounding arises
  because amounts are integers and nothing is divided.

Semantics:
- `MONTHLY` (recurring): counts its full `amountPaise` once in every cycle that overlaps
  `[startDate, endDate]`, i.e. `startDate < cycle.endExclusive` and (`endDate` null or `endDate >= cycle.start`).
  `endDate` is inclusive and optional (open-ended). A source starting mid-cycle counts in full for that cycle
  (no pro-rating).
- `ONE_OFF`: `startDate` is the date received; `endDate` must be null. Counts only in the cycle where
  `cycle.contains(startDate)`, so a bonus appears in exactly one cycle.
- Types: `SALARY, RENTAL, BONUS, SIDE, OTHER` (informational; they do not change the maths).

Tests: `IncomeCalculatorTest` (boundary days at `start` and `endExclusive - 1`/`endExclusive`, start day 31 in
February, ended source, future source, empty list = 0, one-off in the previous and next cycle excluded) and
a `RulesGoldenTest` case.

Golden assertions (fixture only, no hard-coded persona amounts): load via `GoldenFixture`. The fixture has
`income.monthly_paise` today. Add keys `income.salary_paise`, `income.rental_paise` (sum must equal
`income.monthly_paise`) and `income.bonus_paise` to `persona.sample.properties` (fake round figures) and
tell the owner to add the same keys to `persona.local.properties`. Assertions:
1. MONTHLY salary + MONTHLY rental over the current cycle == `income.monthly_paise` (the persona's
   ₹1,27,000 when run against the local fixture; the sample's own fake figure otherwise).
2. Adding a ONE_OFF `income.bonus_paise` dated inside the cycle gives monthly + bonus for that cycle and
   exactly `income.monthly_paise` for the previous and next cycles.

## 2. Data (V5)
`income_source(id uuid pk default gen_random_uuid(), user_id → users ON DELETE CASCADE, type text,
frequency text, amount bigint, start_date date, end_date date, created_at timestamptz default now())`.
- CHECKs: `type IN (...)`, `frequency IN ('MONTHLY','ONE_OFF')`, `amount > 0`,
  `end_date IS NULL OR end_date >= start_date`, `frequency = 'MONTHLY' OR end_date IS NULL`.
- Index `(user_id, start_date)`. No label/free-text column, no account or ID numbers (keeps logs and exports
  free of payer names; a free-text note is out of scope).
- RLS ENABLE + FORCE, policy `income_source_self` copied from V4: `USING (user_id = <cur>)`,
  `WITH CHECK (user_id = <cur> AND finpath_has_consent())`; `GRANT SELECT, INSERT, UPDATE, DELETE` to `app_user`.
- `[+TABLE]` trio:
  - Export: add `TableRef("income_source","user_id")` to `DataSubjectRegistry.EXPORT`
    (after `plan_cycle`); money keys exported as `amount_paise`.
  - Deletion: add it to `DELETE` before `users` (children first).
  - RLS: `IncomeSourceCrossTenantTest` (user B cannot select, update or delete A's row; insert for another
    user or without consent is rejected). `DataSubjectRegistryTest` must pass.

## 3. API (`/income-sources`, all authenticated; owner = `@CurrentUserId`, any `userId` in a body is ignored)
Money fields are integer paise (`amountPaise`).
| Call | Body / result |
| --- | --- |
| `POST /income-sources` | `{type, frequency, amountPaise, startDate, endDate?}` -> 201 + view |
| `GET /income-sources` | list of views, ordered by `startDate` desc |
| `PUT /income-sources/{id}` | same body as POST (full replace) -> 200 + view |
| `DELETE /income-sources/{id}` | 204 |
| `GET /income-sources/cycle-total` | `{cycleStart, cycleEndExclusive, totalPaise, items:[{id, type, amountPaise}]}` for the current cycle; `items` are those where `countsIn` is true |

View: `id, type, frequency, amountPaise, startDate, endDate, countsInCurrentCycle` (the flag comes from
`countsIn`, not from the UI). Dates are ISO `yyyy-MM-dd`.

Errors (fixed codes): 400 `invalid_request` (unknown enum, non-integer or <= 0 amount, bad date,
`endDate` before `startDate`, ONE_OFF with `endDate`); 403 `consent_required` on writes before consent
(service checks first for a clean 403, as in T34); 404 `not_found` for an unknown id or another user's row
(RLS, indistinguishable). Nothing logs amounts or request bodies. Add the new endpoints to the `OpenApiTest`
expectations.

## 4. Screens (consumed by T44 / T47; no UI work in T35)
Income list with add/edit sheet at 380 px, light and dark; shows `cycle-total.totalPaise` as the headline
and a recurring/one-off badge from `frequency`. Components display API numbers only and never sum
`amountPaise`. INR lakh/crore formatting is UI-only.

## 5. Open questions (assumptions)
1. Quarterly / yearly recurrence? Assume only MONTHLY and ONE_OFF; the BRD's "bonus about ₹14,000 a year" is
   entered as a ONE_OFF on its payout date.
2. Pro-rating for mid-cycle start/end? Assume none (full amount in any overlapped cycle).
3. Editing a recurring amount: assume the edit applies to all cycles of that row; a raise is modelled as
   ending the old row and adding a new one, so history stays correct.
4. Past cycles: `cycle-total` covers the current cycle only; a `?date=` parameter is deferred until a task needs it.

## 6. Plan coverage
Covers **T35**. Feeds T44, T47 (income in wizard) and later T64/EMI-ratio work via `IncomeCalculator`.
Additions not in PLAN.md: `income.salary_paise`, `income.rental_paise`, `income.bonus_paise` fixture keys
(sample and owner's local file), and the `GET /income-sources/cycle-total` endpoint.
