# T36 — category and transaction tables (FR-04, design)

Scope: migration `V6__category_transaction.sql`, `rules/TransactionHash`, a `TransactionRepository`
(package `api/.../expense`, same JDBC style as `IncomeService`), registry and tests. **No endpoints** (T37), no UI
(T42/T43). Follows the T35 / V4 / V5 pattern.

## 1. Rules (`api/.../rules`, pure)
`TransactionHash.dedupeHash(LocalDate txnDate, long amountPaise, Direction direction, String categoryCode, String mode) -> String`
(64-char lowercase hex SHA-256, `java.security.MessageDigest` + `HexFormat`, no DB/clock/user).

- Canonical input, UTF-8: `v1|<yyyy-MM-dd>|<amountPaise>|<IN|OUT>|<CATEGORY_CODE>|<MODE>`. Code and mode are
  `trim().toUpperCase(Locale.ROOT)`; blank, null or `amountPaise <= 0` throws `IllegalArgumentException`.
- **User-independent on purpose**: `user_id` is not hashed. Uniqueness is always scoped by the `user_id` column
  in the index, and a user-free hash keeps the function pure and testable against fixed vectors.
- **Category by stable `code`, not UUID**, so the hash survives re-seeding or an environment rebuild.
- **Excluded**: `note` and `tag` (editing a note or re-tagging must not make a row look new), `source`,
  `idempotency_key`, timestamps. The `v1|` prefix lets a future change of field set be versioned.
- The hash is a **duplicate hint, not a uniqueness constraint**: two identical ₹40 chai payments in a day are
  legitimate. Index is non-unique; T37 may surface "possible duplicate", never reject. Idempotency (below) is the
  hard guard.
- Not a secret and not a privacy control: inputs are low-entropy, so it is stored only on the RLS-protected table
  and exported/deleted with the row.

Tests (`TransactionHashTest`): identical inputs give equal hashes; changed amount (also date, direction,
category, mode) gives a different hash; case/whitespace in code and mode normalise equal; one fixed known-answer
vector; invalid input throws. No persona golden assertion: FR-04 introduces no persona figure.

## 2. Data (V6)
**`category`** (shared reference data, system taxonomy only): `id uuid pk default gen_random_uuid()`,
`code text NOT NULL UNIQUE CHECK (code = upper(code))`, `name text NOT NULL`,
`parent_id uuid REFERENCES category(id)` (nullable, for later sub-categories; seeded flat now),
`sort_order int NOT NULL`. **User-owned custom categories are out of scope** (BRD lists none; they would need
`user_id` + RLS and a merge story). `budget_per_cycle` from the BRD data model is *not* a column: per-cycle
budgets are `category_budget` (V8, T40).
Seed (in V6, `INSERT ... VALUES`): GROCERIES, DINING, RENT, UTILITIES, TRANSPORT, FUEL, HEALTH, EDUCATION,
INSURANCE, EMI, SUBSCRIPTIONS, SHOPPING, ENTERTAINMENT, TRAVEL, PERSONAL_CARE, GIFTS_DONATIONS, TAXES_FEES,
OTHER (OUT), and INCOME, REFUND, OTHER_IN (for `direction = IN`). Display names are plain English; no seed
carries a default need/want/waste tag (an untagged row must stay untagged, T39).
No RLS (no personal data). `GRANT SELECT ON category TO app_user` only; no write grant, so the app role cannot
alter the taxonomy.

**`transaction`** (quote the name in SQL if the dialect requires; it is non-reserved in PostgreSQL):
`id uuid pk default gen_random_uuid()`, `user_id uuid NOT NULL REFERENCES users ON DELETE CASCADE`,
`txn_date date NOT NULL`, `amount_paise bigint NOT NULL CHECK (amount_paise > 0)`,
`direction text NOT NULL CHECK IN ('IN','OUT')`, `category_id uuid NOT NULL REFERENCES category ON DELETE RESTRICT`,
`mode text NOT NULL CHECK IN ('UPI','CARD','CASH','NETBANKING','OTHER')`,
`tag text CHECK (tag IN ('NEED','WANT','WASTE'))` nullable, `CHECK (tag IS NULL OR direction = 'OUT')`,
`source text NOT NULL DEFAULT 'MANUAL' CHECK IN ('MANUAL','SMS','IMPORT')` (only MANUAL is written in the MVP; the
CHECK admits the BRD's future values so enabling them needs no migration),
`note text CHECK (note IS NULL OR char_length(note) <= 140)`,
`dedupe_hash text NOT NULL CHECK (dedupe_hash ~ '^[0-9a-f]{64}$')`,
`idempotency_key text NOT NULL CHECK (char_length(idempotency_key) BETWEEN 8 AND 64)`,
`created_at timestamptz NOT NULL DEFAULT now()`.
Uppercase enum values match V5; PLAN/BRD's lowercase `manual` is a display form only.

- `note`: BRD FR-04 lists "note" as an entry field and T42 builds it, so it is included, nullable, capped at 140
  chars. It is the only free-text column and the main privacy risk: never logged, never in error bodies, T42 shows
  a hint not to enter account, card or ID numbers. Included in export and deleted with the row. See Q1.
- Indexes: `UNIQUE (user_id, idempotency_key)` (PLAN acceptance); `(user_id, txn_date)` for cycle range queries;
  `(user_id, dedupe_hash)` non-unique.
- RLS exactly as V5: `ENABLE` + `FORCE`, policy `transaction_self` with `USING (user_id = NULLIF(current_setting('app.current_user_id', true), '')::uuid)` and
  `WITH CHECK (<same> AND finpath_has_consent())`; `GRANT SELECT, INSERT, UPDATE, DELETE ... TO app_user`.
- Repository (`TransactionRepository`): `insert(userId, row)` computes `dedupe_hash` via `TransactionHash` (never
  caller-supplied), `findByIdempotencyKey`, `findById`, `listBetween(from, toExclusive)`, `update`, `delete`.
  Insert uses `ON CONFLICT (user_id, idempotency_key) DO NOTHING` and returns the existing row when it conflicts,
  so T37's replay behaviour is a one-liner. All calls run under the existing RLS-aware transaction wrapper; the
  user id comes from the principal, never a parameter the client controls. Changing amount/date/category/mode on
  update recomputes the hash.

## 3. `[+TABLE]` obligations
`transaction` (personal data):
- Export: `TableRef("transaction","user_id")` in `DataSubjectRegistry.EXPORT` (after `income_source`); the export
  also emits each row's category `code` next to `category_id` so the file is readable without the taxonomy.
- Deletion: add to `DELETE` before `users`. It references `category` only, which is never deleted.
- RLS: `TransactionCrossTenantTest`: B cannot select, update or delete A's row; insert for another user, or
  without consent, is rejected; same `idempotency_key` for two users is allowed, twice for one user is rejected.
  Plus `TransactionRepositoryDbTest` (replay returns the original, equal hashes on identical inserts, changed
  amount differs).

`category` (shared, holds no personal data):
- Export: **not exported** (identical for everyone; the transaction export carries the code). Deletion:
  **never deleted** by `DELETE /me`.
- Because `DataSubjectRegistryTest.everyMigratedTableIsInBothRegistries` fails for any table not listed, add an
  explicit `DataSubjectRegistry.sharedTables() = Set.of("category")` and make that test and `registriesListNoUnknownTable`
  accept a migrated table that is in either a registry or `sharedTables()` (and fail if a shared table has a
  `user_id` column). This is the reviewed exemption; the compliance-review agent should confirm it.
- Access test (the T85 per-table RLS rule): `CategoryAccessTest`: `app_user` can select all seeded rows; insert,
  update and delete are denied; codes are unique and uppercase.

## 4. API / Screens
None in T36. T37 will expose `/transactions` with `amountPaise`, `categoryId` and a read-only `GET /categories`
(authenticated, not user-scoped); T42 shows categories from that call and never derives the hash or any totals.

## 5. Open questions (assumptions)
1. Keep a free-text `note` at all? Assumed yes (BRD FR-04, T42), 140 chars, with the privacy handling above. If
   the owner prefers none, drop the column and the T42 field; nothing else depends on it.
2. Custom categories? Assumed no (system taxonomy only); revisit with a `user_id` + RLS table post-MVP.
3. Duplicate handling: warn, never block, since legitimate repeats exist. Assumed.
4. `idempotency_key` NOT NULL: T37 generates a UUID server-side when a caller (e.g. a future importer) sends none.
5. Seed list above is a proposal; renames only change `name`, never `code`.

## 6. Plan coverage
Covers **T36** (both tables, seed, hash, unique index, `[+TABLE]` for both). Additions not in PLAN.md:
`note` column (from BRD FR-04), `DataSubjectRegistry.sharedTables()` and the registry-test change,
`CategoryAccessTest`, category `code` in the transaction export, the `v1` hash version prefix.

## Compliance follow-ups (accepted / for T37)
- `note` is capped at 140 chars with a UI hint only; T37 must reject long digit runs (9+) and PAN-shaped text in validation. Residual risk until then is accepted (no endpoint exists yet).
- Constraint-violation messages carry the failing row; handlers must never log or echo `getMessage()`.
- `DataSubjectRegistry.sharedTables()` is pinned by `sharedTableSetIsPinned`.
- `TableRef.exportRow` is restricted to two registry constants (allowlist in `DataSubjectService.checkIdentifiers`).
