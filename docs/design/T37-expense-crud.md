# T37 — expense CRUD, idempotent create (FR-04, design)

Scope: `api/.../expense` controller, service, advice, `NoteGuard`; one added repository method
(`repeatedHashes`). No migration (V6 has everything). Follows the T35 income pattern and `docs/api-conventions.md`.

## Endpoints (`/expenses`, owner = `@CurrentUserId` only; a `userId` in a body is ignored)
- `POST /expenses` body `{txnDate, amountPaise, categoryCode, mode, tag?, note?}`; header `Idempotency-Key`
  (8-64 chars of `[A-Za-z0-9._:-]`, else 400; generated server-side as a UUID when absent, which makes that
  create non-replayable). Direction is always OUT; IN-only categories (INCOME, REFUND, OTHER_IN) are 400.
  **201** new row; **200** replay with the original row (nothing inserted); **409 `idempotency_conflict`** same key,
  different payload. Payload equality = same `dedupe_hash` (date, amount, direction, category, mode) plus same tag
  and note. Concurrent first-use of a key is settled by `ON CONFLICT DO NOTHING` in the repository, then the same
  comparison. A key is scoped per user. If the original row was later edited, replaying the original payload is a 409.
- `GET /expenses?from=&to=` (both inclusive, yyyy-MM-dd, max 366 days) or neither = current cycle from
  `users.cycle_start_day` (Asia/Kolkata, `CycleCalendar`). One bound only, to before from, or too long = 400.
  Response `{from, toExclusive, items[]}`, newest first, OUT rows only. No pagination in MVP (range cap instead).
- `PUT /expenses/{id}` full replace of editable fields (hash recomputed; idempotency key and source kept).
- `DELETE /expenses/{id}` 204. Unknown id or another user's row: 404 `not_found`. Writes need consent (403
  `consent_required`). Bad input 400 `invalid_request`. All bodies are the fixed `{status, error}` shape.
- View: `id, txnDate, amountPaise (integer), categoryCode, mode, tag, source, note, possibleDuplicate`. The hash is
  not exposed.

## Duplicate hint
`possibleDuplicate` is true when the caller owns another row with the same `dedupe_hash`. Never blocks (two chai
payments in a day are legitimate). One batched query per response (`repeatedHashes`).

## Note validation (compliance follow-up from T36)
Trimmed; blank becomes null; max 140; rejected (400) if it has 9+ consecutive digits (any Unicode digit), digit
groups separated by single spaces or hyphens totalling 12+ digits (card/Aadhaar layouts), or PAN-shaped text
(5 letters, 4 digits, 1 letter, case-insensitive, anywhere). Accepted false positives such as "order 123 456 789 012"
are the price of the privacy rule. The note is never logged and never in an error body (fixed bodies; exceptions carry
no message). The note is returned to its owner in normal responses and is exported/deleted with the row.

## Errors and logging
`ExpenseExceptionHandler` (same shape as income) plus `DataAccessException` -> 500 `server_error`, logging the class
name only. Because constraint-violation messages carry the failing row, `application.yml` now pins
`org.springframework.transaction` and `org.springframework.jdbc` to INFO (Spring logs the rollback exception at DEBUG);
`ExpenseLogPinTest` asserts the pin. New code `idempotency_conflict` added to `docs/api-conventions.md`.

## Tests
`NoteGuardTest`, `ExpenseControllerTest` (mocked service: principal-only, statuses, fixed bodies, integer paise),
`ExpenseEndpointDbTest` (real DB under RLS through the controller: replay inserts nothing, 409, per-user keys,
generated key, duplicate hint, cross-tenant list/PUT/DELETE = 404 and body userId ignored, cycle/range, consent 403,
note never echoed or logged, DB-constraint error generic, latency), `ExpenseLogPinTest`, `OpenApiTest` entries.

## Measurement: warm create latency
`ExpenseEndpointDbTest.warmCreateP95IsUnder500Ms`: 20 warm-up creates, then 200 sequential `POST /expenses`
(MockMvc through controller, service, consent check, RLS transaction, insert, duplicate-hint query) against local
Postgres 16 on the same machine, each with a fresh key. Measured in this environment (two full runs):
**p50 42 ms, p95 52.6 ms and 49.3 ms, max 57-67 ms**; the test asserts p95 < 500 ms. Caveats: no network hop, no
TLS, no real HTTP server, no Neon; the figure covers application and local-DB time only. T82 must re-measure
against the deployed stack. A cold Neon wake is out of scope here (slow, not an error).

## Open questions
1. Replay after the original was edited returns 409 (payload compared to the current row). Assumed acceptable.
2. 200 vs 201 on replay chosen as 200 (the resource already existed); clients treat both as success.

## Review follow-ups (open, low)
- No two-thread test of concurrent same-key creates yet; `ON CONFLICT DO NOTHING` + same-transaction SELECT is believed correct under READ COMMITTED.
- A replay of a create whose row was since deleted inserts a new row (accepted MVP limitation).
- `txnDate` is not range-bounded (typo years accepted).
