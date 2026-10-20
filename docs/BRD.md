# FinPath — Business Requirements Document

Oct 20, 2026 · @Sanket

## Executive summary

FinPath (working name) is a web and mobile personal-finance assistant that helps salaried Indians track every rupee, get out of debt, invest consistently and see a dated path to financial independence. Version 1 runs entirely on free-tier infrastructure and works from manual entry, SMS parsing and statement uploads, so it needs no Account Aggregator licence.

| Item | Detail |
| --- | --- |
| Document | Business Requirements Document, version 0.1 (draft) |
| Product owner | Sanket Kale |
| Build approach | Claude Code for development, Claude agents for insight and categorisation features |
| Platforms | Android app (Capacitor) and responsive web PWA, both in the MVP |
| Hosting constraint | Free-tier database and servers only, with a documented upgrade path |
| Reference user | The product owner's own finances (section 4) |

## Problem statement and objectives

Salaried professionals earn steadily but stay stuck because money leaks, debts overlap and investing stops and starts. Five problems drive this:

1. **No single view of money.** Income and spending are spread across salary, rent, UPI, cards, cash and auto-debits.
2. **Unnoticed waste.** Small repeated spends and forgotten subscriptions absorb what could be invested.
3. **Debt pressure.** Several loans with different rates, due dates and structures (EMI, bullet, card EMI) invite missed payments and debt traps.
4. **Inconsistent investing.** SIPs are started and stopped, and products are picked without matching goals or risk.
5. **No goal connection.** Daily choices are not tied to targets like an emergency fund, a debt-free date or the FI corpus.

**Business objectives**

| # | Objective | Measure |
| --- | --- | --- |
| O1 | Every rupee in and out is captured and categorised | 95% of monthly outflow categorised |
| O2 | Unnecessary spending falls | Discretionary spend down 10–20% within 3 months |
| O3 | Debts are cleared in the cheapest order | No missed EMI; costliest debt closed first |
| O4 | Investing stays consistent | Planned monthly investment met in 10 of 12 months |
| O5 | The user always knows their FI date | FI projection refreshed every month |
| O6 | The user avoids new bad debt | Alert before any new EMI pushes EMI-to-income above 40% |

## Scope

Version 1 is a single-user-first, multi-user-ready app for Indian salaried users; it advises and educates but never moves money.

**In scope**

- Income, expense, debt, asset and goal tracking
- Data capture by manual entry, CSV/PDF statement upload, Android SMS parsing (with consent) and recurring templates
- Debt payoff planning, including EMI, bullet (interest-only) and credit-card EMI loans
- Budgeting on a custom cycle (default 25th to 25th)
- Goal planning: emergency fund, marriage or other dated goals, home-loan prepayment, financial independence
- Investment allocation guidance as education and comparison
- Dashboards, charts, monthly reports and reminders
- An AI assistant for categorisation, monthly reviews and questions

**Out of scope for version 1**

- Automatic bank linking via Account Aggregator (needs licensing)
- Executing trades, SIPs or payments
- Personalised buy/sell calls on specific securities (SEBI investment-adviser territory)
- Tax filing; only tax-saving hints are in scope
- iOS native app

**Phases**

| Phase | Content |
| --- | --- |
| MVP | Tracking, budgets, debt module, goals, core dashboard, Android app and web PWA |
| Phase 2 | SMS parsing, statement import, scenario planner, AI monthly review |
| Phase 3 | Multi-user household view, portfolio import from CAS, community templates |

## Target users and reference persona

The primary user is a salaried urban Indian aged 24–40 with EMIs, family responsibilities and a wish to retire early. The reference persona below is real data (October 2026) and doubles as the test fixture for every calculation; anonymise it before sharing this document.

**Persona: software engineer, age 27, Pune, marriage expected late 2028**

| Category | Item | Monthly (₹) | Notes |
| --- | --- | --- | --- |
| Income | Salary in hand | 1,13,000 | Bonus about ₹14,000 a year |
| Income | Rental | 14,000 |  |
| Debt | Home loan | 40,032 | ₹50.16 L outstanding, 8.35%, 297 EMIs left |
| Debt | Phone EMI 1 | 8,000 | Ends in 2 months |
| Debt | Washing machine (credit-card EMI) | 6,000 | Ends in 3 months |
| Debt | Phone EMI 2 | 7,000 | Ends in 11 months |
| Debt | Gold loan, interest only | 2,300 | ₹1.8 L principal due March 2028, about 15.3% |
| Fixed expense | Groceries, milk, fuel, utilities, internet, mobile, maintenance, insurance | 18,833 | Quarterly bills spread monthly |
| Fixed expense | Buffer | 5,000 |  |
| Investment | EPF (employee + employer) | 12,240 | Deducted from salary; no EPS |
| Asset | Stocks and mutual funds |  | About ₹19,400 current value, no active SIP |
| Asset | Cash |  | About ₹15,000; no emergency fund yet |

**Derived figures:** income ₹1,27,000; EMIs ₹63,332 (about 50% of income); planned surplus about ₹39,800 a month, rising to about ₹59,000 by September 2027 as short EMIs end.

**Goals:** emergency fund of 3 months; gold loan closed by March 2028; marriage fund of ₹7–10 L by late 2028; health insurance to be bought; home loan closed and financial independence by age 40, at ₹50,000 a month of expenses in today's money.

## Functional requirements

Ten modules cover the problem; MVP items are marked P1, phase 2 items P2 and phase 3 items P3.

| ID | Module | Requirement | Priority |
| --- | --- | --- | --- |
| FR-01 | Onboarding | Guided setup collects income, debts, fixed expenses, assets and goals, one question at a time | P1 |
| FR-02 | Onboarding | User sets budget cycle start day (default 25th) | P1 |
| FR-03 | Income | Track salary, rental, bonus and side income, recurring or one-off | P1 |
| FR-04 | Expenses | Add an expense in under 10 seconds: amount, category, mode (UPI, card, cash), note | P1 |
| FR-05 | Expenses | Recurring templates for bills, including quarterly ones spread monthly | P1 |
| FR-06 | Expenses | Tag each expense as need, want or waste | P1 |
| FR-07 | Expenses | Add an unplanned or emergency expense; the plan re-balances the rest of the month and goals | P1 |
| FR-08 | Expenses | Import bank and card statements (CSV, PDF) with duplicate detection | P2 |
| FR-09 | Expenses | Parse bank SMS on Android with explicit consent | P2 |
| FR-10 | Budget | Category budgets per cycle with alerts at 80% and 100% | P1 |
| FR-11 | Leakage | Detect subscriptions, repeated small spends and month-on-month spikes | P2 |
| FR-12 | Debt | Support EMI loans, bullet (interest-only) loans and credit-card EMIs | P1 |
| FR-13 | Debt | Show effective interest rate, total interest left and payoff date per loan | P1 |
| FR-14 | Debt | Payoff plan by avalanche (highest rate first) or snowball (smallest first) | P1 |
| FR-15 | Debt | EMI roll-off timeline that redirects freed EMIs to goals | P1 |
| FR-16 | Debt | Bullet-loan sinking fund with monthly set-aside and behind-schedule alert | P1 |
| FR-17 | Debt | Home-loan prepayment simulator: interest saved and new end date | P1 |
| FR-18 | Debt | Debt-trap guard: warn before a new EMI pushes EMI-to-income above 40% | P1 |
| FR-19 | Goals | Dated goals with target, current amount, monthly need and on-track status | P1 |
| FR-20 | Goals | Emergency fund goal sized from actual monthly outflow | P1 |
| FR-21 | Goals | FI calculator: corpus needed, FI date, required step-up SIP | P1 |
| FR-22 | Allocation | Monthly surplus split by the priority waterfall (section 6) | P1 |
| FR-23 | Investments | Track EPF, PPF, NPS, mutual funds, stocks, FDs and gold with current value | P1 |
| FR-24 | Investments | Educational product comparison by goal horizon and risk (index funds, debt funds, RD, PPF) | P2 |
| FR-25 | Investments | Import holdings from CAS or broker CSV | P3 |
| FR-26 | Consistency | Monthly checklist: EMIs paid, SIPs done, goal transfers made, with streaks | P1 |
| FR-27 | Consistency | Reminders before due dates and on cycle day | P1 |
| FR-28 | Scenarios | What-if planner: salary hike, new expense, prepayment, marriage cost | P2 |
| FR-29 | AI assistant | Auto-categorise transactions and answer questions about the user's data | P2 |
| FR-30 | AI assistant | Monthly review: what went well, leaks found, next month's plan | P2 |
| FR-31 | Reports | Monthly and yearly PDF or CSV export | P2 |
| FR-32 | Household | Shared view for spouse with separate and joint accounts | P3 |

## Financial logic and rules engine

All money rules live in one deterministic, unit-tested rules engine; the AI layer explains results but never calculates them.

**Priority waterfall for each cycle's surplus**

1. Mandatory outflows: EMIs, fixed expenses, insurance premiums
2. Emergency fund to 1 month of outflow
3. Any debt above 12% (credit cards, personal loans, gold loans), avalanche order; bullet loans via sinking fund
4. Emergency fund to the target (3–6 months)
5. Dated goals within 3 years, held in safe instruments
6. Minimum habit SIP (configurable, default ₹5,000)
7. Split of the remainder between long-term SIPs and prepayment of debt below 12%, by user-set ratio

**Core formulas**

EMI for principal P, monthly rate r, n months:

```latex
EMI = \frac{P \cdot r \cdot (1+r)^n}{(1+r)^n - 1}
```

Effective annual rate of an interest-only loan: monthly interest × 12 ÷ principal. FI corpus: annual expense at FI age, net of passive income, × a multiplier (default 30 for retirement before 45, i.e. a 3.3% withdrawal rate). Future value of a step-up SIP uses monthly compounding with an annual step-up.

**Rules and thresholds (configurable)**

| Rule | Default | Action |
| --- | --- | --- |
| EMI-to-income ratio | Warn above 40%, block suggestion above 50% | Debt-trap guard alert |
| Credit card | Full statement paid every cycle | Alert 5 days before due date |
| Inflation | 6% a year | Used in FI and goal maths |
| Equity return | 12% a year | Projections only, shown with a range |
| Debt or EPF return | 7–8.25% a year | Projections |
| Goal horizon under 3 years | No equity | Suggest RD, liquid or short-duration debt funds |
| Freed EMI | 100% redirected by default | Roll-off timeline |
| Category overspend | 80% and 100% of budget | Push alert |

**Validation against the persona:** the engine must reproduce these figures within 1%: gold loan rate 15.3%; home loan interest left about ₹69 L; EMI to close the home loan in 13 years about ₹52,800; interest saved about ₹36 L; surplus ₹39,800 a month.

## Dashboards, charts and statistics

The home screen answers three questions at a glance: how much is left this cycle, am I on track, and when am I free. Every chart works on a 380 px phone screen and in dark mode.

| Screen | Visual | Statistic shown |
| --- | --- | --- |
| Home | Cycle progress ring | Spent vs budget, days left, safe-to-spend per day |
| Home | Net-worth line | Assets minus liabilities by month |
| Home | FI countdown card | Projected FI date and change vs last month |
| Spending | Category donut | Share of spend by category, need/want/waste split |
| Spending | Month-on-month bars | Each category against its 3-month average |
| Spending | Leakage list | Subscriptions and repeated small spends with yearly cost |
| Debt | Payoff timeline | Each loan's end date, EMI roll-off steps |
| Debt | Principal vs interest stacked area | How each EMI splits over time |
| Debt | Ratio gauge | EMI-to-income ratio against the 40% line |
| Goals | Progress bars | Saved vs target, monthly need, on-track flag |
| Investments | Allocation donut | Equity, debt, EPF, gold, cash |
| Investments | Consistency heatmap | Months where planned investing was met |
| FI planner | Corpus projection line | Projected corpus vs required corpus, with a return range band |
| Reports | Cash-flow waterfall | Income to EMIs, expenses, goals, investments, leftover |

**Key statistics computed every cycle:** savings rate, EMI-to-income ratio, discretionary-spend share, emergency-fund months covered, debt-free date, FI date, investment consistency streak and net worth.

## Non-functional requirements

Financial data is the most sensitive data a user owns, so security and privacy rank above features.

| Area | Requirement |
| --- | --- |
| Security | TLS everywhere; row-level security so a user reads only their own rows; secrets in environment variables, never in code |
| Data protection | Statements and SMS parsed then discarded; no account numbers, card numbers or IDs stored; amounts and merchants only |
| Privacy law | Consent capture, purpose statement, data export and account deletion, in line with India's DPDP Act 2023 |
| Compliance | Investment content labelled educational; no specific security recommendations; no money movement |
| AI usage | Only aggregated, de-identified data sent to the AI model; AI features can be switched off |
| Performance | Dashboard loads in under 2 s on 4G; expense entry saves in under 500 ms |
| Availability | Best effort on free tier; free databases that sleep must wake on first request without data loss |
| Offline | PWA caches the last dashboard and queues new expenses offline |
| Accessibility | WCAG 2.1 AA contrast, screen-reader labels on charts |
| Localisation | Indian number format (lakh, crore), INR, English first; Marathi and Hindi later |
| Backups | Daily logical backup (pg\_dump) to free object storage, 7-day retention |
| Testing | 90% unit-test coverage on the rules engine; persona figures as golden tests |

## Technical architecture

The recommended stack is Angular PWA + Spring Boot + PostgreSQL, matching the product owner's existing skills and running at ₹0 a month; only the AI features have a usage cost.

&#91;embedded content: system architecture · 6 components\]

The rules engine in the API does every calculation; the Claude agents only receive de-identified aggregates and explain the results.

**Free-tier stack (checked October 2026)**

| Layer | Choice | Free allowance and caveat |
| --- | --- | --- |
| Web and mobile UI | Angular PWA, ngx-echarts for charts; Capacitor for the Android build | Static hosting on Cloudflare Pages or Vercel free plan |
| API and rules engine | Spring Boot 3, Java 21, Docker | Oracle Cloud Always Free Ampere A1 VM: cut to 2 OCPU / 12 GB RAM from 18 Aug 2026; capacity can be hard to get in busy regions and idle instances may be reclaimed ([TerminalBytes](https://terminalbytes.com/oracle-cloud-free-tier-changes-2026)) |
| Database (option A) | Neon serverless PostgreSQL | 0.5 GB storage and 100 CU-hours per project; scales to zero after 5 min idle and wakes on the next query ([Neon pricing](https://neon.com/pricing)) |
| Database (option B) | Supabase PostgreSQL | 500 MB database, 1 GB files, 5 GB egress; free projects pause after a week of inactivity ([Supabase limits](https://automationatlas.io/answers/supabase-free-tier-limits-2026/)) |
| Auth | Spring Security with Google OAuth2 and JWT | Free |
| Notifications | Web Push and a free-tier SMTP service | Free within send limits |
| Backups | Nightly pg\_dump to Oracle Object Storage | Free allowance |
| CI/CD | GitHub + GitHub Actions | Free for personal repos within minutes quota |
| AI | Claude API: a small model for categorisation, a larger one for monthly reviews | Paid per use; cap at a fixed monthly budget, rules-based fallback when off |

Recommendation: Neon for the database, so the database never pauses for a week, and the Oracle VM for the API. 0.5 GB holds years of one household's transactions.

**Core data model**

| Entity | Key fields |
| --- | --- |
| user | id, name, cycle\_start\_day, currency, risk\_profile, fi\_target\_age |
| income\_source | user\_id, type, amount, frequency, start/end date |
| transaction | user\_id, date, amount, direction, category\_id, mode, tag (need/want/waste), source (manual/sms/import), dedupe\_hash |
| category | id, name, parent\_id, budget\_per\_cycle |
| loan | user\_id, lender, type (emi/bullet/card\_emi), principal\_outstanding, rate, emi, emis\_left, bullet\_due\_date |
| goal | user\_id, type, target\_amount, target\_date, current\_amount, priority |
| holding | user\_id, asset\_class, name, invested, current\_value, monthly\_contribution |
| plan\_cycle | user\_id, cycle\_start, surplus, allocations (json), status |
| checklist\_item | plan\_cycle\_id, type, amount, done\_at |
| scenario | user\_id, name, overrides (json), result (json) |

**Claude agents in the product**

| Agent | Job | Data access |
| --- | --- | --- |
| Categoriser | Turn merchant text and notes into a category and need/want/waste tag | One transaction at a time, no identity |
| Monthly reviewer | Write the cycle review: wins, leaks, next cycle's plan | Aggregates from the rules engine |
| Ask-my-money | Answer questions such as "can I afford a ₹40,000 phone?" | Read-only API tools; numbers come from the engine |

**Build with Claude Code:** one monorepo (`/web`, `/api`, `/infra`), a CLAUDE.md holding this BRD's rules and the persona golden tests, and one feature branch per FR.

## Roadmap, success metrics and risks

The MVP is used by the product owner from its first cycle; each later phase starts only after the previous one passes its gate.

&#91;embedded content: roadmap · 3 phases, 2 gates, not to scale\]

**Success metrics (measured on the persona first)**

| Metric | Baseline (Oct 2026) | Target |
| --- | --- | --- |
| Expenses captured | Partial (Excel) | 95% of outflow per cycle |
| Savings and investment rate | EPF only | Planned allocation met 10 of 12 cycles |
| Emergency fund | About ₹15,000 | 3 months of outflow by late 2028 |
| Gold loan | ₹1.8 L due Mar 2028 | Closed by Mar 2028 |
| Marriage fund | ₹0 | ₹7.5 L+ by late 2028 |
| EMI-to-income ratio | About 50% | Under 40% from Jan 2027 |
| Weekly active use |  | 4 of 4 weeks |

**Risks**

| Risk | Impact | Mitigation |
| --- | --- | --- |
| Free tiers change or shrink (Oracle halved its ARM allowance in 2026) | Downtime or forced migration | Docker everywhere, standard Postgres, documented move to a ₹500–1,500 a month host |
| Free database sleeps or pauses | Slow first load or outage | Neon auto-wake; scheduled ping if Supabase is used |
| Manual entry fatigue | Data gaps, wrong plans | 10-second entry, recurring templates, SMS parsing, streaks |
| Data breach | Severe trust and legal harm | Row-level security, no IDs stored, encryption, minimal AI sharing |
| Advice seen as regulated | SEBI exposure | Educational wording, no specific securities, disclaimers |
| AI cost grows | Budget overrun | Hard monthly cap, small model for routine jobs, rules fallback |
| Projections taken as certain | Poor decisions | Show ranges and assumptions beside every projection |

**Assumptions:** inflation 6%, equity 12%, EPF about 8%; salary grows about 8% a year; the persona's figures are as of October 2026.

**Open questions**

- [ ] EPF total balance and whether the previous employer's PF was transferred
- [ ] Health insurance premium once a policy is chosen
- [ ] App name and domain
- [ ] Angular or React for the UI (Angular assumed)

## Sources

- [Neon pricing plans](https://neon.com/pricing)
- [Neon: managed Postgres free tiers compared](https://neon.com/faqs/managed-postgres-databases-free-tier)
- [Supabase free tier limits in 2026](https://automationatlas.io/answers/supabase-free-tier-limits-2026/)
- [Oracle Cloud free tier changes 2026](https://terminalbytes.com/oracle-cloud-free-tier-changes-2026)

Free-tier limits change often; recheck them before deployment.
