# T72 — reminders: loan.due_day, reminder_sent, date rule, scheduler (FR-27, design)

Code: new rules class `rules/Reminders`; `api/.../reminder` (`ReminderScheduler`, `ReminderService`,
`ReminderRepository`, `ReminderSink`); small changes in `api/.../loan` and the web loan form; migration
`V15__loan_due_day_reminder_sent.sql` (latest existing is V14). Pattern: T71 (V13, registry, CrossTenantTest) and
T31 `UnconsentedUserPurge` (scheduled job, slow run = warning). Delivery (push, SMTP) is T73; T72 only decides
and claims reminders and hands them to a sink.

## 1. The date rule (BRD)
BRD "Rules and thresholds": *Credit card — full statement paid every cycle — alert 5 days before due date.* FR-27:
*reminders before due dates and on cycle day.* Rule as built:
- `LEAD_DAYS = 5` (constant in `Reminders`, the BRD default; configurable later).
- **Due date** of a loan for a month = `due_day` clamped to the month length (31 in April = 30th, 30/31 in
  February = 28th/29th).
- **Reminder date** = due date minus 5 calendar days. A `due_day` of 5 therefore gives the **last day of the previous
  month** (31st after a 31-day month, 30th after April/June/Sept/Nov, 28th/29th before March); `due_day` 6 gives the
  **1st**. The PLAN wording "31st/1st" is read as this month-boundary case, not as two reminders. Golden cases: due
  5 May -> 30 Apr; due 5 Apr -> 31 Mar; due 5 Mar 2027 -> 28 Feb; due 5 Mar 2028 -> 29 Feb; due 5 Jan -> 31 Dec
  (previous year); due 6 Jun -> 1 Jun; due_day 31 in Feb 2027 -> due 28 Feb, reminder 23 Feb.
- A loan with `due_day` NULL produces nothing. No guessing from `bullet_due_date` or the cycle.
- **Cycle-day event**: one reminder on the user's cycle start date (`CycleCalendar` clamped start).

## 2. Rules (`api/.../rules/Reminders`, pure; no `LocalDate.now`, `today` is a parameter)
```
record Reminder(LocalDate remindOn, LocalDate dueDate)
Reminders.loanDue(int dueDay, LocalDate today) -> Reminder   // dueDay 1..31, else IllegalArgumentException
Reminders.cycleStart(int startDay, LocalDate today) -> Optional<Reminder>
```
- `loanDue`: `c = CycleCalendar.cycleFor(today, dueDay)`; due = `c.start()` if it equals `today`, else
  `c.endExclusive()` (the next clamped occurrence, so no new clamping code). `remindOn = due.minusDays(5)`. Returns
  the next due date on or after `today`; the service sends when `remindOn <= today` (a late first run still sends once,
  until the due date passes, then the following month's reminder takes over).
- `cycleStart`: `c = cycleFor(today, startDay)`; present only if `daysElapsed(c, today) <= 2` (`CYCLE_CATCHUP_DAYS`,
  so a short outage still delivers, a month-old cycle never does); `remindOn = dueDate = c.start()`.
- Both use `CycleCalendar`; neither does money arithmetic. `RulesNoClockTest`-style grep (`LocalDate.now` in `rules`
  returns nothing) must stay clean.

Tests (`RemindersTest`): the golden dates above; leap and non-leap February; December -> January year rollover;
`dueDay` 0 and 32 rejected; `today` on the due date returns that date; `today` the day after returns next month;
`cycleStart` on day 0, 2 -> present, day 3 -> empty; start day 31 in April/February; result independent of the
system clock/zone.

## 3. Data (V15)
1. `ALTER TABLE loan ADD COLUMN due_day smallint CHECK (due_day IS NULL OR due_day BETWEEN 1 AND 31)`. Nullable, no
   default, no backfill. Existing grants and the `loan_self` policy already cover it.
2. `reminder_sent(id uuid pk default gen_random_uuid(), user_id uuid NOT NULL -> users ON DELETE CASCADE,
   reminder_key text NOT NULL, due_date date NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
   CONSTRAINT reminder_sent_once UNIQUE (user_id, reminder_key, due_date))`.
   - `reminder_key` CHECK `~ '^(loan_due:[0-9a-f-]{36}|cycle_start)$'`: a kind plus a loan uuid, never user text,
     lender, amount or any account/card/ID value. No FK to `loan` (deleting a loan must not need to touch history).
   - `due_date` is the due date (loan) or cycle start (cycle event), so each month is a new key.
   - RLS ENABLE + FORCE, policy `reminder_sent_self` as V13 (USING owner; WITH CHECK owner and
     `finpath_has_consent()`). Grants: SELECT, INSERT, DELETE to `app_user` (DELETE only to release a failed claim).
     No UPDATE. Index: the unique constraint covers lookups.

## 4. API / DTO changes (loans)
- `LoanRequest` and `LoanView` gain `dueDay` (JSON integer or null/absent). Validation: integer 1..31, else 400
  `invalid_request` (fixed message); string/decimal 400. PUT is a full replace: omitting `dueDay` clears it (same rule
  as `emi`, T48). Allowed on every loan type. Add to `OpenApiTest` and `LoanApiDbTest` round trip.
- No new endpoints. The next reminder date is not exposed (YAGNI); if shown later it is a rules figure from the API.
- Owner always from `@CurrentUserId`; the scheduler never takes a user id from outside.

## 5. Scheduler (cold-start tolerant, idempotent)
`ReminderScheduler` (`@Scheduled(fixedDelayString = "${finpath.reminders.interval:15m}", initialDelay 2m)`; the
class using `@EnableScheduling` already exists in `dpdp`). Uses an injected `Clock` and `Asia/Kolkata` to get `today`.
Flow per run:
1. List candidate users through a SECURITY DEFINER function `finpath_reminder_users()` returning `uuid[]` of users
   with live consent (same pattern, NOLOGIN owner role, fail-closed check and EXECUTE only to the login role as
   `finpath_purge_unconsented_users`, V2.1/T31; add to `RlsMetadataTest.SECURITY_DEFINER_ALLOW_LIST`). Reason: RLS
   forbids a cross-tenant read, and the job must not run as a role that bypasses it. Returns ids only.
2. For each user, one short transaction as that user: a small `rls` addition `RlsScope.runAs(userId, ...)` that
   `RlsTransactionManager` consults when `CurrentUser` is empty (thread-local, always cleared in `finally`). Reads
   that user's loans with `due_day` and the cycle start day (via `PlanCycleService`), asks `Reminders`, and for each
   reminder with `remindOn <= today` runs
   `INSERT INTO reminder_sent (...) VALUES (...) ON CONFLICT (user_id, reminder_key, due_date) DO NOTHING`.
3. **Update count 1 = this run owns the send** and calls `ReminderSink.send(ReminderMessage)`; count 0 = already
   sent, do nothing. If the sink throws, the claim row is deleted in the same user scope so the next tick retries
   (at-most-once per success; a crash between claim and send loses one reminder, accepted over double-sending).
4. Cold start: nothing is wrapped in one long transaction; a slow first query (Neon wake, 12 s, within T04's
   connect timeout) is just slow. A connection failure for one user is logged as a warning (count only, no ids, no
   bodies) and retried next interval; it never aborts the other users or surfaces as a 5xx. If the first attempt
   timed out client-side but committed, the retry hits `ON CONFLICT DO NOTHING` -> 0 rows -> no second send. Two
   API instances or an overlapping run are safe for the same reason (the unique key, not an in-memory lock, is the
   guarantee). No keep-warm ping (PLAN section on Neon compute).
5. `ReminderSink` is an interface; T72 ships a `LoggingReminderSink` (logs kind and count only). T73 provides push/SMTP.

**Message content**: `ReminderMessage(userId, kind, title, body)` with fixed constant text per kind, no
interpolation: e.g. LOAN_DUE "A loan payment is coming up" / "Open FinPath to check this month's payment";
CYCLE_START "Your new budget cycle has started" / "Plan your month in FinPath". No amount, no lender, no merchant,
no category, no digits. T73 reuses the constants.

## 6. Layer ownership
| Figure | Owner |
|---|---|
| Due date incl. clamping, reminder date, lead days, cycle-start event and catch-up window | rules `Reminders` (+ `CycleCalendar`) |
| "Today" | scheduler `Clock` (IST), passed in |
| Sent / not sent | DB unique key via `ON CONFLICT DO NOTHING` |
| `due_day` validation 1..31 | API (400) and DB CHECK |
| Message text | `reminder` package constants (no figures) |
| Web | form field only; computes nothing |

## 7. `[+TABLE]` trio for `reminder_sent`
- Export: `new TableRef("reminder_sent","user_id")` in `DataSubjectRegistry.EXPORT` after `checklist_item`
  (keys `reminder_key`, `due_date`, `created_at`).
- Deletion: add to `DELETE` before `users`, with `checklist_item`.
- RLS: `ReminderSentCrossTenantTest` — B cannot SELECT/DELETE A's row; INSERT for A as B fails; insert without
  consent fails; UPDATE not granted. `DataSubjectRightsDbTest` seeds and exports a row and `DELETE /me` leaves zero.
  `DataSubjectRegistryTest` (fails by table name if unregistered) and the no-identifier-column test as T65 pass.

## 8. Screens (web-ui; loan form only)
Loan add/edit sheet gets an optional "Due day of month" numeric field (1-31, `inputmode=numeric`), helper text
"Reminders arrive 5 days before. Leave empty for none." Sent as `dueDay` (number or null). Loan list row may show
"Due on the 5th" from `dueDay`, text only. 380 px, light and dark: label above input, no overflow, error text for
out-of-range, no hard-coded colours. Tests: field round-trips through the service stub, 0 and 32 show the error and
do not submit, empty submits null.

## 9. Tests
- `RemindersTest` (section 2).
- `LoanApiDbTest` additions: `dueDay` 5 round trips; null/absent stays null; 0, 32, "5" and 5.5 -> 400; PUT without
  it clears; direct SQL insert of 32 fails the CHECK.
- `ReminderServiceDbTest` (Testcontainers/TestDb, fixed `Clock`): loan due 5th, today 30 Apr -> one sink call, one row
  with `due_date` 5 May; loan with no `due_day` -> zero rows, zero calls; second run same day -> still one; next
  month -> new row; cycle-day event once per cycle; no-consent user gets nothing; user A's run never reads B's loans.
- **Cold-start test**: a repository decorator delays the first claim by a simulated 12 s (latch or fake clock, not a
  real sleep) and the run is retried/overlapped; assert exactly one sink call and one row. Also: sink throws once ->
  claim released -> next run sends exactly once. A connection failure for one user does not stop the next user.
- Body test: every `ReminderMessage` text equals its constant, matches no `₹`, `Rs`, digits, and contains none of a
  seeded lender name (a lender seeded with a merchant-like name).
- Guards: `git grep LocalDate.now` in `rules` empty; `RlsMetadataTest` allow-list updated; scheduler logs checked
  for ids/bodies (as `SignInLogHygieneTest`).

## 10. Open questions (assumptions)
1. The 5-day lead applies to **all** loans with a `due_day`, not only `card_emi` (BRD names credit cards only; the
   plan's acceptance uses "a loan"). Assumed; restrict by `type` in the service if the owner prefers.
2. "31st/1st" read as the month-boundary result of minus 5 days (section 1), one reminder per due date.
3. No separate on-the-due-day reminder; only the lead reminder and the cycle-day one. Add a `lead 0` key later without
   schema change (`reminder_key` kind).
4. `bullet_due_date` loans get reminders only if they also set `due_day` (monthly interest); principal-due reminder is
   out of scope.
5. Catch-up: a loan entered 3 days before its due date still gets one (late) reminder immediately.
6. `finpath_reminder_users()` and `RlsScope.runAs` touch RLS/consent paths: **compliance-review required** before merge.
7. Timezone fixed to IST (single-household India app); per-user zones out of scope.
8. Notification content opt-out and channel choice belong to T73.

## 11. Plan coverage
Covers **T72** (due_day migration + DTO + web field, `reminder_sent` + trio, date rule, idempotent scheduler).
Adds, not in the plan: `rules/Reminders` (loanDue, cycleStart), `ReminderSink` seam with logging default,
`finpath_reminder_users()` SECURITY DEFINER function, `RlsScope.runAs`, cycle-start catch-up window, fixed message
constants, `OpenApiTest` and `LoanApiDbTest` updates.
