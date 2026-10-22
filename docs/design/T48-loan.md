# T48 — loan table and CRUD (FR-12, design)

Code: `api/.../loan` (`LoanController`, `LoanService`, `LoanRepository`), one small addition to
`api/.../rules/BulletLoan`, migration `V9__loan.sql`. Follows T38/T40 (conventions, RLS, registry, error codes).
Web is a later task; FR-13 figures (effective rate, interest left, payoff date) are T49, not built here. T48 only
stores loans and fills a derived EMI when none is stated.

## 1. Rules (`api/.../rules`, pure)
Rates in the rules package are **annual fractions as `BigDecimal`** (0.0835 = 8.35%), scale `MathPolicy.RATE_SCALE`
(10); money is `Money` (paise). The loan table and API match that: no percent and no basis points.

Existing, called as is (the service does no arithmetic):
- `AmortisingLoan.emi(Money principal, BigDecimal annualRate, int tenureMonths) -> Money` (type-guarded overload
  `emi(Loan, int)`). Used for `type = emi` when `emi` is not stated: `principal = principal_outstanding`,
  `tenure = emis_left`.

New (rules-engine, one function): `BulletLoan.monthlyInterest(Money principal, BigDecimal annualRate) -> Money`
= `principal * rate / 12`, `HALF_UP` to paise (inverse of `effectiveAnnualRate`), rejects negatives. Used for
`type = bullet` when `emi` is not stated.

`card_emi` has **no derivation**: its instalment is the lender's figure and is required (Q2).

Tests (`BulletLoanTest` addition): persona gold loan principal and rate give a monthly interest within one paise
of the stated figure; zero principal gives zero; negative rate/principal rejected; round trip with
`effectiveAnnualRate`. `RulesGoldenTest`: add fixture keys `loan.home_emi_stated_paise`, `loan.home_principal_paise`,
`loan.home_rate`, `loan.home_emis_left`, `loan.gold_monthly_interest_paise`, `loan.gold_principal_paise`,
`loan.gold_rate` to `persona.sample.properties` (fake values with the same shape; the owner mirrors the real ones
in `persona.local.properties`). Assertion: stated EMI differs from `AmortisingLoan.emi(...)` for the home loan
(that is the point of T48) and the stored value equals the stated one.

## 2. Stated EMI vs derived EMI
- `emi_paise` stated by the user: **stored verbatim**, `emi_derived = false`, never recomputed or "corrected". This
  is the cash-flow source of truth (persona ₹40,032 stays ₹40,032, not the computed ₹40,004).
- `emi_paise` omitted or null (types `emi`, `bullet`): `LoanService` calls the rules function above, **stores the
  derived value in `emi_paise` and sets `emi_derived = true`** (persisted flag, chosen over compute-on-read so that
  every reader, T49/T54/T72 included, sees one number that cannot drift with a later rules change, and so the flag
  states exactly what was true at write time). The flag is returned in every view.
- PUT is a full replace: a PUT that omits `emi` re-derives from the new principal/rate/emis_left; a PUT that
  states `emi` clears the flag. A derived value is never carried over silently. No partial update, so stale derived
  values cannot survive an edit.
- The computed figure for a loan that has a stated EMI is **not** stored or returned by T48; T49 shows it only as a
  labelled comparison.

## 3. Data (V9)
`loan(id uuid pk default gen_random_uuid(), user_id uuid NOT NULL -> users ON DELETE CASCADE, lender text NOT NULL
CHECK (char_length(lender) BETWEEN 1 AND 60), type text NOT NULL CHECK IN ('emi','bullet','card_emi'),
principal_outstanding_paise bigint NOT NULL CHECK (>= 0), rate numeric(12,10) NOT NULL CHECK (rate >= 0 AND rate <
1), emi_paise bigint CHECK (emi_paise IS NULL OR emi_paise > 0), emi_derived boolean NOT NULL DEFAULT false,
emis_left integer CHECK (emis_left IS NULL OR emis_left > 0), bullet_due_date date, upfront_fee_paise bigint NOT NULL
DEFAULT 0 CHECK (>= 0), created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT
now())`.
Table-level CHECKs (the DB repeats the API rules, so a bad row cannot arrive any other way):
- `loan_bullet_due CHECK ((type = 'bullet') = (bullet_due_date IS NOT NULL))` — bullet needs a due date, others
  must not have one.
- `loan_emis_left CHECK ((type = 'bullet') = (emis_left IS NULL))` — emi and card_emi need a tenure.
- `loan_derived_has_emi CHECK (NOT emi_derived OR emi_paise IS NOT NULL)`.
- `loan_card_emi_stated CHECK (type <> 'card_emi' OR (emi_paise IS NOT NULL AND NOT emi_derived))`.
- `loan_fee_card_only CHECK (type = 'card_emi' OR upfront_fee_paise = 0)`.
Money columns are `bigint` paise; the rate is `numeric(12,10)` to hold the rules' fraction at `RATE_SCALE`.
- **Lender is free text but capped at 60 chars and is a display name only** ("HDFC Home Loan"). The API trims it,
  rejects blank or over-length, and the UI tells the user not to enter account numbers. There is **no
  account-number, card-number or ID column**. Lender is never logged and never used as a key. (Digit-run rejection
  is a possible extra guard; see Q4.)
- Index `(user_id, type)`. RLS `ENABLE` + `FORCE`, policy `loan_self` copied from V8 (`USING user_id = current`,
  `WITH CHECK ... AND finpath_has_consent()`); `GRANT SELECT, INSERT, UPDATE, DELETE` to `app_user`.
- No FK to `category` or `transaction`; loans are standalone (EMI postings are a later concern).

## 4. API (`/loans`, authenticated; owner = `@CurrentUserId`, a `userId` in a body is ignored)
| Call | Body / result |
| --- | --- |
| `POST /loans` | `{lender, type, principalOutstandingPaise, rate, emiPaise?, emisLeft?, bulletDueDate?, upfrontFeePaise?}` -> 201 + view |
| `GET /loans` | list of views, ordered by `type`, then `createdAt` |
| `GET /loans/{id}` | view |
| `PUT /loans/{id}` | same body, full replace -> 200 + view |
| `DELETE /loans/{id}` | 204 |

`type` in the API is the DB value (`emi`, `bullet`, `card_emi`); the service maps to `LoanType`. `rate` is a decimal
**string** fraction ("0.0835"), parsed with `new BigDecimal(String)`, rejected if it has more than 10 decimal places,
is negative, or is >= 1. Amounts are JSON integers (paise `long`); strings, decimals or negatives are 400.
View: `id, lender, type, principalOutstandingPaise, rate (string), emiPaise, emiDerived, emisLeft, bulletDueDate,
upfrontFeePaise, createdAt`. `emiPaise` is the stored value verbatim for both stated and derived rows.

Validation (all 400 `invalid_request`, fixed message, before any DB write; the DB CHECKs are the second line):
unknown `type`; lender blank or > 60 chars; `bullet` without `bulletDueDate` (**the acceptance case**); `bulletDueDate`
on a non-bullet; `emisLeft` missing for emi/card_emi or present for bullet; `emisLeft` <= 0; `card_emi` without
`emiPaise`; `emiPaise` <= 0; `upfrontFeePaise` on non-card or >= principal; rate out of range; negative amounts.
Other errors: 403 `consent_required` on POST/PUT/DELETE before consent (service checks first, as T38); 404
`not_found` for an unknown id or another user's row (no distinction). Nothing logs lender, amounts or bodies. Add the
endpoints to `OpenApiTest`. No Debt-trap call here; T54 hooks `POST /loans` later.

## 5. `[+TABLE]` trio for `loan`
- Export: `new TableRef("loan","user_id")` in `DataSubjectRegistry.EXPORT` after `category_budget` (no category
  column, so the plain constructor). Money keys keep the `_paise` suffix; `rate` is exported as stored.
- Deletion: add to `DELETE` before `users`.
- RLS: `LoanCrossTenantTest`: B cannot select, update or delete A's loan; insert for another user, or without
  consent, is rejected. `DataSubjectRegistryTest` must pass.
- `LoanApiDbTest` (acceptance): all three types round-trip with identical field values; bullet without due date is
  400 `invalid_request` and writes nothing; a direct SQL insert of a bullet without a date fails the CHECK; a stated
  `emiPaise` from `GoldenFixture` (`loan.home_emi_stated_paise`) reads back unchanged with `emiDerived=false` even though
  `AmortisingLoan.emi` for the same inputs differs; omitting `emiPaise` stores the rules result with
  `emiDerived=true` (test computes the expected value from the rules function on the same fixture inputs); PUT
  with a stated EMI clears the flag; a review assertion that `LoanService` imports no `BigDecimal` arithmetic.

## 6. Screens
None in T48. For the later loans screen (380 px, light and dark): list rows from `GET /loans` showing lender, type
badge, outstanding, `emiPaise` and a "calculated for you" tag when `emiDerived`; add/edit sheet with a lender field
hinted "name only, no account numbers". Components render API numbers; the fraction is formatted as a percentage
for display only; no derivation.

## 7. Open questions (assumptions)
1. Rate stored as a fraction string matching `AmortisingLoan`; UI converts for display/entry. Assumed.
2. `card_emi` instalment must be stated (rate may be 0% with a fee), so no derivation; `emi` loans with a stated
   EMI trust it even if inconsistent with rate/tenure.
3. For bullet loans `emi_paise` means the monthly interest paid; derived from principal x rate / 12 when omitted.
4. Reject a lender containing a run of 9 or more digits? Not in MVP; cap plus UI hint only. Reviewer may tighten.
5. `upfront_fee_paise` added now because T49 needs it for the card-EMI effective rate (`Loan.CardEmi`) and a later
   column change would need another migration; default 0.
6. `principal_outstanding_paise` and `emis_left` are user-maintained snapshots; no automatic amortisation.
7. The column is named `principal_outstanding_paise` and `emi_paise` (BRD says `principal_outstanding`, `emi`) to
   keep the paise suffix used elsewhere.

## 8. Plan coverage
Covers **T48** (table, CRUD, type rules, stated-EMI-wins, derived flag, `[+TABLE]` trio). Feeds T49, T54, T70, T72.
Additions not in PLAN.md: `BulletLoan.monthlyInterest` rules function, `emi_derived` and `upfront_fee_paise`
columns, `loan.*` fixture keys (sample plus owner's local file), `GET /loans/{id}`, lender length cap, `_paise` column
naming.
