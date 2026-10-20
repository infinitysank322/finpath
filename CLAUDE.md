# FinPath
Spec: docs/BRD.md (source of truth). Plan: docs/PLAN.md.
## Stack
api/: Spring Boot 3, Java 21, Maven, PostgreSQL (Neon), Flyway, Spring Security + Google OAuth2/JWT
web/: Angular 18+, standalone components, ngx-echarts, Capacitor (Android)
## Rules
- ALL money maths lives in api/.../rules package. Pure functions, BigDecimal, no floats.
- Every rule needs unit tests. Golden tests in RulesGoldenTest must pass (persona figures from BRD section 6).
- Amounts in paise (long) in DB; INR formatting with lakh/crore in UI.
- Never store account numbers, card numbers or IDs.
- One feature = one branch = one FR id from the BRD. Commit message starts with FR id.
## Commands
api: ./mvnw test | web: npm test, ng build