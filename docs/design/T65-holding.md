# T65 — holding table, CRUD and asset-class aggregate (FR-23, design)

Code: `api/.../holding` (`HoldingController`, `HoldingService`, `HoldingRepository`, exception handler), pure aggregate
`rules/HoldingTotals`, migration `V11__holding.sql`. Follows T61 (goal) exactly; the owner is `@CurrentUserId`, a
`userId` in a body is ignored.

## Data (V11)
`holding(id, user_id -> users ON DELETE CASCADE, asset_class, name, invested_paise, current_value_paise,
monthly_contribution_paise, created_at, updated_at)`. Amounts are `bigint >= 0`, default 0. `asset_class` is a closed
list: epf, ppf, nps, mf, stocks, fd, gold. RLS `ENABLE` + `FORCE`, policy `holding_self` (WITH CHECK owner and
`finpath_has_consent()`), grants to `app_user`.

## name is a label, not an identifier
`name` is the only free text: 1..60 chars (DB CHECK and API), meant for labels like "Employer EPF". It is NOT for folio,
demat, account, PAN or UAN numbers. The API additionally rejects a name containing 9 or more digits (spaces and hyphens
ignored) with 400. There is no identifier column; `HoldingNoIdentifierColumnTest` pins the exact column list. `[?OQ-1]`:
an EPF balance is entered by the user, never derived.

## API (`/holdings`)
`POST`, `GET` (class, then creation order), `GET /{id}`, `PUT /{id}` (full replace), `DELETE /{id}`. Body
`{assetClass, name, investedPaise?, currentValuePaise?, monthlyContributionPaise?}`; paise are strict JSON integers.
`GET /holdings/aggregate` -> `{classes: [{assetClass, investedPaise, currentValuePaise, monthlyContributionPaise,
count} x 7, in fixed order, zeros for classes not held], total: {...same, assetClass "total"}}`. Per-class sums are a
SQL `GROUP BY` under RLS; `HoldingTotals.aggregate` (pure, `Math.addExact`) fills missing classes and adds the grand
totals. The controller and service do no arithmetic. Errors as goal: 400 `invalid_request`, 403 `consent_required`
(writes), 404 `not_found` (unknown id or another user's row). Nothing logs names or amounts.

## `[+TABLE]` trio
Registry entries in `DataSubjectRegistry` (export after `goal`; delete after `goal`, before `users`);
`HoldingCrossTenantTest`; `RlsMetadataTest`, `DataSubjectRegistryTest`, consent-gate and schema-lint tests cover the
table automatically; `DataSubjectRightsDbTest` seeds and exports a holding row.

## Open questions
1. Gains/XIRR are out of scope; only totals are returned.
2. Widening the class list needs a migration.
3. Digit-run heuristic for `name` is a guard, not proof; the UI copy (T69) must say not to enter account numbers.
