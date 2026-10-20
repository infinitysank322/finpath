# Contributing

## Branches

One feature = one branch = one FR id from the [BRD](docs/BRD.md).

| Work | Branch name | Example |
| --- | --- | --- |
| A BRD functional requirement | `fr-NN/<slug>` | `fr-12/loan-amortisation` |
| Scaffolding / infra task with no FR | `infra/tNN-<slug>` | `infra/t01-monorepo-root` |

Branch from `master`. Keep a branch to its one FR; split anything else into its own branch.

- A task listing several FRs (e.g. T11 "FR-12, FR-13") uses the first FR id listed.
- Tasks labelled `SCAFFOLD` or `INFRA` in PLAN.md use `infra/` branches and the `INFRA:` prefix.

## Commits

The subject starts with the FR id, or `INFRA:` for non-FR work, then the plan task id:

```
FR-12: T11 amortising loan EMI and schedule
INFRA: T01 monorepo root — gitignore, editorconfig, conventions, docs index
```

## Hard rules

These come from [CLAUDE.md](CLAUDE.md) and are not up for review per-PR:

- All money maths lives in the `api/.../rules` package: pure functions, `BigDecimal`, no floats.
- Every rule has unit tests, and `RulesGoldenTest` (persona figures from BRD section 6) must pass.
- Amounts are stored as paise (`long` / `bigint`); the UI formats INR with lakh/crore.
- Never store account numbers, card numbers or ID numbers.

## Definition of done for new tables

A task marked `[+TABLE]` in [PLAN.md](docs/PLAN.md#3-build-order) is not done until the table is:

1. joined into the DPDP export path,
2. joined into the account-deletion path, and
3. covered by a row-level-security test proving user B cannot read user A's row.

These are acceptance criteria of the same task, never a later pass.
