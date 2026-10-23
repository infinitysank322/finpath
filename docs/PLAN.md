# FinPath — MVP Implementation Plan

Derived from [BRD.md](BRD.md) (source of truth). Scope is the **P1 rows of BRD section 5** plus the scaffolding needed to make them real. Stack decisions are settled in [../CLAUDE.md](../CLAUDE.md) and are not revisited here.

Every persona figure quoted below was computed directly, not copied from the BRD's prose. Where the two disagree, see [section 7](#7-open-questions-and-resolved-decisions).

---

## 1. Scope boundary

**In scope — 22 P1 FRs:** FR-01, FR-02, FR-03, FR-04, FR-05, FR-06, FR-07, FR-10, FR-12, FR-13, FR-14, FR-15, FR-16, FR-17, FR-18, FR-19, FR-20, FR-21, FR-22, FR-23, FR-26, FR-27.

**Excluded from MVP:** FR-08, FR-09, FR-11, FR-24, FR-28, FR-29, FR-30, FR-31 (P2); FR-25, FR-32 (P3) — statement import, SMS parsing, leakage detection, product comparison, scenario planner, all three Claude agents, report export, CAS import, household sharing.

Three consequences, stated so nobody designs around them:

- **The MVP ships no AI.** Every AI FR is P2, so runtime Claude API spend is ₹0 and the Categoriser, Monthly reviewer and Ask-my-money agents are not built. Do not add an AI call path "while we're here" — it would arrive without the aggregation, opt-out and fallback machinery BRD section 7 requires.
- **Capture is manual-entry only.** `transaction.source` is `manual` for every row, yet `dedupe_hash` and a client idempotency key ship anyway (T36, T43): the offline queue already creates duplicates, and retrofitting a stable hash after rows exist is a migration, not an edit.
- **Leakage and report visuals are out** with their FRs. The section 8 charts that *are* in scope: cycle progress ring, net-worth line, FI countdown, category donut with need/want/waste, month-on-month bars, payoff timeline, principal-vs-interest area, EMI ratio gauge, goal progress bars, allocation donut, consistency heatmap, corpus projection line.

---

## 2. Where logic lives

Non-negotiable layer assignments. Each row says why the figure cannot sit one layer out.

| Figure / behaviour | Layer | Why not one layer out |
| --- | --- | --- |
| EMI, amortisation, effective rate, payoff date, interest saved | `api/.../rules` pure functions | A DB view or client calc diverges from the golden tests and escapes the 90% coverage gate |
| Surplus waterfall, allocations | rules | The 7-step order *is* the product; a service that re-orders it is a silent behaviour change |
| Cycle boundaries (`cycleFor(date, startDay)`) | rules (`CycleCalendar`) | Every other layer would reimplement month-end clamping |
| Safe-to-spend/day, budget % consumed, 80/100 thresholds | rules, exposed via API | A client dividing remaining-by-days disagrees with the server after a mid-cycle rebalance |
| Savings rate, EMI-to-income, discretionary share, EF months, debt-free date, FI date, streak | rules, returned by `GET /dashboard` | Section 8 calls these "computed every cycle" — one computation, one source |
| Net-worth-by-month series | API writes a `net_worth_snapshot` at cycle close; rules computes the value | Assets minus liabilities *as at a past date* is not derivable from today's rows — it must be captured, not recalculated (T70) |
| Paise → rupee rounding for display | client edge only | Rounding mid-calculation breaks the 1% golden tolerance |
| `dedupe_hash`, idempotency resolution | API service | Needs the persisted neighbourhood of rows; the client cannot see other sources |
| Owning user of every row | API security layer from the authenticated principal, plus Postgres RLS | A request-supplied user id is a tenancy breach by design |
| INR lakh/crore formatting | Angular pipe | Formatting in the API bakes locale into the contract |

If a step needs a number the API does not expose, the step is "add the endpoint" — never "derive it in the component".

---

## 3. Build order

Seven stages, each independently demonstrable. **Stage 1 completes before any UI depends on a figure** — the single most important ordering constraint here.

Markers:

- `[+TABLE]` — the task adds a table. It is **not done** until (a) the table is joined into the DPDP export path, (b) joined into the account-deletion path, (c) has a row-level-security test proving user B cannot read user A's row. These are acceptance criteria of the *same* task, never a later pass.
- `[?OQ-n]` — depends on a BRD open question; the assumption to proceed under is named in [section 7](#7-open-questions-and-resolved-decisions).
- `[LOCAL]` — needs the owner's PC, real hardware or secrets (Docker, Neon/OAuth/SMTP credentials, the phone or the VM). Not suitable for a cloud agent; run locally.

**Status** column: `TODO` / `IN-PROGRESS` / `DONE` / `BLOCKED`. Update it in the same commit that moves the task.

Estimates assume one developer with Claude Code, tests included.

### Stage 0 — Skeleton and pipes · 8 tasks · 23 h

*Exit:* `./mvnw test` and `npm test && ng build` green in CI; `docker compose up` serves health against local Postgres; Angular shell renders at 380 px in light and dark.

| Id | Status | FR | Task | Prereq | Est | Acceptance |
| --- | --- | --- | --- | --- | --- | --- |
| T01 | DONE | SCAFFOLD | Monorepo root: `.gitignore`, `.editorconfig`, branch/commit convention note, `docs/` index | — | 2 h | `git log --oneline` shows a commit prefixed with an FR id or `INFRA:`; root README names the three packages |
| T02 | DONE | SCAFFOLD | Spring Boot 3 / Java 21 Maven scaffold, wrapper, `local`/`ci`/`prod` profiles, actuator | T01 | 3 h | `./mvnw -q test` exits 0; `GET /actuator/health` returns `{"status":"UP"}`; `mvnw` is committed executable (`git update-index --chmod=+x api/mvnw`) and `git ls-files -s api/mvnw` shows mode `100755` |
| T03 | DONE | INFRA | `[LOCAL]` Docker Compose Postgres + Flyway + `V1__baseline.sql` (`app_user` role; comment recording money = paise `bigint`) | T02 | 3 h | `./mvnw flyway:info` lists V1 applied; a schema-lint test fails on any `double precision` money column |
| T04 | DONE | INFRA | `[LOCAL]` Neon connection from env; HikariCP tuned for scale-to-zero (connection-timeout ≥ 10 s, validation query, no retry-as-error) | T03 | 3 h | Test simulating a 12 s first connect passes without surfacing an error; `git grep -n -iE -e 'postgres(ql)?://' -e 'neon\.tech'` returns nothing tracked (covers plain and JDBC Postgres URLs and Neon hostnames; written so this row does not match itself) |
| T05 | DONE | INFRA | GitHub Actions: api `./mvnw -B verify`, web `npm ci && npm test && ng build`, `~/.m2` + npm caches, path filters | T02 | 3 h | A PR touching only `api/**` runs one job; cached run under 6 min |
| T06 | DONE | SCAFFOLD | Angular 18 standalone scaffold, routing, light/dark tokens, 380 px shell with bottom nav, `ng add @angular/pwa` service-worker registration (registration only — caching strategy is T79) | T01 | 3 h | `ng build` exits 0; shell at 380 px in both themes with no horizontal scroll; a production build registers the service worker |
| T07 | DONE | SCAFFOLD | `PaiseInrPipe` (paise `long` → lakh/crore INR) + contrast token test | T06 | 3 h | `501600000 → "₹50.16 L"`, `1900 → "₹19"`, `12700000 → "₹1,27,000"`; every token pair ≥ 4.5:1 |
| T08 | DONE | INFRA | `[LOCAL]` Multi-stage api `Dockerfile` (arm64 + amd64), compose for api + db | T02, T03 | 3 h | `docker buildx build --platform linux/arm64,linux/amd64` succeeds; container starts from env-only config |

### Stage 1 — Rules engine and golden tests · 20 tasks · 63 h

No HTTP, no JPA, no Spring in this package. Pure functions, `BigDecimal`, explicit `MathContext`, reference date always a parameter.

**The fixture comes first (T10b).** Real persona figures never enter git history, so the external fixture mechanism is built *before* the first golden assertion exists. From T11 onward every expected golden value is **read from the fixture**, never typed into a test file. A task that hard-codes a persona amount in Java source is not done, however correct the number.

`RulesGoldenTest` is created at T11 against the fixture; each later rule task adds its assertion; T26 closes it.

> **Scope of the fixture guarantee.** T10b keeps persona amounts out of `api/` and `web/` source and out of CI logs. It does **not** cleanse `docs/` — `BRD.md` contains the full persona by design (BRD section 4 doubles as the test fixture) and this plan quotes derived figures throughout. The repo is private, which is what makes that acceptable. If it is ever opened up, `docs/BRD.md` is the exposure, not the test suite, and the BRD's own instruction to "anonymise it before sharing" applies first.

*Exit:* `./mvnw test -Dtest=RulesGoldenTest` green; JaCoCo ≥ 90% on `rules`; every persona figure within 1% **except the two flagged in section 7**.

| Id | Status | FR | Task | Prereq | Est | Acceptance |
| --- | --- | --- | --- | --- | --- | --- |
| T09 | DONE | FR-22 | `rules` skeleton: `RuleConfig` (EMI warn 40 / block 50, inflation 6%, equity 12%, debt 7–8.25%, FI multiplier 30, min habit SIP ₹5,000, budget alerts 80/100, 3-year equity floor), `Money` paise↔BigDecimal boundary, shared `MathContext` policy | T02 | 3 h | Static scan asserts no literal `0.40`, `0.12` or `5000` outside `RuleConfig`; `Money.divide` without a scale throws |
| T10 | DONE | FR-02 | `CycleCalendar`: `cycleFor(date, startDay)`, bounds, days elapsed/left, cycles between dates, clamping for start days 29–31 | T09 | 4 h | startDay 31 in February resolves to the 28th/29th; a cycle spanning a month end reports the correct day count; `git grep 'LocalDate.now'` in `rules` returns nothing |
| T10b | DONE | INFRA | **Golden fixture mechanism, built before any golden assertion.** Real figures read from a gitignored local file; anonymised sample committed so the suite runs on a clean clone; loader + `assertWithinOnePercent` harness | T09 | 2 h | Suite passes on a clean clone from the committed sample; a `git grep` sweep for each persona amount across tracked files **under `api/` and `web/`** returns nothing (see the note below on `docs/`); a missing local file produces an explicit skip-or-fail, never a silent pass; fixture values never appear in test output or logs |
| T11 | DONE | FR-12, FR-13 | Amortising loan: EMI formula, full schedule, principal/interest split per instalment, interest remaining, payoff cycle | T10, T10b | 4 h | `RulesGoldenTest` created, **reading every expected value from the T10b fixture**: ₹50.16 L at 8.35% over 297 → computed EMI ₹40,004 (fixture's stated ₹40,032, −0.07%) and interest remaining ₹68.65 L (fixture ~₹69 L, −0.50%); no persona amount appears as a literal in the test source |
| T12 | DONE | FR-12, FR-13 | Bullet loan (interest-only): effective annual rate = monthly interest × 12 ÷ principal; interest to due date. Card-EMI variant with its own cost basis | T11 | 3 h | ₹2,300/mo on ₹1.8 L → **15.33%** (BRD 15.3%); a bullet loan passed to the amortising path fails a type assertion rather than returning a plausible number |
| T13 | DONE | FR-16 | Sinking fund: monthly set-aside to meet bullet principal by due date, cycles remaining, behind-schedule delta | T12 | 3 h | ₹1.8 L due Mar 2028 from Oct 2026 (17 cycles) → **₹10,588/cycle**; with ₹50 k already aside the need falls proportionally; a past due date yields a "behind" result, never a negative set-aside |
| T14 | DONE | FR-14 | Payoff ordering: avalanche (rate desc) and snowball (balance asc); multi-loan simulation cascading freed amounts | T11, T12 | 4 h | Persona's five debts order gold 15.33% → home 8.35% under avalanche; rate ties break deterministically; snowball orders by outstanding; closed loans excluded |
| T15 | DONE | FR-15 | EMI roll-off timeline: per-cycle freed-EMI steps as short loans end, 100% redirect default | T14 | 3 h | Steps of +₹8,000 (Dec 2026), +₹6,000 (Jan 2027), +₹7,000 (Sep 2027) with correct cycle ids |
| T16 | DONE | FR-17 | Prepayment simulator: lump-sum and EMI-increase variants → interest saved, new end date; inverse solve "EMI to close in N years" | T11 | 4 h | 156-EMI close → **₹52,804** (BRD ₹52,800, +0.01%); interest saved = interest@297 − interest@156 = **₹36.44 L** — see OQ-5 |
| T17 | DONE | FR-18 | EMI-to-income ratio + debt-trap guard returning `{ratio, status: OK/WARN/BLOCK, headroom}` | T09 | 2 h | ₹63,332 / ₹1,27,000 = **49.87%** → WARN; a further ₹2,000 EMI → BLOCK at 51.4%; exactly 40.00% is OK, 40.01% is WARN |
| T18 | DONE | FR-20 | Emergency-fund sizing from actual monthly outflow; months-covered statistic | T09 | 2 h | Outflow **₹87,165** → 1-month ₹87,165, 3-month **₹2,61,495**; current ₹15,000 reports **0.17 months** |
| T19 | DONE | FR-19 | Dated goals: monthly need to target by date, on-track status, horizon → instrument class (no equity under 3 years) | T10 | 3 h | ₹7.5 L by Dec 2028 from ₹0 over 26 cycles → **₹28,846/cycle**; a 2.9-year horizon returns `SAFE_ONLY`, 3.1 years permits equity |
| T20 | DONE | FR-21 | FI corpus: annual expense at FI age inflated at 6%, net of passive income, × multiplier 30; FI date solve | T19 | 3 h | ₹50,000/mo today at 27 → age 40 (13 y) inflates to **₹1,06,646/mo**; gross corpus **₹3.84 Cr**; net of rental inflated to ₹29,861/mo → **₹2.76 Cr**; multiplier read from `RuleConfig`, never inline |
| T21 | DONE | FR-21 | Step-up SIP future value (monthly compounding, annual step-up) + required-SIP solve for a target corpus | T20 | 3 h | Closed-form FV matches a 156-iteration loop to within ₹1; required-SIP solve round-trips through FV |
| T22 | DONE | FR-22 | Surplus waterfall allocator: all 7 priority steps in order, bullet loans via sinking fund, remainder split by user ratio | T13, T14, T18, T19, T21 | 4 h | Surplus **₹39,835** (BRD ~₹39,800, +0.09%) from ₹1,27,000 − ₹63,332 − ₹18,833 − ₹5,000; EPF ₹12,240 **not** subtracted (it is outside in-hand salary); allocation lines sum *exactly* to surplus |
| T23 | DONE | FR-10 | Budget rules: per-category per-cycle consumption, 80%/100% threshold events, safe-to-spend per remaining day | T10 | 3 h | 79.9% → no event; 80.0% → one `WARN`; 100% → one `BREACH` and no duplicate `WARN`; safe-to-spend on day 10 of a 31-day cycle matches hand arithmetic |
| T24 | DONE | FR-07 | Unplanned-expense rebalance: recompute remaining-cycle budget and goal/allocation impact | T22, T23 | 3 h | A ₹15,000 mid-cycle spend reduces safe-to-spend and names the absorbing waterfall step; allocations still sum to surplus |
| T25 | DONE | FR-26 | Statistics bundle: savings rate, discretionary share, debt-free date, net worth, consistency streak | T14, T22 | 3 h | Savings rate **31.4%**; debt-free date = home-loan payoff cycle; a missed cycle resets the streak to 0, not 1 |
| T26 | DONE | FR-13…FR-22 | Close `RulesGoldenTest`: every persona figure asserted, all sourced from the T10b fixture | T10b, T11–T22 | 4 h | `./mvnw test -Dtest=RulesGoldenTest` green; tolerance asserted only by the named `assertWithinOnePercent`; test output contains no persona amount; a static check confirms no persona literal in the `rules` test sources; owner runs `RulesGoldenTest` locally against the real fixture before merging dev to master |
| T27 | DONE | INFRA | Boundary sweep (zero principal, final instalment, rate at threshold, cycle spanning month end, already-closed loan) + JaCoCo 90% gate on `rules` | T26 | 3 h | `./mvnw verify` fails below 90% coverage on `rules`; each boundary case has a named test |

### Stage 2 — Identity, tenancy, DPDP spine · 6 tasks · 22 h

Built before any business table, so every later `[+TABLE]` task has an export registry, a deletion registry and an RLS harness to plug into.

*Exit:* a real Google sign-in reaches an authenticated `/me`; a cross-tenant read fails closed; export returns a file and deletion empties the user's rows.

| Id | Status | FR | Task | Prereq | Est | Acceptance |
| --- | --- | --- | --- | --- | --- | --- |
| T28 | DONE | FR-01 `[+TABLE]` | `users` table (id, name, email, cycle_start_day default 25, currency INR, risk_profile, fi_target_age) + entity. No account/card/identity columns | T03 | 3 h | V2 applies; RLS test proves user B cannot select user A's row; export and delete registries both list `users`; schema lint confirms no identity-number-shaped column |
| T29 | DONE | INFRA | `[LOCAL]` Spring Security + Google OAuth2 → JWT issuance, principal resolution, refresh | T28 | 4 h | No bearer token → 401; tampered signature → 401; client secret only from env (`git grep` for the secret pattern empty) |
| T30 | DONE | INFRA | Postgres RLS policies on `app.current_user_id`, set per transaction by a connection initialiser; service-layer scoping as defence in depth; reusable `CrossTenantTest` base | T29 | 4 h | `CrossTenantTest` proves a raw repository query for another user's id returns zero rows **even with service scoping bypassed**; a new table without a policy fails a metadata test; the app connects as `app_user` (not the migrating/owner role) and returning-user sign-in through `finpath_user_id_by_email` (V2_1) still passes; that function is on the metadata test's SECURITY DEFINER allow-list and a cross-tenant test proves it reveals only an id (T29 follow-up) |
| T31 | DONE | INFRA `[+TABLE]` | DPDP: `consent` table with purpose statement, `GET /me/export` from an exporter registry, `DELETE /me` from a deleter registry | T30 | 4 h | Removing a table from the registry breaks a test *by name*; export is valid JSON covering every registered table; post-delete every table returns zero rows; a consent row is required before the first write; `consent` itself carries an RLS policy and a cross-tenant test, and is registered in both the exporter and deleter (`[+TABLE]` trio satisfied for `consent`); T29 follow-ups: after `DELETE /me` the user's old access token, refresh tokens and any live one-time code all fail (`RefreshTokenStore.revokeAll`, a matching `OneTimeCodeStore` revoke, and JWTs/refresh for a deleted `sub` rejected); the consent check is proven against a business-table write (sign-in's `users` upsert is the only pre-consent write), and `users` rows whose owner declines or never gives consent are deleted or purged |
| T32 | DONE | INFRA | API conventions: error model, OpenAPI, paise-as-integer JSON contract documented, no stack traces or amounts in logs | T29 | 3 h | OpenAPI served; a 500 body contains no amount or merchant text; a log-appender test asserts no amount patterns in output, and no email addresses (including on the sign-in failure path) or bearer/refresh/one-time-code values (T29 follow-up) |
| T33 | TODO | FR-01 | `[LOCAL]` Web: Google sign-in, JWT storage, auth guard, session survival inside the Capacitor webview | T29, T06 | 4 h | Signed-out redirect test passes; token survives an app restart; sign-in renders at 380 px dark; T29 follow-ups: the API chain has a CORS allow-list for the web origin (or the Angular dev server proxies `/auth`, `/oauth2`, `/login`); the web client generates a PKCE S256 verifier and a `state` per sign-in, keeps the verifier only for the round trip, rejects a mismatched echoed `state`, and sends `codeVerifier` to `POST /auth/token` |

### Stage 3 — Capture and cycle · 15 tasks · 52 h

*Exit:* onboarding completes, cycle day is set, income recorded, an expense added in under 10 s on a phone, budget consumption visible for the live cycle — and the whole path proven as a **walking-skeleton debug APK on a real device** (T43b), not only in a desktop browser.

| Id | Status | FR | Task | Prereq | Est | Acceptance |
| --- | --- | --- | --- | --- | --- | --- |
| T34 | DONE | FR-02 `[+TABLE]` | `plan_cycle` (user_id, cycle_start, surplus, allocations jsonb, status) + materialisation via `CycleCalendar`; cycle-day change endpoint | T10, T31 | 4 h | Changing start day 25 → 1 re-derives the current cycle without orphaning rows; `[+TABLE]` trio satisfied; no code path calls `YearMonth` |
| T35 | DONE | FR-03 `[+TABLE]` | `income_source` (type, amount, frequency, start/end date) + CRUD; recurring vs one-off semantics | T34 | 3 h | Salary + rental return ₹1,27,000 for the current cycle; a one-off bonus appears only in its own cycle; `[+TABLE]` trio satisfied |
| T36 | DONE | FR-04 `[+TABLE]` | `category` + seeded taxonomy; `transaction` (date, amount, direction, category_id, mode, tag, source, dedupe_hash, idempotency_key) | T34 | 4 h | `dedupe_hash` stable across two identical inserts and different on a changed amount; unique index on (user_id, idempotency_key); `[+TABLE]` trio satisfied for both tables |
| T37 | DONE | FR-04 | Expense CRUD endpoints, idempotent create | T36 | 3 h | Replaying a create with the same idempotency key returns the original row and inserts nothing; warm p95 for create under 500 ms, measured and recorded |
| T38 | DONE | FR-05 `[+TABLE]` | `recurring_template` + materialiser; quarterly bills spread monthly | T36 | 4 h | A ₹6,000 quarterly bill posts ₹2,000/cycle and the three postings sum to exactly ₹6,000 in paise; running the job twice materialises once; `[+TABLE]` trio satisfied |
| T39 | DONE | FR-06 | Need/want/waste tagging endpoint + per-cycle tag aggregates | T37 | 2 h | The three-way split sums to total outflow; an untagged expense reports as untagged, never silently as `need` |
| T40 | DONE | FR-10 `[+TABLE]` | `category_budget` per cycle + budget-status endpoint over T23; threshold events | T23, T38 | 3 h | Status matches `RulesGoldenTest` arithmetic for a seeded cycle; the 80% event fires once; `[+TABLE]` trio satisfied |
| T41 | DONE | FR-07 | Unplanned-expense rebalance endpoint over T24 | T24, T40 | 3 h | Response names the absorbing waterfall step and revised safe-to-spend; figures equal the rules-engine result (no service-layer arithmetic) |
| T42 | DONE | FR-04, FR-06 | Web: expense entry — amount keypad, category, mode, note, tag, save | T37, T07 | 4 h | A scripted run completes amount → category → mode → save in 4 taps and under 10 s; 380 px dark; screen-reader labels on every control |
| T43 | DONE | FR-04 | Web: offline queue + idempotency-key sync; conflict resolution left to the API. Rides on T06's service-worker registration; dashboard caching stays in T79 | T42, T06 | 3 h | An expense entered offline appears locally and syncs once on reconnect; a double sync creates no duplicate row |
| T43b | TODO | INFRA | `[LOCAL]` **Walking-skeleton Android APK:** Capacitor wrapper, OAuth deep-link return, debug build installed on the owner's phone. Proves sign-in → expense entry → offline queue end-to-end on real hardware while the surface is still small | T33, T43 | 4 h | Debug APK installs on the device; Google sign-in completes through the deep-link return and the session survives an app restart; an expense entered with the device in airplane mode syncs exactly once on reconnect, creating no duplicate; app id read from config — `[?OQ-4]` placeholder `com.finpath.app`; `gradlew` is committed executable (`git update-index --chmod=+x web/android/gradlew`) and `git ls-files -s web/android/gradlew` shows mode `100755` |
| T44 | DONE | FR-03, FR-05 | Web: income sources and recurring templates screens | T35, T38 | 4 h | A quarterly template shows both the bill amount and the per-cycle spread; 380 px dark verified |
| T45 | DONE | FR-10 | Web: budget screen, per-category consumption, 80/100 banners | T40 | 3 h | Percentages equal the API response exactly (no client division); banner contrast ≥ 4.5:1 in both themes |
| T46 | TODO | FR-01 | Web: onboarding wizard shell — one question per screen, resumable, progress, consent + purpose step | T33, T31 | 4 h | Closing mid-wizard resumes at the same step; the wizard cannot complete without a recorded consent row |
| T47 | TODO | FR-01, FR-02 | Web: wizard steps for cycle day, income, fixed expenses | T46, T35, T38 | 4 h | Entering the persona's figures yields ₹1,27,000 income and ₹23,833 fixed in the API; cycle-day step defaults to 25 |

### Stage 4 — Debt · 13 tasks · 40 h

*Exit:* all five persona debts entered; payoff plan and roll-off timeline render on a phone; the simulator reproduces ₹52,804 / ₹36.44 L; a new EMI trips the guard.

| Id | Status | FR | Task | Prereq | Est | Acceptance |
| --- | --- | --- | --- | --- | --- | --- |
| T48 | DONE | FR-12 `[+TABLE]` | `loan` (lender, type emi/bullet/card_emi, principal_outstanding, rate, emi, emis_left, bullet_due_date) + CRUD. Lender is free text; no account-number column | T34 | 4 h | All three types round-trip; a bullet loan without a due date is rejected at the API boundary; **the user-entered `emi` is stored verbatim and is the source of truth for cash flow** — persisting ₹40,032 never silently becomes the computed ₹40,004; `emi` is nullable and derived only when the user states no EMI, with the row flagged as derived; `[+TABLE]` trio satisfied |
| T49 | DONE | FR-13 | Loan detail endpoint: effective rate, interest remaining, payoff date — all from rules | T48, T12 | 3 h | **Cash-flow figures use the stated EMI; the computed EMI appears only in simulations and for loans with no stated EMI.** Persona home loan reports the stated ₹40,032 in every cash-flow context, with its schedule, interest remaining and payoff date derived from that EMI; the computed ₹40,004 is surfaced only as a labelled comparison; gold loan 15.33%; a loan with a derived EMI is marked as such in the response; no arithmetic in controller or service (review assertion) |
| T50 | DONE | FR-14 | Payoff plan endpoint, `strategy=avalanche\|snowball` | T49, T14 | 3 h | Avalanche puts the gold loan first; an unknown strategy returns 400, never a silent default |
| T51 | DONE | FR-15 | Roll-off timeline endpoint | T50, T15 | 3 h | Returns the three freed-EMI steps with cycle ids matching T15 |
| T52 | DONE | FR-16 | Sinking-fund status endpoint + behind-schedule flag | T49, T13 | 3 h | ₹10,588/cycle for the gold loan; a deliberate ₹30 k shortfall flags `BEHIND` with the catch-up amount |
| T53 | DONE | FR-17 | Prepayment simulator endpoint (lump sum, EMI increase, target close date) | T49, T16 | 3 h | 13-year target returns ₹52,804 and ₹36.44 L saved; the response carries the assumption set used |
| T54 | DONE | FR-18 | Debt-trap guard endpoint, invoked automatically on loan create | T48, T17 | 2 h | A loan pushing the ratio past 50% returns BLOCK and does not persist without explicit client confirmation; the ratio equals T17's output |
| T55 | TODO | FR-12, FR-13, FR-01 | Web: loan list + detail; appends the debt step to the wizard | T49, T47 | 4 h | Five persona debts entered via the wizard total ₹63,332 EMI; detail renders all three loan types at 380 px dark |
| T56 | DONE | FR-14, FR-15 | Web: payoff timeline chart with roll-off steps (ngx-echarts) | T51, T50 | 4 h | Legible at 380 px in both themes; has an aria description and a table equivalent listing each loan's end date |
| T57 | DONE | FR-13 | Web: principal-vs-interest stacked area chart | T49 | 3 h | Series come from the loan detail endpoint; table-equivalent totals match the API to the paise |
| T58 | DONE | FR-18 | Web: EMI-to-income gauge with the 40% line + guard dialog | T54 | 3 h | Shows 49.87% and the 40% marker; colour is not the only indicator of WARN/BLOCK |
| T59 | DONE | FR-17 | Web: prepayment simulator screen | T53 | 3 h | Results shown as exact figures from the T53 endpoint (deterministic loan maths), with the assumed loan rate stated beside them and a note that floating rates can change; no single figure presented as a guaranteed outcome beyond that; no named security or product; no web-side money maths. Prepay-vs-invest comparison is out of scope: it belongs to FR-28 (Phase 2) |
| T60 | IN-PROGRESS | FR-16 | Web: sinking-fund card for bullet loans | T52 | 2 h | Shows set-aside, progress and behind-schedule state; 380 px dark verified |

### Stage 5 — Goals, FI, allocation, investments · 9 tasks · 30 h

*Exit:* the FR-01 wizard runs end-to-end (income, cycle, expenses, debts, assets, goals); the cycle's allocation reproduces ₹39,835; the FI date renders with a range.

| Id | Status | FR | Task | Prereq | Est | Acceptance |
| --- | --- | --- | --- | --- | --- | --- |
| T61 | TODO | FR-19 `[+TABLE]` | `goal` (type, target_amount, target_date, current_amount, priority) + CRUD + status endpoint | T34, T19 | 4 h | Marriage goal returns ₹28,846/cycle and an on-track flag matching T19; a sub-3-year horizon returns `SAFE_ONLY`; `[+TABLE]` trio satisfied |
| T62 | TODO | FR-20 | Emergency-fund goal auto-sized from actual outflow | T61, T18 | 2 h | Target recomputes when outflow changes; 1-month and 3-month figures match T18 |
| T63 | TODO | FR-21 | FI planner endpoint: corpus, FI date, required step-up SIP, return-range band | T61, T21 | 3 h | Gross corpus ₹3.84 Cr, net of rental ₹2.76 Cr, both ±1%; response includes low/base/high bands and the inflation/equity assumptions; `[?OQ-1]` EPF opening balance is a user-entered field defaulting to ₹0 |
| T64 | TODO | FR-22 | Allocation endpoint: run the waterfall for the current cycle, persist `plan_cycle.allocations` | T22, T61, T52 | 4 h | Surplus ₹39,835 ±1%; lines sum exactly to surplus; re-running the same cycle is idempotent; `[?OQ-3]` step-7 ratio defaults to 70/30, user-configurable |
| T65 | TODO | FR-23 `[+TABLE]` | `holding` (asset_class, name, invested, current_value, monthly_contribution) + CRUD + asset-class aggregate | T34 | 4 h | EPF, PPF, NPS, MF, stocks, FD and gold classes accepted; aggregate sums to per-class totals; no folio or demat identifier column; `[+TABLE]` trio satisfied |
| T66 | TODO | FR-19, FR-20, FR-01 | Web: goals screen with progress bars; appends the goal step to the wizard | T62, T47 | 3 h | Monthly need and on-track flag come from the API; progress conveyed by text as well as bar length |
| T67 | TODO | FR-21 | Web: FI planner with corpus projection line and return-range band | T63 | 4 h | Band and assumptions visible without scrolling at 380 px; screen carries an educational label; chart has a table equivalent |
| T68 | TODO | FR-22 | Web: cycle allocation display (waterfall steps, amounts, which step absorbed an overrun) | T64 | 3 h | Displayed lines sum to the API surplus; no client arithmetic; 380 px dark verified |
| T69 | TODO | FR-23, FR-01 | Web: investments screen + allocation donut; appends the assets step to the wizard | T65, T66 | 3 h | Donut has a table equivalent and non-colour labels; `[?OQ-1]` EPF balance is asked for, never derived; a full FR-01 wizard run is recorded |

### Stage 6 — Dashboard, consistency and shipping · 17 tasks · 57 h

*Exit:* the home screen answers the three section-8 questions in one call under 2 s on throttled 4G; the Android build is polished on a fresh install (the APK itself already worked at T43b); backups run; DPDP export/delete verified against every shipped table.

| Id | Status | FR | Task | Prereq | Est | Acceptance |
| --- | --- | --- | --- | --- | --- | --- |
| T70 | TODO | FR-26 `[+TABLE]` | `net_worth_snapshot` written at cycle close (assets − liabilities as at that cycle) | T65, T48 | 3 h | Closing a cycle writes exactly one snapshot; backfilling a closed cycle is rejected; value equals the rules-engine result; `[+TABLE]` trio satisfied |
| T71 | TODO | FR-26 `[+TABLE]` | `checklist_item` (plan_cycle_id, type, amount, done_at) + per-cycle generation + mark-done + streak from T25 | T34, T25 | 4 h | Generation produces one item per EMI, SIP and goal transfer; marking done twice is idempotent; streak matches T25; `[+TABLE]` trio satisfied |
| T72 | TODO | FR-27 | Reminder scheduling: due-date and cycle-day events, the 5-days-before card rule, cold-start-tolerant scheduler | T71, T48 | 3 h | A loan due on the 5th generates a reminder dated the 31st/1st per the rule; a simulated 12 s first query does not cause a double-send |
| T73 | TODO | FR-27 | `[LOCAL]` Web Push subscription + free-tier SMTP fallback | T72 | 3 h | Push delivered to a subscribed browser; with push denied the email path fires; notification bodies contain no amount or merchant text |
| T74 | TODO | FR-10, FR-21, FR-26 | `GET /dashboard` single aggregate: cycle progress, spent vs budget, days left, safe-to-spend/day, net-worth series, FI date + delta, key statistics | T64, T70, T71 | 4 h | One request returns every home-screen figure; warm p95 under 600 ms; a cold first call returns correct data without an error status |
| T75 | TODO | FR-10 | Web: home cycle-progress ring, spent vs budget, days left, safe-to-spend | T74 | 3 h | Ring values equal the API payload; ring has a text equivalent; 380 px both themes |
| T76 | TODO | FR-21, FR-26 | Web: net-worth line + FI countdown card with change vs last cycle | T74 | 3 h | Series from snapshots, never recomputed client-side; FI date shown with range and assumptions |
| T77 | TODO | FR-06, FR-10 | Web: spending screens — category donut with need/want/waste, month-on-month bars against each category's 3-month average | T39, T40 | 4 h | Both carry table equivalents; comparison labels use **cycle** labels, never calendar-month names, unless the cycle starts on the 1st |
| T78 | TODO | FR-26 | Web: monthly checklist, streak, consistency heatmap | T71 | 4 h | Heatmap cells labelled for screen readers; streak matches the API; cells distinguishable without colour |
| T79 | TODO | INFRA | PWA: service worker, cache last dashboard, offline shell, install prompt | T74, T06 | 4 h | With the network disabled the last dashboard renders from cache with a clear staleness label; Lighthouse PWA installability passes |
| T80 | TODO | INFRA | `[LOCAL]` Android final polish: app icon and splash, re-verify the wrapper after PWA caching (T79) lands, plugin/permission audit, fresh-install run-through | T43b, T79 | 2 h | A fresh install on a wiped device completes onboarding → expense entry → dashboard with the T79 service worker active; no regression in the T43b deep-link sign-in; icon and splash render at the device's density. No Play Store or release signing in MVP |
| T81 | TODO | INFRA | Accessibility, dark-mode and 380 px audit across every shipped screen and chart | T75–T78 | 4 h | axe reports zero serious/critical issues; each of the 12 in-scope charts has an aria description and a table equivalent; all contrast pairs ≥ 4.5:1 |
| T82 | TODO | INFRA | Performance pass: dashboard under 2 s on throttled 4G, expense save under 500 ms, cold-start skeleton that never shows an error | T74, T37 | 3 h | Throttled Lighthouse records dashboard LCP under 2 s; create-expense warm p95 under 500 ms; a 12 s cold first request shows a skeleton and succeeds, measurement recorded in the PR |
| T83 | TODO | INFRA | `[LOCAL]` Nightly `pg_dump` to object storage, 7-day retention, documented restore | T04 | 3 h | A dump restores into a scratch database with matching row counts; credentials from env only; dump size recorded against the 0.5 GB budget |
| T84 | TODO | INFRA | `[LOCAL]` Deploy: compose on the Oracle ARM VM, TLS, env secrets, web to static hosting, documented ₹500–1,500/mo exit path | T08, T79 | 4 h | Public HTTPS serves `/actuator/health`; the same compose file runs unchanged against a generic Postgres host, proven locally; no managed-service-specific code — `[?OQ-4]` domain is a config value; low priority, T29 review follow-ups: V2_1 migrates cleanly on a throwaway Neon branch before the first prod deploy; V2_1 refuses a pre-existing `finpath_signin` with LOGIN, SUPERUSER or BYPASSRLS; V2_1 gives a clear error when a second non-superuser migrator lacks ADMIN on `finpath_signin`; `JdbcUserAccountsTest`'s role rename is crash-safe (each cleanup step guarded, leftover `finpath_signin_parked_*` restored on start) |
| T85 | TODO | INFRA | DPDP close-out: export covers every shipped table, deletion verified empty, consent screen live, full cross-tenant RLS matrix | T31, T70, T71 | 4 h | Export contains a section for each of the 12 shipped tables; after `DELETE /me` every table returns zero rows; one RLS test per table, and the suite fails if a table lacks one |
| T86 | TODO | INFRA | `compliance-review` + `reviewer` pass over the MVP surface | T85, T81 | 2 h | Zero findings on: stored identity/account numbers, AI as a number source (assert no AI path exists in MVP), specific-security wording, market-dependent projections without ranges (e.g. T67 FI planner; deterministic loan maths such as T59 prepayment is exempt, but must state its assumed rate), secrets in code |

---

## 4. Totals

| Stage | Tasks | Hours |
| --- | --- | --- |
| 0 — Skeleton and pipes | 8 | 23 |
| 1 — Rules engine and golden tests | 20 | 63 |
| 2 — Identity, tenancy, DPDP spine | 6 | 22 |
| 3 — Capture and cycle | 15 | 52 |
| 4 — Debt | 13 | 40 |
| 5 — Goals, FI, allocation, investments | 9 | 30 |
| 6 — Dashboard, consistency, shipping | 17 | 57 |
| **Total** | **88** | **287** |

287 hours is roughly **7 focused weeks full-time, or 5–6 months at 12 h/week of evenings**. Stage 1 alone is 22% of the build and ships no visible UI — that is correct, not a problem to engineer around.

---

## 5. Schema and migration summary

Twelve tables ship in the MVP, each in its own forward-only Flyway migration, each carrying an RLS policy, an exporter and a deleter:

`users` (V2, T28) · `consent` (V3, T31) · `plan_cycle` (V4, T34) · `income_source` (V5, T35) · `category` + `transaction` (V6, T36) · `recurring_template` (V7, T38) · `category_budget` (V8, T40) · `loan` (V9, T48) · `goal` (V10, T61) · `holding` (V11, T65) · `net_worth_snapshot` (V12, T70) · `checklist_item` (V13, T71).

Not built in MVP: `scenario` (FR-28, P2) and any household/sharing tables (FR-32, P3).

Rules binding every migration: money is `bigint` paise, never floating point; no account, card, PAN or Aadhaar column anywhere; `transaction` carries `dedupe_hash`, `source` and `idempotency_key` from day one even though MVP only writes `source='manual'`; amortisation schedules are **computed on request and never persisted** — 297 rows per loan per query would be the single largest consumer of the 0.5 GB budget for no benefit.

---

## 6. Free-tier consumption

| Resource | Allowance | MVP consumption | Call-out |
| --- | --- | --- | --- |
| Neon storage | 0.5 GB | ~10–15 MB/year for one household at ~1,000 transactions/month, plus indexes | Comfortable, but only because schedules are not persisted (T49) and statements are out of scope. Measure at T83. |
| Neon compute | 100 CU-hours/month | Interactive use only | **Do not add a keep-warm ping.** A 5-minute ping at 0.25 CU costs 0.25 × 730 ≈ **183 CU-hours/month** — nearly double the allowance. Cold start is absorbed by the UI skeleton (T82) and the offline queue (T43) instead. |
| Oracle ARM VM | 2 OCPU / 12 GB since Aug 2026; idle instances reclaimable | One Docker container | T84 stays generic compose on standard Postgres. If capacity is unobtainable the API moves to any ₹500/mo VPS with no code change; web hosting is independent. |
| Static hosting egress | Cloudflare Pages / Vercel free | Small bundle, charts code-split | Keep ngx-echarts lazy-loaded per chart route (T56, T57, T67, T77). |
| GitHub Actions | Free personal quota | ~6 min/run with caches and path filters | T05's path filters matter; without them every web-only commit also pays for a Maven run. |
| Claude API | Hard monthly cap | **₹0 at runtime** — all AI FRs are P2 | The only Claude spend in MVP is development-time. |

---

## 7. Open questions and resolved decisions

### Resolved — these are now the golden baseline

| Id | Decision | Tasks |
| --- | --- | --- |
| **OQ-5** | **Interest saved asserts ₹36.44 L**, computed as `interest@297 − interest@156`. The BRD's "about ₹36 L" is rounded prose and is not the assertion. Both legs reproduce within 0.1%, so they stand unchanged — **never re-baseline the ₹40,032 or ₹52,800 legs to make ₹36 L fit.** | T16, T26 |
| **OQ-6** | **Surplus by Sep 2027 asserts ₹60,835, pre-insurance.** The BRD's ₹59,000 already deducted roughly ₹2,000 for an unpriced health-insurance premium; the fixture's figure is the pre-insurance roll-off (₹39,835 + ₹8,000 + ₹6,000 + ₹7,000). Insurance is modelled as a waterfall step-1 expense line (OQ-2), not baked into the surplus. **Do not alter persona inputs to reach ₹59,000.** | T15, T22, T26 |

Both resolutions are recorded in the T10b fixture with a comment naming this section, so a later reader sees why the number differs from the BRD's prose.

### Accepted assumptions — proceed as written

| Id | Question | Tasks | Assumption |
| --- | --- | --- | --- |
| OQ-1 | EPF total balance; whether the previous employer's PF was transferred | T63, T69 | EPF opening balance is a user-entered `holding` field defaulting to ₹0. The FI projection shows a range, so an unknown opening balance degrades precision rather than blocking the build. Do not hard-code a figure. |
| OQ-2 | Health insurance premium once a policy is chosen | T22, T38, T64 | Insurance is a configured fixed-expense line in waterfall step 1; absent = ₹0. Onboarding asks and flags "not yet bought". OQ-6 implies the owner's working figure is ~₹2,000/month — record it when the policy is chosen rather than assuming it now. |
| OQ-3 | The step-7 SIP-vs-prepayment ratio, which the BRD never states | T22, T64 | Default 70% long-term SIP / 30% sub-12% prepayment, in `RuleConfig`, user-configurable. |
| OQ-4 | App name and domain | T29, T43b, T80, T84 | Placeholder `finpath` / `com.finpath.app`; OAuth client id, redirect URIs, app id and domain live only in env config, so a rename is a config change rather than a refactor. |

Angular vs React is closed — settled in `CLAUDE.md`, not re-litigated here.

### Figures verified against the BRD

Computed directly, not copied from the BRD's prose. The last two rows are where the assertion deliberately differs from the document.

| Figure | BRD | Asserted | Δ | Status |
| --- | --- | --- | --- | --- |
| Home-loan EMI (cash flow) | ₹40,032 | ₹40,032 *(user-entered, authoritative)* | — | stated EMI wins (T48) |
| Home-loan EMI (computed) | ₹40,032 | ₹40,004 | −0.07% | simulation only |
| Interest remaining | ~₹69 L | ₹68.65 L | −0.50% | ✓ |
| EMI to close in 13 y | ₹52,800 | ₹52,804 | +0.01% | ✓ |
| Gold-loan effective rate | 15.3% | 15.33% | +0.2% | ✓ |
| Monthly surplus | ~₹39,800 | ₹39,835 | +0.09% | ✓ |
| Interest saved | ~₹36 L | **₹36.44 L** | +1.22% | resolved, OQ-5 |
| Surplus by Sep 2027 | ~₹59,000 | **₹60,835** | +3.11% | resolved, OQ-6 — BRD figure was post-insurance |

---

## 8. Risk and sequencing note

**Most likely to slip, in order.**

1. **Stage 1's golden figures (T26).** OQ-5 and OQ-6 are now resolved and the asserted values (₹36.44 L, ₹60,835) deliberately differ from the BRD's prose. The remaining risk is a later reader treating that gap as a bug and "fixing" it — widening a tolerance or editing a persona input to match the document, which destroys the only external check the engine has. Mitigation: the fixture carries both figures with a comment pointing at section 7, and `assertWithinOnePercent` is the file's only tolerance mechanism, so any change to it appears as a one-line diff in review.
2. **Stage 2's auth and RLS plumbing (T29, T30).** Google OAuth2 redirect handling plus transaction-scoped `SET LOCAL` reads as 8 hours and behaves like 20, particularly where the Capacitor deep-link return exposes a redirect assumption made in T29. Mitigation: build T30's `CrossTenantTest` base before the first business table so the cost is paid once, and let T43b's walking-skeleton APK surface the deep-link problem in Stage 3 — while the app is still three screens — rather than at T80 with the whole MVP built on top of it.
3. **Chart work (T56, T57, T67, T77, T81).** Twelve charts each needing 380 px, dark mode, a screen-reader description and a table equivalent is the most commonly undersized block in this plan. Mitigation: build one chart to the full standard first (T56), extract a `ChartShell` wrapper supplying the aria description and table equivalent, and treat T81 as verification rather than remediation. If time runs short, the honest cut is the principal-vs-interest area chart (T57) and the consistency heatmap (T78) — both are supporting visuals and neither answers one of the three home-screen questions.
4. **Oracle ARM capacity (T84).** The 2026 allowance cut means the instance may be unobtainable or reclaimed. The mitigation is structural and already in the plan: plain Docker Compose on standard Postgres, no managed-service APIs, web tier hosted independently — so the fallback is a ₹500/mo VPS and a DNS change.

**Sequencing rules that must not be relaxed.** Stage 1 finishes before any screen displays a figure, or the UI grows its own arithmetic and the two diverge. Stage 2 finishes before the first business table, or the `[+TABLE]` trio becomes a retrofit across twelve tables. The onboarding wizard (FR-01) is deliberately a shell in Stage 3 with its debt, goal and asset steps appended in Stages 4 and 5 — attempting FR-01 in one task would require every entity to exist already and would be an 8-hour job wearing a 4-hour label.
