# T38 — recurring_template and materialiser (FR-05, design)

Code: `api/.../recurring` (controller, service, repository), `api/.../rules/RecurringSplit`, migration
`V7__recurring_template.sql`. Follows T35 (service/controller/errors per `docs/api-conventions.md`) and T36
(postings reuse `TransactionRepository`). Web is T44; no UI here.

## 1. Rules (`api/.../rules`, pure, `long`/`BigDecimal`, no clock, no DB)
`RecurringSplit`:
- `periodMonths(Frequency) -> int`: `MONTHLY`=1, `QUARTERLY`=3, `YEARLY`=12.
- `instalment(long billPaise, int periods, int index) -> long` (index 0-based): base = `billPaise / periods`
  (floor, integer division); instalments `0..periods-2` return base; **the last (`index == periods-1`) returns
  `billPaise - base*(periods-1)`**, so the remainder paise always lands on the last instalment and the sum is
  exactly `billPaise`. Throws `IllegalArgumentException` for `billPaise <= 0`, `periods < 1`, index out of range.
- `due(Template t, CycleCalendar.Cycle cycle, int startDay) -> Optional<Due(index, periods, amountPaise)>`:
  active when `t.startDate < cycle.endExclusive` and (`endDate` null or `endDate >= cycle.start`);
  `m = CycleCalendar.cyclesBetween(t.startDate, cycle.start, startDay)` (empty if `m < 0`);
  `index = m % periods`. So the period starts in the cycle containing `startDate` and repeats every `periods`
  cycles, one instalment per cycle. A MONTHLY template is `periods=1` (full amount every cycle).
- Cycle bounds come from `CycleCalendar.cycleFor(today, startDay)`; "today" is `Asia/Kolkata` from a `Clock` in the
  service (as T35).

Tests (`RecurringSplitTest`): 600000 paise / 3 = 200000 x3, sum exact; 1000 paise / 3 = 333, 333, 334 (sum
1000); a bill under `periods` paise is rejected at validation (Q2); MONTHLY returns
full; index/period validation; `due` at cycle boundaries, `endDate` inside a period, start day 31 across
February, start in a future cycle = empty. Property-style loop: for bills 1..5000 paise and periods {1,3,12}, the
instalments sum to the bill. `RulesGoldenTest` case: persona's quarterly share (BRD s6 fixed expenses, "quarterly
bills spread monthly") read from `GoldenFixture`; no persona figure hard-coded. Add key `fixed.quarterly_bill_paise`
to `persona.sample.properties` (fake round, divisible by 3 plus a non-divisible variant in the unit test) and
tell the owner to add it to `persona.local.properties`. Assertion: three consecutive instalments sum to the key.

## 2. Data (V7)
`recurring_template(id uuid pk default gen_random_uuid(), user_id uuid NOT NULL → users ON DELETE CASCADE,
category_id uuid NOT NULL → category ON DELETE RESTRICT, amount_paise bigint NOT NULL CHECK (> 0),
frequency text NOT NULL CHECK IN ('MONTHLY','QUARTERLY','YEARLY'), mode text NOT NULL CHECK IN (same list as
transaction), tag text CHECK IN ('NEED','WANT','WASTE') nullable, start_date date NOT NULL, end_date date,
created_at timestamptz NOT NULL DEFAULT now(), CHECK (end_date IS NULL OR end_date >= start_date))`.
- `amount_paise` is the **bill amount per period** (the full ₹6,000), not the instalment.
- **No free-text column** (no label, no note): payer/biller names and reference numbers stay out of the table, exports
  and logs. The category identifies the bill. Postings are written with `note = NULL`. Direction is always `OUT`.
- Index `(user_id, start_date)`. RLS `ENABLE` + `FORCE`, policy `recurring_template_self` copied from V6
  (`USING user_id = current`, `WITH CHECK ... AND finpath_has_consent()`); `GRANT SELECT, INSERT, UPDATE, DELETE`
  to `app_user`.
- **Same migration changes `transaction.source`**: drop the CHECK (default name `transaction_source_check`) and
  re-add `CHECK (source IN ('MANUAL','SMS','IMPORT','RECURRING'))`. Confirm the constraint name with
  `\d transaction` before writing it; use `DROP CONSTRAINT IF EXISTS`. Postings carry `source = 'RECURRING'`.
- `TransactionRepository` change (small, api-feature): add `source` to `Draft` (default `MANUAL` via a second
  constructor/factory so T37 callers are unchanged) and bind it instead of the hard-coded `'MANUAL'` in `insert`.
  Hash, `ON CONFLICT (user_id, idempotency_key) DO NOTHING` and the replay result are reused as is.

## 3. Materialiser and idempotency
`RecurringMaterialiser.materialiseForUser(UUID userId, LocalDate today) -> Result` (service, one RLS-bound
transaction per template or per call):
1. Read the user's `cycle_start_day` (as `IncomeService`), compute the current cycle.
2. Load the user's templates; for each, `RecurringSplit.due(...)`; skip if empty.
3. Build a `Draft(txnDate = cycle.start, amountPaise = instalment, OUT, categoryCode, mode, tag, null note,
   idempotencyKey)` with source `RECURRING` and call `TransactionRepository.insert`.
4. **Key: `"rt-" + templateId + "-" + cycle.start` as `yyyyMMdd`** (3+36+1+8 = 48 chars, within the 8..64 CHECK).
   Deterministic, so a second run hits the unique index `(user_id, idempotency_key)` and `insert` returns
   `created=false`: **running twice materialises once**. No separate "last run" state to drift.
5. Result: `{cycleStart, cycleEndExclusive, items:[{templateId, instalmentIndex, instalments, amountPaise,
   created}], createdCount, postedPaise}` (`postedPaise` sums only `created` items; computed in the service from
   rules output, not the UI).

Behaviour notes: later edits to a template do not rewrite already-posted rows (they are normal, editable
transactions); deleting a template keeps its postings (no FK; they are the user's records). Posting date is the
cycle start day so it always falls inside the cycle (chosen over a per-template day, see Q3). If the user later
changes `cycle_start_day`, a re-run in the same calendar period could produce a different `cycle.start` and thus a
new key; accepted, user can delete the extra row (Q4).

**Scheduled invocation without a scheduler.** The only entry points are `materialiseForUser` and
`POST /recurring-templates/materialise`. No `@Scheduled`, no cron, no new infra. A future scheduler (separate
task, needs per-user RLS context and a consent check) loops users and calls `materialiseForUser(userId, today)`;
it needs no design change because the call is idempotent. Until then web (T44) can call the endpoint on opening the
plan screen.

## 4. API (`/recurring-templates`, authenticated; owner = `@CurrentUserId`, a `userId` in a body is ignored)
| Call | Body / result |
| --- | --- |
| `POST /recurring-templates` | `{categoryCode, amountPaise, frequency, mode, tag?, startDate, endDate?}` -> 201 + view |
| `GET /recurring-templates` | list of views, ordered by `startDate` desc |
| `PUT /recurring-templates/{id}` | same body (full replace) -> 200 + view |
| `DELETE /recurring-templates/{id}` | 204 |
| `POST /recurring-templates/materialise` | no body; current cycle -> 200 + Result (above) |

View: `id, categoryCode, amountPaise (bill), frequency, mode, tag, startDate, endDate, perCycleSpread:
[{index, amountPaise}] (from RecurringSplit, e.g. 200000,200000,200000), currentInstalmentPaise (null if not
due this cycle)`. T44 shows the bill amount and the spread from these fields and sums nothing.
Errors (fixed codes, as T35): 400 `invalid_request` (unknown enum or category, amount not an integer or <= 0,
bad date, `endDate` < `startDate`, amount < periods paise per Q2);
403 `consent_required` on writes and materialise before consent (service checks first); 404 `not_found` for an
unknown id or another user's row. Nothing logs amounts or bodies. Add the endpoints to `OpenApiTest`.

## 5. `[+TABLE]` trio for `recurring_template`
- Export: `new TableRef("recurring_template","user_id", ROW_WITH_CATEGORY_CODE)` in `DataSubjectRegistry.EXPORT`
  after `transaction` (it has `category_id`, so the existing constant fits). Money key stays `amount_paise`.
- Deletion: add to `DELETE` before `users` (children first; order relative to `transaction` is irrelevant, no FK).
  Postings are already covered by the `transaction` entry.
- RLS: `RecurringTemplateCrossTenantTest`: B cannot select, update or delete A's template; insert for another
  user, or without consent, is rejected. `DataSubjectRegistryTest` must pass.
- Also `RecurringMaterialiserDbTest`: the ₹6,000 quarterly template posts 200000 paise in each of three
  consecutive cycles (`SUM(amount_paise) = 600000`, source `RECURRING`); a 1000-paise variant posts 333/333/334;
  a second call in the same cycle returns `createdCount = 0` and leaves the row count unchanged; user B's
  materialise never touches A's templates.

## 6. Screens (T44 consumes; nothing built in T38)
Recurring list and add/edit sheet at 380 px, light and dark. Each row shows the bill amount, frequency badge and
`perCycleSpread` from the API ("₹6,000 every 3 months, ₹2,000 a month"). A "Post this cycle's bills" action calls
`POST .../materialise` and displays its result. Components never compute the split; INR lakh/crore formatting is
UI-only. Add a hint that no account or reference numbers belong anywhere (there is no text field to enter them).

## 7. Open questions (assumptions)
1. Bill anchor: the period starts in the cycle containing `startDate`; instalment 1 posts there. Assumed.
2. Bills smaller than `periods` paise would give zero instalments; reject at validation (`amountPaise >= periods`).
3. Posting date = cycle start, not the real due date. Assumed; a `due_day` column can be added later.
4. Start-day change mid-period may allow a second posting under a new key. Accepted for MVP.
5. YEARLY included because it costs nothing in `RecurringSplit`; drop from the CHECK if unwanted.
6. Insurance (OQ-2) is just a template once a policy exists; no special case.

## 8. Plan coverage
Covers **T38** (table, split rule, materialiser, idempotency, `[+TABLE]` trio). Feeds T40 and T44/T47.
Additions not in PLAN.md: `transaction.source` CHECK now admits `RECURRING` (V7), `source` on
`TransactionRepository.Draft`, `fixed.quarterly_bill_paise` fixture key (sample plus owner's local file),
`POST /recurring-templates/materialise`, `perCycleSpread` in the view, YEARLY frequency.

## Review follow-ups (accepted trade-offs, decide before T44)
- The idempotency key is the only record of a posting: deleting a RECURRING posting and re-running materialise recreates it; deleting a template and recreating an identical one posts the same cycle again. Accepted for MVP (no state column); revisit with a tombstone / last_materialised_cycle if T44 UX needs it.
- Service sums instalments it received from `RecurringSplit` only for the response total; no new arithmetic.
- Editing amount/frequency/start after postings leaves earlier postings unchanged; missed cycles are not back-filled.
- V7 drops `transaction_source_check` without IF EXISTS so a drifted schema fails loudly.
