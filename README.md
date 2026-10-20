# FinPath

A web and Android personal-finance assistant that helps salaried Indians track every rupee, get out of debt, invest consistently and see a dated path to financial independence.

- Requirements (source of truth): [docs/BRD.md](docs/BRD.md)
- MVP build plan: [docs/PLAN.md](docs/PLAN.md)
- Docs index: [docs/README.md](docs/README.md)

## Packages

| Package | What | Stack | Lands in |
| --- | --- | --- | --- |
| `api/` | REST API and rules engine (all money maths) | Spring Boot 3, Java 21, Maven, PostgreSQL (Neon), Flyway, Google OAuth2/JWT | T02 |
| `web/` | Responsive PWA and Android app | Angular 18+ standalone components, ngx-echarts, Capacitor | T06 |
| `infra/` | Local compose and Docker support | Docker Compose | T03, T08 |

CI workflows live in `.github/workflows/` (T05), where GitHub Actions requires them.

## Commands

```sh
# api/
./mvnw test

# web/
npm test
ng build
```

## Contributing

One feature = one branch = one FR id. See [CONTRIBUTING.md](CONTRIBUTING.md).
