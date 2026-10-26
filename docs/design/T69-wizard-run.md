# T69 - FR-01 wizard run (record)

Scope: the onboarding wizard after T69 appended the assets step, plus the Investments screen (FR-23).

## What was run, and how

`web/src/app/onboarding/wizard-run.spec.ts` renders the real `OnboardingComponent` with the real step registry
(`ONBOARDING_STEP_PROVIDERS`) in headless Chrome under Karma, drives every step through the DOM (typing, clicking
Next/Save/Add/Finish), and answers every HTTP call with `HttpTestingController`. It is automated and the API is
mocked. **No manual run in a real browser, on a phone, or against a live API was done.** Layout at 380 px, dark mode and
real-device screen-reader behaviour were not exercised by this run.

## Steps covered (7 of 7)

| # | Step | Driven | Asserted request |
| --- | --- | --- | --- |
| 1 | Consent | tick, Give consent | `POST /me/consent {purpose, statementVersion}`; Next disabled until the server re-read says given |
| 2 | Cycle day | 25 -> 26, Save day | `PUT /plan-cycles/start-day {startDay: 26}`; Next disabled until saved |
| 3 | Income | "87,654", Add income | `POST /income-sources` with `amountPaise: 8765400`, MONTHLY; Next gated on one source |
| 4 | Fixed expenses | "1234.50", Add bill | `POST /recurring-templates` with `amountPaise: 123450` |
| 5 | Goals | "5,00,000", date, Add goal | `POST /goals` with `targetAmountPaise: 50000000` |
| 6 | Assets | EPF "4,50,000", then a holding "2,50,000.75" | `POST /holdings` EPF `currentValuePaise: 45000000`; then `currentValuePaise: 25000075`; EPF field starts at 0 |
| 7 | Done | Finish | consent re-checked, saved position cleared, navigates to `/home` |

A second test skips steps 4 to 6 and asserts no non-GET request is sent.

## OQ-1 (EPF)

The EPF balance is its own labelled field in the assets step and the investments form, user-typed, default 0. Nothing
estimates it. If an EPF holding already exists, saving replaces it (PUT) rather than adding a second.

## Not covered

Resume after a reload mid-wizard is covered by `onboarding.component.spec.ts`, not this run. The debts step (T55) is not
registered yet, so it is absent from the run. Real API behaviour (the 400 on long digit runs in `name`, consent 403s) is
covered by API tests, not here.

## Result

Automated run on 2026-10-25: `ng test` 365 of 365 SUCCESS (headless Chromium), `ng build` complete. This is a mocked-HTTP
run, not a manual browser run: 380 px layout, dark mode and screen-reader behaviour were not exercised here and remain
for the T81 audit.
